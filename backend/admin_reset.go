package main

import (
	"database/sql"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"

	"golang.org/x/crypto/bcrypt"
)

const minimumPasswordCharacters = 10

func validPasswordLength(password string) bool {
	return utf8.ValidString(password) && utf8.RuneCountInString(password) >= minimumPasswordCharacters
}

// setPasswordAndRevokeSessions belongs to the same transaction as any account
// edits. A failure to revoke sessions must never leave the new password active.
func setPasswordAndRevokeSessions(tx *sql.Tx, userID, passwordHash string) error {
	result, err := tx.Exec("UPDATE users SET password_hash=? WHERE id=?", passwordHash, userID)
	if err != nil {
		return err
	}
	if n, err := result.RowsAffected(); err != nil {
		return err
	} else if n != 1 {
		return errors.New("password reset requires one existing account")
	}
	_, err = tx.Exec("UPDATE sessions SET revoked=1 WHERE user_id=?", userID)
	return err
}

// resetAdminPassword deliberately does not call openServer: this maintenance
// operation cannot create a database, apply migrations or bootstrap an account.
// The operator must stop the service before invoking it.
func resetAdminPassword(dbPath, username, password string) error {
	if dbPath == "" || username == "" {
		return errors.New("reset-admin requires --db EXISTING_DB and --username EXISTING_ADMIN")
	}
	if !validPasswordLength(password) {
		return errors.New("password must contain at least 10 characters")
	}
	info, err := os.Stat(dbPath)
	if err != nil {
		return fmt.Errorf("existing database required: %w", err)
	}
	if !info.Mode().IsRegular() {
		return errors.New("database path must identify an existing regular file")
	}
	absPath, err := filepath.Abs(dbPath)
	if err != nil {
		return err
	}
	// Keep the drive prefix intact for modernc SQLite on Windows while escaping
	// spaces and URI metacharacters in database filenames.
	escapedPath := strings.NewReplacer("%2F", "/", "%3A", ":").Replace(url.PathEscape(filepath.ToSlash(absPath)))
	db, err := sql.Open("sqlite", "file:"+escapedPath+"?mode=rw")
	if err != nil {
		return err
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	if _, err = db.Exec("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=10000"); err != nil {
		return err
	}
	var integrity string
	if err = db.QueryRow("PRAGMA integrity_check").Scan(&integrity); err != nil || integrity != "ok" {
		return errors.New("existing database failed integrity check")
	}
	var version int
	if err = db.QueryRow("SELECT coalesce(max(version),0) FROM schema_migrations").Scan(&version); err != nil || version != 1 {
		return errors.New("reset-admin requires the existing supported database schema")
	}
	passwordHash, err := bcrypt.GenerateFromPassword([]byte(password), 12)
	if err != nil {
		return errors.New("password could not be hashed")
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var userID, role string
	if err = tx.QueryRow("SELECT id,role FROM users WHERE username=?", username).Scan(&userID, &role); err != nil {
		return errors.New("administrator account does not exist")
	}
	if role != "admin" {
		return errors.New("selected account is not an administrator")
	}
	if err = setPasswordAndRevokeSessions(tx, userID, string(passwordHash)); err != nil {
		return err
	}
	return tx.Commit()
}

func runResetAdmin(args []string, input io.Reader, output io.Writer) error {
	flags := flag.NewFlagSet("reset-admin", flag.ContinueOnError)
	flags.SetOutput(output)
	dbPath := flags.String("db", "", "Existing SQLite path; stop the service first")
	username := flags.String("username", "", "Existing administrator username")
	passwordStdin := flags.Bool("password-stdin", false, "Read the new password only from standard input")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 0 || *dbPath == "" || *username == "" || !*passwordStdin {
		return errors.New("reset-admin requires --db EXISTING_DB --username EXISTING_ADMIN --password-stdin; stop the service first")
	}
	const maximumInputBytes = 1024
	passwordBytes, err := io.ReadAll(io.LimitReader(input, maximumInputBytes+1))
	if err != nil {
		return errors.New("could not read password from standard input")
	}
	if len(passwordBytes) > maximumInputBytes {
		return errors.New("password input exceeds supported size")
	}
	password := strings.TrimSuffix(strings.TrimSuffix(string(passwordBytes), "\n"), "\r")
	if strings.ContainsAny(password, "\r\n") {
		return errors.New("password input must contain one line")
	}
	if err = resetAdminPassword(*dbPath, *username, password); err != nil {
		return err
	}
	_, err = fmt.Fprintln(output, "Administrator password changed; all account sessions revoked")
	return err
}
