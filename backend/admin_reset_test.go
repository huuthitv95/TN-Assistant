package main

import (
	"bytes"
	"database/sql"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/crypto/bcrypt"
)

func TestPasswordMinimumBootstrapAndAdminAPI(t *testing.T) {
	for _, tc := range []struct {
		name, password string
		valid          bool
	}{
		{"nine", "fixture-9", false},
		{"ten", "fixture-10", true},
		{"nine-unicode", strings.Repeat("ấ", 9), false},
		{"ten-unicode", strings.Repeat("ấ", 10), true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, err := openServer(Config{DBPath: filepath.Join(t.TempDir(), "bootstrap.db"), AdminPassword: tc.password})
			if (err == nil) != tc.valid {
				t.Fatalf("bootstrap validity = %t, want %t", err == nil, tc.valid)
			}
			if s != nil {
				s.db.Close()
			}
		})
	}
	s := testServer(t)
	admin := adminToken(t, s)
	status, m := call(t, s, "POST", "/v1/admin/users", admin, Object{"username": "fixture-user", "name": "Fixture user", "role": "user", "password": "fixture-9"})
	requireStatus(t, 400, status, m)
	status, m = call(t, s, "POST", "/v1/admin/users", admin, Object{"username": "fixture-user", "name": "Fixture user", "role": "user", "password": "fixture-10"})
	requireStatus(t, 200, status, m)
	uid := str(m, "id")
	status, m = call(t, s, "PUT", "/v1/admin/users/"+uid, admin, Object{"name": "Fixture user", "role": "user", "password": "fixture-9"})
	requireStatus(t, 400, status, m)
	status, m = call(t, s, "PUT", "/v1/admin/users/"+uid, admin, Object{"name": "Fixture user", "role": "user", "password": "updated-10"})
	requireStatus(t, 200, status, m)
	if str(m, "username") != "fixture-user" {
		t.Fatal("password update lost omitted username")
	}
}

func TestResetAdminRevokesAllSessionsAndPreservesAccount(t *testing.T) {
	s := testServer(t)
	first := adminToken(t, s)
	adminToken(t, s)
	var id, beforeCreated string
	if err := s.db.QueryRow("SELECT id,created_at FROM users WHERE username='admin'").Scan(&id, &beforeCreated); err != nil {
		t.Fatal(err)
	}
	s.db.Close()
	var output bytes.Buffer
	err := runResetAdmin([]string{"--db", s.cfg.DBPath, "--username", "admin", "--password-stdin"}, strings.NewReader("updated-10\r\n"), &output)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(output.String(), "updated-10") {
		t.Fatal("reset output disclosed the password")
	}
	db, err := sql.Open("sqlite", s.cfg.DBPath)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	s.db = db
	var passwordHash, afterCreated string
	if err = db.QueryRow("SELECT password_hash,created_at FROM users WHERE id=? AND role='admin'", id).Scan(&passwordHash, &afterCreated); err != nil {
		t.Fatal(err)
	}
	if afterCreated != beforeCreated {
		t.Fatal("reset replaced the administrator account")
	}
	if err = bcrypt.CompareHashAndPassword([]byte(passwordHash), []byte("updated-10")); err != nil {
		t.Fatal("new password was not stored")
	}
	if cost, err := bcrypt.Cost([]byte(passwordHash)); err != nil || cost != 12 {
		t.Fatal("reset must retain bcrypt cost 12")
	}
	var total, revoked int
	if err = db.QueryRow("SELECT count(*),sum(revoked) FROM sessions WHERE user_id=?", id).Scan(&total, &revoked); err != nil || total != 2 || revoked != total {
		t.Fatal("reset did not revoke every administrator session")
	}
	status, m := call(t, s, "GET", "/v1/admin/users", first, nil)
	requireStatus(t, 401, status, m)
	status, m = call(t, s, "POST", "/v1/auth/login", "", Object{"username": "admin", "password": "updated-10"})
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "GET", "/v1/admin/users", str(m, "access_token"), nil)
	requireStatus(t, 200, status, m)
}

func TestResetAdminRefusesMissingDatabaseAccountAndNonAdmin(t *testing.T) {
	missingPath := filepath.Join(t.TempDir(), "missing.db")
	if err := resetAdminPassword(missingPath, "admin", "updated-10"); err == nil {
		t.Fatal("reset accepted a missing database")
	}
	if _, err := os.Stat(missingPath); !os.IsNotExist(err) {
		t.Fatal("reset created a missing database")
	}
	blankPath := filepath.Join(t.TempDir(), "empty.db")
	if err := os.WriteFile(blankPath, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := resetAdminPassword(blankPath, "admin", "updated-10"); err == nil {
		t.Fatal("reset accepted an uninitialized database")
	}
	blankDB, err := sql.Open("sqlite", blankPath)
	if err != nil {
		t.Fatal(err)
	}
	defer blankDB.Close()
	var tables int
	if err = blankDB.QueryRow("SELECT count(*) FROM sqlite_master WHERE type='table'").Scan(&tables); err != nil || tables != 0 {
		t.Fatal("reset initialized database tables")
	}
	s := testServer(t)
	if _, err = s.db.Exec("INSERT INTO users(id,username,name,role,created_at) VALUES('fixture-nonadmin','fixture-user','Fixture user','user',?)", now()); err != nil {
		t.Fatal(err)
	}
	for _, username := range []string{"missing-admin", "fixture-user"} {
		if err = resetAdminPassword(s.cfg.DBPath, username, "updated-10"); err == nil {
			t.Fatal("reset accepted a missing or non-admin account")
		}
	}
	var count int
	if err = s.db.QueryRow("SELECT count(*) FROM users").Scan(&count); err != nil || count != 2 {
		t.Fatal("reset changed the set of accounts")
	}
}

func TestPasswordResetRollsBackPasswordAndFieldsOnRevocationFailure(t *testing.T) {
	s := testServer(t)
	admin := adminToken(t, s)
	var id, beforeHash, beforeName string
	if err := s.db.QueryRow("SELECT id,password_hash,name FROM users WHERE username='admin'").Scan(&id, &beforeHash, &beforeName); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("CREATE TRIGGER fixture_revoke_failure BEFORE UPDATE OF revoked ON sessions BEGIN SELECT RAISE(ABORT, 'fixture revocation failed'); END"); err != nil {
		t.Fatal(err)
	}
	status, m := call(t, s, "PUT", "/v1/admin/users/"+id, admin, Object{"name": "Changed fixture", "role": "admin", "device_limit": 2, "password": "updated-10"})
	requireStatus(t, 409, status, m)
	if err := resetAdminPassword(s.cfg.DBPath, "admin", "updated-10"); err == nil {
		t.Fatal("reset ignored session revocation failure")
	}
	var afterHash, afterName string
	var limit, revoked int
	if err := s.db.QueryRow("SELECT password_hash,name,device_limit FROM users WHERE id=?", id).Scan(&afterHash, &afterName, &limit); err != nil {
		t.Fatal(err)
	}
	if beforeHash != afterHash || beforeName != afterName || limit != 10 {
		t.Fatal("password reset failure partially committed account changes")
	}
	if err := s.db.QueryRow("SELECT revoked FROM sessions WHERE access_hash=?", hash(admin)).Scan(&revoked); err != nil || revoked != 0 {
		t.Fatal("failed reset changed existing session")
	}
}

func TestResetAdminRequiresExplicitFlagsAndBoundedSingleLineInput(t *testing.T) {
	for _, tc := range []struct {
		name  string
		args  []string
		input string
	}{
		{"no-flags", nil, "updated-10"},
		{"no-db", []string{"--username", "admin", "--password-stdin"}, "updated-10"},
		{"no-stdin-flag", []string{"--db", "missing.db", "--username", "admin"}, "updated-10"},
		{"short", []string{"--db", "missing.db", "--username", "admin", "--password-stdin"}, "fixture-9"},
		{"multiline", []string{"--db", "missing.db", "--username", "admin", "--password-stdin"}, "updated-10\nsecond-line"},
		{"oversized", []string{"--db", "missing.db", "--username", "admin", "--password-stdin"}, strings.Repeat("x", 1025)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var output bytes.Buffer
			if err := runResetAdmin(tc.args, strings.NewReader(tc.input), &output); err == nil {
				t.Fatal("invalid reset request accepted")
			}
			if strings.Contains(output.String(), tc.input) {
				t.Fatal("reset output disclosed input")
			}
		})
	}
}
