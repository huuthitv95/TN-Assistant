package main

import (
	"database/sql"
	"errors"
	"net/http"
	"regexp"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

var tnUsername = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}$`)

func validateTNDevice(m Object) (string, string, error) {
	did, name := str(m, "device_id"), strings.TrimSpace(str(m, "device_name"))
	if len(did) < 8 || len(did) > 128 || strings.ContainsAny(did, "\x00\r\n") || name == "" || len(name) > 240 || strings.ContainsAny(name, "\x00\r\n") {
		return "", "", problem(400, "INVALID_DEVICE", "Bounded device_id and device_name are required")
	}
	return did, name, nil
}

// Query through the active transaction: the SQLite connection pool has one
// connection, and a second database query here would block the transaction.
func (s *Server) checkFleetDeviceLimit(tx *sql.Tx) error {
	var raw string
	err := tx.QueryRow("SELECT data FROM records WHERE kind='config' AND id='runtime'").Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) && s.cfg.RuntimeMode == "" {
		return nil
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	limit := 100
	if raw != "" {
		var configured int
		if err = tx.QueryRow("SELECT json_extract(data,'$.max_devices') FROM records WHERE kind='config' AND id='runtime'").Scan(&configured); err != nil {
			return err
		}
		if configured < 1 || configured > 100 {
			return problem(503, "INVALID_FLEET_POLICY", "Device policy is unavailable")
		}
		limit = configured
	}
	var count int
	if err = tx.QueryRow("SELECT count(*) FROM devices").Scan(&count); err != nil {
		return err
	}
	if count >= limit {
		return problem(409, "FLEET_DEVICE_LIMIT", "The configured fleet device limit has been reached")
	}
	return nil
}

func (s *Server) bindLoginDevice(tx *sql.Tx, uid string, m Object) (string, error) {
	did, name, err := validateTNDevice(m)
	if err != nil {
		return "", err
	}
	var owner string
	err = tx.QueryRow("SELECT user_id FROM devices WHERE id=?", did).Scan(&owner)
	if err == nil {
		if owner != uid {
			return "", problem(409, "DEVICE_OWNERSHIP_REQUIRED", "Device belongs to another account; use its authenticated registration or linking flow")
		}
		return did, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	var limit, count int
	if err = tx.QueryRow("SELECT device_limit FROM users WHERE id=?", uid).Scan(&limit); err != nil {
		return "", err
	}
	if err = tx.QueryRow("SELECT count(*) FROM devices WHERE user_id=?", uid).Scan(&count); err != nil {
		return "", err
	}
	if count >= limit {
		return "", problem(409, "DEVICE_LIMIT", "Account device limit reached")
	}
	if err = s.checkFleetDeviceLimit(tx); err != nil {
		return "", err
	}
	if _, err = tx.Exec("INSERT INTO devices(id,user_id,name,created_at) VALUES(?,?,?,?)", did, uid, name, now()); err != nil {
		return "", err
	}
	return did, nil
}

func (s *Server) registerTN(w http.ResponseWriter, r *http.Request) error {
	m, err := body(r)
	if err != nil {
		return err
	}
	for key := range m {
		if !allowedType(key, []string{"username", "password", "name", "device_id", "device_name"}) {
			return problem(400, "INVALID_REGISTRATION", "Unknown registration field")
		}
	}
	username, password := strings.TrimSpace(str(m, "username")), str(m, "password")
	if !tnUsername.MatchString(username) {
		return problem(400, "INVALID_USERNAME", "Username must contain 3..64 letters, digits, dots, underscores or hyphens")
	}
	if !validPasswordLength(password) || len(password) > 72 {
		return problem(400, "WEAK_PASSWORD", "Password must contain at least 10 characters and at most 72 UTF-8 bytes")
	}
	name := strings.TrimSpace(str(m, "name"))
	if name == "" {
		name = username
	}
	if len(name) > 240 || strings.ContainsAny(name, "\x00\r\n") {
		return problem(400, "INVALID_NAME", "A bounded name is required")
	}
	var principal *Principal
	if authorization := r.Header.Get("Authorization"); authorization != "" {
		if !strings.HasPrefix(authorization, "Bearer ") || len(authorization) <= 7 {
			return problem(401, "UNAUTHORIZED", "Valid guest bearer required")
		}
		principal, err = s.authenticate(strings.TrimPrefix(authorization, "Bearer "))
		if err != nil {
			return problem(401, "TOKEN_EXPIRED", "Guest access token expired or revoked")
		}
		if str(principal.User, "role") != "guest" {
			return problem(409, "ACCOUNT_ALREADY_REGISTERED", "Only an authenticated guest can upgrade an existing account")
		}
	}
	did := str(m, "device_id")
	if principal != nil {
		ownedDevice := str(principal.Device, "id")
		if ownedDevice == "" || (did != "" && did != ownedDevice) {
			return problem(403, "DEVICE_OWNERSHIP_REQUIRED", "Guest bearer must own the registration device")
		}
		did = ownedDevice
	}
	if did != "" && principal == nil {
		if _, _, err = validateTNDevice(m); err != nil {
			return err
		}
	}
	passwordHash, err := bcrypt.GenerateFromPassword([]byte(password), 12)
	if err != nil {
		return problem(400, "WEAK_PASSWORD", "Password cannot be hashed")
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var existing int
	if err = tx.QueryRow("SELECT count(*) FROM users WHERE lower(username)=lower(?)", username).Scan(&existing); err != nil {
		return err
	}
	if existing != 0 {
		return problem(409, "USERNAME_TAKEN", "Username is unavailable")
	}
	uid := ident()
	if principal != nil {
		uid = str(principal.User, "id")
		// Recheck the session and ownership under the write transaction so an
		// admin unlink, reset or concurrent upgrade cannot race this request.
		var valid int
		if err = tx.QueryRow("SELECT count(*) FROM sessions s JOIN users u ON u.id=s.user_id JOIN devices d ON d.id=s.device_id WHERE s.id=? AND s.revoked=0 AND s.access_expires>unixepoch() AND u.id=? AND u.role='guest' AND d.id=? AND d.user_id=u.id", principal.SessionID, uid, did).Scan(&valid); err != nil {
			return err
		}
		if valid != 1 {
			return problem(409, "GUEST_SESSION_CHANGED", "Guest session or device ownership changed; authenticate again")
		}
		if _, err = tx.Exec("UPDATE users SET username=?,name=?,role='user',password_hash=? WHERE id=? AND role='guest'", username, name, string(passwordHash), uid); err != nil {
			return problem(409, "USERNAME_TAKEN", "Username is unavailable")
		}
		if _, err = tx.Exec("UPDATE sessions SET revoked=1 WHERE user_id=?", uid); err != nil {
			return err
		}
	} else {
		if _, err = tx.Exec("INSERT INTO users(id,username,name,role,password_hash,created_at) VALUES(?,?,?,'user',?,?)", uid, username, name, string(passwordHash), now()); err != nil {
			return problem(409, "USERNAME_TAKEN", "Username is unavailable")
		}
		if did != "" {
			if _, err = s.bindLoginDevice(tx, uid, m); err != nil {
				return err
			}
		}
	}
	if err = runtimeAudit(tx, uid, "tn_account_registered", Object{"guest_upgraded": principal != nil, "device_bound": did != ""}); err != nil {
		return err
	}
	out, err := s.tokens(tx, uid, did, "")
	if err != nil {
		return err
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	send(w, 200, s.hydrateTokens(out))
	return nil
}

func (s *Server) adminResetUserPassword(w http.ResponseWriter, r *http.Request, actor *Principal, uid string) error {
	if r.Method != "POST" || r.URL.Path != "/v1/admin/users/"+uid+"/reset-password" || uid == "" {
		return problem(405, "METHOD_NOT_ALLOWED", "POST /v1/admin/users/{id}/reset-password required")
	}
	m, err := body(r)
	if err != nil {
		return err
	}
	if len(m) != 1 {
		return problem(400, "INVALID_PASSWORD_RESET", "Only password is accepted")
	}
	password := str(m, "password")
	if !validPasswordLength(password) || len(password) > 72 {
		return problem(400, "WEAK_PASSWORD", "Password must contain at least 10 characters and at most 72 UTF-8 bytes")
	}
	passwordHash, err := bcrypt.GenerateFromPassword([]byte(password), 12)
	if err != nil {
		return err
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var role, username string
	if err = tx.QueryRow("SELECT role,coalesce(username,'') FROM users WHERE id=?", uid).Scan(&role, &username); err != nil {
		return err
	}
	if role == "admin" {
		return problem(403, "ADMIN_RESET_REQUIRES_MAINTENANCE", "Administrator passwords require the existing maintenance reset flow")
	}
	if username == "" {
		return problem(409, "ACCOUNT_NOT_REGISTERED", "Register a TN account before resetting its password")
	}
	if err = setPasswordAndRevokeSessions(tx, uid, string(passwordHash)); err != nil {
		return err
	}
	if err = runtimeAudit(tx, str(actor.User, "id"), "user_password_reset", Object{"user_id": uid, "sessions_revoked": true}); err != nil {
		return err
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	send(w, 200, Object{"ok": true, "sessions_revoked": true})
	return nil
}
