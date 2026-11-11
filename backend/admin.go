package main

import (
	"database/sql"
	"encoding/hex"
	"golang.org/x/crypto/bcrypt"
	"net/http"
	"net/url"
	"strings"
)

func (s *Server) admin(w http.ResponseWriter, r *http.Request, p *Principal) error {
	part := strings.TrimPrefix(r.URL.Path, "/v1/admin/")
	pieces := strings.Split(part, "/")
	kind := pieces[0]
	id := ""
	if len(pieces) > 1 {
		id = pieces[1]
	}
	method := r.Method
	switch kind {
	case "runtime":
		if len(pieces) != 2 || id != "config" {
			return problem(404, "NOT_FOUND", "Unknown runtime resource")
		}
		return s.adminRuntimeConfig(w, r, p)
	case "operations":
		if len(pieces) != 1 || method != "GET" {
			return problem(405, "METHOD_NOT_ALLOWED", "GET required")
		}
		return s.adminOperations(w, r)
	case "ai":
		return s.adminAI(w, r, p, pieces)
	case "premium-local":
		if len(pieces) != 2 || id == "" {
			return problem(404, "NOT_FOUND", "User id required")
		}
		return s.adminLocalPremium(w, r, p, id)
	case "overview":
		if method != "GET" {
			return problem(405, "METHOD_NOT_ALLOWED", "GET required")
		}
		out := Object{"sandbox": true, "providers_live_verified": false}
		for _, table := range []string{"users", "devices", "payments", "entitlements"} {
			var n int
			if e := s.db.QueryRow("SELECT count(*) FROM " + table).Scan(&n); e != nil {
				return e
			}
			out[table] = n
		}
		var paid int64
		s.db.QueryRow("SELECT coalesce(sum(amount),0) FROM payments WHERE status='paid'").Scan(&paid)
		out["sandbox_paid_amount"] = paid
		send(w, 200, out)
		return nil
	case "providers":
		if method == "GET" {
			providers, e := s.providers()
			if e != nil {
				return e
			}
			send(w, 200, providers.redacted())
			return nil
		}
		if method == "PUT" {
			m, e := body(r)
			if e != nil {
				return e
			}
			if e = s.saveProviders(m); e != nil {
				return e
			}
			providers, e := s.providers()
			if e != nil {
				return e
			}
			send(w, 200, providers.redacted())
			return nil
		}
		return problem(405, "METHOD_NOT_ALLOWED", "GET/PUT required")
	case "config":
		if method == "GET" {
			m, _ := s.record("config", "public")
			if m == nil {
				m = Object{"sandbox": true, "private_mode": false, "floating_microphone_enabled": false, "notification_enabled": true, "guideline_enabled": true}
			}
			send(w, 200, m)
			return nil
		}
		if method == "PUT" {
			m, e := body(r)
			if e != nil {
				return e
			}
			if e = s.put("config", "public", "", m); e != nil {
				return e
			}
			send(w, 200, m)
			return nil
		}
		return problem(405, "METHOD_NOT_ALLOWED", "GET/PUT required")
	case "payments":
		if method != "GET" {
			return problem(405, "METHOD_NOT_ALLOWED", "GET required")
		}
		out, e := s.orders("")
		if e != nil {
			return e
		}
		send(w, 200, Object{"items": out})
		return nil
	case "diagnostics", "history":
		if method != "GET" {
			return problem(405, "METHOD_NOT_ALLOWED", "GET required")
		}
		out, e := s.list(kind, "", r.URL.Query().Get("q"))
		if e != nil {
			return e
		}
		send(w, 200, Object{"items": out})
		return nil
	case "link":
		if len(pieces) != 3 || pieces[2] != "complete" || method != "POST" {
			return problem(404, "NOT_FOUND", "POST /admin/link/{code}/complete required")
		}
		m, e := body(r)
		if e != nil {
			return e
		}
		out, e := s.completeLink(id, str(m, "user_id"))
		if e != nil {
			return e
		}
		send(w, 200, out)
		return nil
	case "users":
		return s.adminUsers(w, r, p, id)
	case "devices":
		return s.adminDevices(w, r, id)
	case "activation-codes":
		return s.adminCodes(w, r, id)
	case "products", "content", "releases":
		return s.adminRecords(w, r, kind, id)
	default:
		return problem(404, "NOT_FOUND", "Unknown admin resource")
	}
}
func (s *Server) adminUsers(w http.ResponseWriter, r *http.Request, p *Principal, id string) error {
	if strings.HasSuffix(r.URL.Path, "/reset-password") {
		return s.adminResetUserPassword(w, r, p, id)
	}
	switch r.Method {
	case "GET":
		if id != "" {
			m, e := s.user(id)
			if e != nil {
				return e
			}
			send(w, 200, m)
			return nil
		}
		rows, e := s.db.Query("SELECT id FROM users ORDER BY created_at DESC LIMIT 500")
		if e != nil {
			return e
		}
		ids := []string{}
		for rows.Next() {
			var x string
			rows.Scan(&x)
			ids = append(ids, x)
		}
		rows.Close()
		out := []Object{}
		q := strings.ToLower(r.URL.Query().Get("q"))
		for _, x := range ids {
			m, e := s.user(x)
			if e == nil && (q == "" || strings.Contains(strings.ToLower(jsonText(m)), q)) {
				out = append(out, m)
			}
		}
		send(w, 200, Object{"items": out})
	case "POST", "PUT":
		m, e := body(r)
		if e != nil {
			return e
		}
		name, role := str(m, "name"), str(m, "role")
		if name == "" {
			return problem(400, "INVALID_USER", "name required")
		}
		if role == "" {
			role = "user"
		}
		if role != "admin" && role != "user" && role != "guest" {
			return problem(400, "INVALID_ROLE", "Invalid role")
		}
		limit := integer(m, "device_limit", 10)
		if limit < 1 || limit > 10 {
			return problem(400, "INVALID_LIMIT", "device_limit must be 1..10")
		}
		password := str(m, "password")
		if password != "" && !validPasswordLength(password) {
			return problem(400, "WEAK_PASSWORD", "Password must contain at least 10 characters")
		}
		var pw string
		if password != "" {
			b, e := bcrypt.GenerateFromPassword([]byte(password), 12)
			if e != nil {
				return e
			}
			pw = string(b)
		}
		if r.Method == "POST" {
			if str(m, "username") != "" && pw == "" {
				return problem(400, "PASSWORD_REQUIRED", "Password required for username")
			}
			id = ident()
			var userName any
			if str(m, "username") != "" {
				userName = str(m, "username")
			}
			_, e = s.db.Exec("INSERT INTO users(id,username,name,role,password_hash,device_limit,created_at) VALUES(?,?,?,?,?,?,?)", id, userName, name, role, pw, limit, now())
		} else {
			if id == "" {
				return problem(400, "ID_REQUIRED", "id required")
			}
			if id == str(p.User, "id") && role != "admin" {
				return problem(409, "SELF_DEMOTION", "Cannot remove own administrator role")
			}
			old, e2 := s.user(id)
			if e2 != nil {
				return e2
			}
			if str(old, "role") == "admin" && role != "admin" {
				var n int
				s.db.QueryRow("SELECT count(*) FROM users WHERE role='admin'").Scan(&n)
				if n <= 1 {
					return problem(409, "LAST_ADMIN", "Cannot demote the last administrator")
				}
			}
			var username any
			usernameValue := str(m, "username")
			if _, specified := m["username"]; !specified {
				usernameValue = str(old, "username")
			}
			if usernameValue != "" {
				username = usernameValue
			}
			tx, beginErr := s.db.Begin()
			if beginErr != nil {
				return beginErr
			}
			defer tx.Rollback()
			_, e = tx.Exec("UPDATE users SET username=?,name=?,role=?,device_limit=? WHERE id=?", username, name, role, limit, id)
			if e == nil && pw != "" {
				e = setPasswordAndRevokeSessions(tx, id, pw)
			}
			if e == nil {
				e = tx.Commit()
			}
		}
		if e != nil {
			return problem(409, "USER_CONFLICT", "Username conflict or invalid user")
		}
		out, e := s.user(id)
		if e != nil {
			return e
		}
		send(w, 200, out)
	case "DELETE":
		if id == "" {
			return problem(400, "ID_REQUIRED", "id required")
		}
		if id == str(p.User, "id") {
			return problem(409, "SELF_DELETE", "Cannot delete own administrator account")
		}
		u, e := s.user(id)
		if e != nil {
			return e
		}
		if str(u, "role") == "admin" {
			var n int
			s.db.QueryRow("SELECT count(*) FROM users WHERE role='admin'").Scan(&n)
			if n <= 1 {
				return problem(409, "LAST_ADMIN", "Cannot delete last administrator")
			}
		}
		if e = s.deleteUser(id); e != nil {
			return e
		}
		send(w, 200, Object{"ok": true})
	default:
		return problem(405, "METHOD_NOT_ALLOWED", "Unsupported user operation")
	}
	return nil
}
func (s *Server) deleteUser(uid string) error {
	tx, e := s.db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	for _, q := range []string{"DELETE FROM sessions WHERE user_id=?", "DELETE FROM settings WHERE user_id=?", "DELETE FROM redemptions WHERE user_id=?", "DELETE FROM payment_events WHERE order_id IN(SELECT id FROM payments WHERE user_id=?)", "DELETE FROM payments WHERE user_id=?", "DELETE FROM entitlements WHERE user_id=?", "DELETE FROM link_codes WHERE user_id=? OR linked_user_id=?", "DELETE FROM devices WHERE user_id=?", "DELETE FROM records WHERE owner=?", "DELETE FROM users WHERE id=?"} {
		args := []any{uid}
		if strings.Contains(q, " OR ") {
			args = append(args, uid)
		}
		if _, e = tx.Exec(q, args...); e != nil {
			return e
		}
	}
	return tx.Commit()
}
func (s *Server) adminDevices(w http.ResponseWriter, r *http.Request, id string) error {
	switch r.Method {
	case "GET":
		out, e := s.deviceList("")
		if e != nil {
			return e
		}
		send(w, 200, Object{"items": out})
	case "DELETE":
		if e := s.deleteDevice(id, "", true); e != nil {
			return e
		}
		send(w, 200, Object{"ok": true})
	case "POST", "PUT":
		m, e := body(r)
		if e != nil {
			return e
		}
		if id == "" {
			id = str(m, "id")
		}
		if id == "" {
			id = ident()
		}
		uid := str(m, "user_id")
		if uid == "" || str(m, "name") == "" {
			return problem(400, "INVALID_DEVICE", "user_id and name required")
		}
		tx, e := s.db.Begin()
		if e != nil {
			return e
		}
		defer tx.Rollback()
		var limit, n int
		if e = tx.QueryRow("SELECT device_limit FROM users WHERE id=?", uid).Scan(&limit); e != nil {
			return e
		}
		tx.QueryRow("SELECT count(*) FROM devices WHERE user_id=? AND id<>?", uid, id).Scan(&n)
		if n >= limit {
			return problem(409, "DEVICE_LIMIT", "Device limit reached")
		}
		var old string
		e = tx.QueryRow("SELECT user_id FROM devices WHERE id=?", id).Scan(&old)
		if r.Method == "POST" && e == nil {
			return problem(409, "DEVICE_CONFLICT", "Device already exists")
		}
		if r.Method == "PUT" && e != nil {
			return e
		}
		if e != nil && e != sql.ErrNoRows {
			return e
		}
		if e == sql.ErrNoRows {
			if limitErr := s.checkFleetDeviceLimit(tx); limitErr != nil {
				return limitErr
			}
		}
		_, e = tx.Exec("INSERT INTO devices VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id,name=excluded.name", id, uid, str(m, "name"), now())
		if e != nil {
			return e
		}
		if old != "" && old != uid {
			tx.Exec("UPDATE sessions SET revoked=1 WHERE device_id=?", id)
		}
		if e = tx.Commit(); e != nil {
			return e
		}
		m["id"] = id
		send(w, 200, m)
	default:
		return problem(405, "METHOD_NOT_ALLOWED", "Unsupported device operation")
	}
	return nil
}
func (s *Server) adminCodes(w http.ResponseWriter, r *http.Request, id string) error {
	switch r.Method {
	case "GET":
		rows, e := s.db.Query("SELECT code,product_id,remaining,expires_at,created_at FROM activation_codes ORDER BY created_at DESC LIMIT 500")
		if e != nil {
			return e
		}
		defer rows.Close()
		out := []Object{}
		for rows.Next() {
			var code, p, c string
			var expiry sql.NullString
			var uses int
			rows.Scan(&code, &p, &uses, &expiry, &c)
			out = append(out, Object{"id": code, "code": code, "product_id": p, "uses": uses, "expires_at": expiry.String, "created_at": c})
		}
		send(w, 200, Object{"items": out})
	case "POST":
		m, e := body(r)
		if e != nil {
			return e
		}
		if _, e = s.record("products", str(m, "product_id")); e != nil {
			return e
		}
		count, uses := integer(m, "count", 1), integer(m, "uses", 1)
		if count < 1 || count > 100 || uses < 1 || uses > 100 {
			return problem(400, "INVALID_COUNT", "count/uses must be 1..100")
		}
		expiry, e := normalizeActivationExpiry(str(m, "expires_at"))
		if e != nil {
			return e
		}
		tx, e := s.db.Begin()
		if e != nil {
			return e
		}
		defer tx.Rollback()
		out := []Object{}
		for i := 0; i < count; i++ {
			code := strings.ToUpper(ident()[:20])
			if _, e = tx.Exec("INSERT INTO activation_codes VALUES(?,?,?,?,?)", code, str(m, "product_id"), uses, expiry, now()); e != nil {
				return e
			}
			out = append(out, Object{"id": code, "code": code, "product_id": str(m, "product_id"), "uses": uses, "expires_at": expiry})
		}
		if e = tx.Commit(); e != nil {
			return e
		}
		send(w, 201, Object{"items": out})
	case "PUT":
		m, e := body(r)
		if e != nil {
			return e
		}
		uses := integer(m, "uses", 0)
		if uses < 0 || uses > 100 {
			return problem(400, "INVALID_COUNT", "uses must be 0..100")
		}
		expiry, e := normalizeActivationExpiry(str(m, "expires_at"))
		if e != nil {
			return e
		}
		res, e := s.db.Exec("UPDATE activation_codes SET remaining=?,expires_at=? WHERE code=?", uses, expiry, id)
		if e != nil {
			return e
		}
		n, _ := res.RowsAffected()
		if n == 0 {
			return sql.ErrNoRows
		}
		send(w, 200, Object{"id": id, "uses": uses})
	case "DELETE":
		_, e := s.db.Exec("UPDATE activation_codes SET remaining=0 WHERE code=?", id)
		if e != nil {
			return e
		}
		send(w, 200, Object{"ok": true, "revoked": true})
	default:
		return problem(405, "METHOD_NOT_ALLOWED", "Unsupported activation operation")
	}
	return nil
}
func (s *Server) adminRecords(w http.ResponseWriter, r *http.Request, kind, id string) error {
	switch r.Method {
	case "GET":
		if id != "" {
			m, e := s.record(kind, id)
			if e != nil {
				return e
			}
			send(w, 200, m)
		} else {
			out, e := s.list(kind, "", r.URL.Query().Get("q"))
			if e != nil {
				return e
			}
			send(w, 200, Object{"items": out})
		}
	case "POST", "PUT":
		m, e := body(r)
		if e != nil {
			return e
		}
		if r.Method == "POST" {
			id = str(m, "id")
			if id == "" {
				id = ident()
			}
			if _, e = s.record(kind, id); e == nil {
				return problem(409, "RECORD_CONFLICT", "ID already exists")
			}
		} else {
			if id == "" {
				return problem(400, "ID_REQUIRED", "id required")
			}
			if _, e = s.record(kind, id); e != nil {
				return e
			}
		}
		switch kind {
		case "products":
			if str(m, "name") == "" || str(m, "currency") == "" || integer(m, "price", -1) < 0 || integer(m, "duration_days", 0) < 1 {
				return problem(400, "INVALID_PRODUCT", "name/currency/price/duration_days required")
			}
		case "content":
			if str(m, "title") == "" || str(m, "kind") == "" {
				return problem(400, "INVALID_CONTENT", "title and kind required")
			}
		case "releases":
			applicationID := str(m, "application_id")
			published, publishedSet := m["published"].(bool)
			sha256 := str(m, "sha256")
			_, hashErr := hex.DecodeString(sha256)
			minSDK := integer(m, "min_sdk", 0)
			if integer(m, "version_code", 0) < 1 || str(m, "version_name") == "" || len(str(m, "version_name")) > 80 ||
				len(sha256) != 64 || hashErr != nil || !validReleaseAPKURL(str(m, "url")) ||
				(applicationID != "vn.tn.assistant" && applicationID != "vn.tn.assistant.preview") ||
				minSDK < 21 || minSDK > 100 || !publishedSet {
				return problem(400, "INVALID_RELEASE", "application_id/version_code/version_name/min_sdk/url/sha256/published required")
			}
			m["published"] = published
			m["sha256"] = strings.ToLower(sha256)
		}
		m["created_at"] = now()
		if e = s.put(kind, id, "", m); e != nil {
			return e
		}
		send(w, 200, m)
	case "DELETE":
		if _, e := s.db.Exec("DELETE FROM records WHERE kind=? AND id=?", kind, id); e != nil {
			return e
		}
		send(w, 200, Object{"ok": true})
	default:
		return problem(405, "METHOD_NOT_ALLOWED", "Unsupported record operation")
	}
	return nil
}

func validReleaseAPKURL(value string) bool {
	if value == "" || strings.TrimSpace(value) != value || strings.ContainsAny(value, "\\?#\r\n\t ") {
		return false
	}
	u, err := url.Parse(value)
	if err != nil || u.User != nil || u.Opaque != "" || u.RawPath != "" || u.ForceQuery || u.Fragment != "" || u.RawQuery != "" {
		return false
	}
	if u.Scheme == "" {
		if u.Host != "" || !strings.HasPrefix(value, "/downloads/") {
			return false
		}
	} else if !strings.EqualFold(u.Scheme, "https") ||
		!(strings.EqualFold(u.Host, "assistant.tnfastvn.com") || strings.EqualFold(u.Host, "assistant.tnfastvn.com:443")) {
		return false
	}
	filePath := u.EscapedPath()
	if filePath != u.Path || !strings.HasPrefix(filePath, "/downloads/") || strings.Count(filePath, "/") != 2 {
		return false
	}
	name := strings.TrimPrefix(filePath, "/downloads/")
	if len(name) < len("a.apk") || !strings.HasSuffix(name, ".apk") || name[0] == '.' || strings.Contains(name, "..") {
		return false
	}
	for _, ch := range name {
		if !((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-') {
			return false
		}
	}
	return true
}
