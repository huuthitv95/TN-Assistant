package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html/template"
	"io"
	"log"
	"math/big"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// User OAuth v4; upstream credentials remain in memory. No third-party app identity is assumed
// or entitlement is reused. A browser callback alone cannot issue a TN session.
type zaloAttempt struct {
	Device, Name, Owner, Verifier, ProofHash, Binding, Code string
	AuthorizationURL, UserCode                              string
	SourceSession, SourceUser                               string
	Created                                                 time.Time
	Busy, Denied                                            bool
}

var zaloTokenEndpoint = "https://oauth.zaloapp.com/v4/access_token"
var zaloProfileEndpoint = "https://graph.zalo.me/v2.0/me?fields=id,name"

func (s *Server) zaloConfig() (Provider, error) {
	p, e := s.requireProvider("zalo")
	if e != nil {
		return p, e
	}
	redirect, e := url.Parse(str(p.Options, "redirect_uri"))
	if e != nil || redirect.Scheme != "https" || redirect.Host == "" || redirect.User != nil || redirect.Fragment != "" || redirect.RawQuery != "" || redirect.Path != "/v1/auth/zalo/callback" {
		return p, problem(503, "PROVIDER_CONFIG_INVALID", "Configure the registered HTTPS TN callback at /v1/auth/zalo/callback")
	}
	app := str(p.Options, "app_id")
	for _, c := range app {
		if c < '0' || c > '9' {
			return p, problem(503, "PROVIDER_CONFIG_INVALID", "Zalo application ID must be numeric")
		}
	}
	return p, nil
}
func zaloBinding(p Provider) string {
	return hash(str(p.Options, "app_id") + "\n" + str(p.Options, "redirect_uri") + "\n" + os.Getenv(p.SecretEnv))
}
func oauthRandom() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func oauthVerifier() string {
	for {
		value := oauthRandom()
		if strings.ContainsAny(value, "ABCDEFGHIJKLMNOPQRSTUVWXYZ") && strings.ContainsAny(value, "abcdefghijklmnopqrstuvwxyz") && strings.ContainsAny(value, "0123456789") {
			return value
		}
	}
}

func oauthPairCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(900000))
	if err != nil {
		panic(err)
	}
	return fmt.Sprintf("%06d", n.Int64()+100000)
}

var zaloLoginPage = template.Must(template.New("zalo-login").Parse(`<!doctype html>
<html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>{{.Title}}</title>
<style>body{margin:0;background:#06132b;color:#eef6ff;font:16px system-ui,sans-serif;display:grid;min-height:100vh;place-items:center}main{box-sizing:border-box;width:min(92vw,560px);padding:32px;border-radius:20px;background:#092b61;text-align:center}h1{font-size:26px}p{line-height:1.6}.code{font-size:38px;font-weight:700;letter-spacing:.16em;color:#38e5ec;margin:24px 0}a{display:block;margin:24px auto 0;padding:16px;border-radius:12px;background:#26dce9;color:#03224a;text-decoration:none;font-weight:700}</style></head>
<body><main><h1>{{.Title}}</h1><p>{{.Message}}</p>{{if .Code}}<div class="code">{{.Code}}</div>{{end}}{{if .AuthorizeURL}}<a href="{{.AuthorizeURL}}" rel="noreferrer">Liên kết tài khoản Zalo</a>{{end}}</main></body></html>`))

func zaloHtml(w http.ResponseWriter, status int, title, message, code, authorizeURL string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action https://oauth.zaloapp.com; base-uri 'none'; frame-ancestors 'none'")
	w.WriteHeader(status)
	_ = zaloLoginPage.Execute(w, Object{"Title": title, "Message": message, "Code": code, "AuthorizeURL": authorizeURL})
}

func (s *Server) zaloAuth(w http.ResponseWriter, r *http.Request) error {
	p, e := s.zaloConfig()
	if e != nil {
		return e
	}
	if r.URL.Path == "/v1/auth/zalo/start" {
		client, _, configErr := zaloHTTPClient()
		if configErr != nil {
			return configErr
		}
		client.CloseIdleConnections()
		if strings.TrimSpace(os.Getenv("TN_ZALO_EGRESS_MODE")) == "gateway" && (!zaloGatewayURLAllowed(zaloTokenEndpoint, "oauth.zaloapp.com") || !zaloGatewayURLAllowed(zaloProfileEndpoint, "graph.zalo.me")) {
			return problem(503, "ZALO_EGRESS_CONFIG_INVALID", "Zalo gateway destinations are invalid")
		}
	}
	switch r.URL.Path {
	case "/v1/auth/zalo/start":
		if r.Method != "POST" && r.Method != "GET" {
			return problem(405, "METHOD_NOT_ALLOWED", "POST required")
		}
		m := Object{}
		if r.Method == "POST" {
			m, e = body(r)
			if e != nil {
				return e
			}
		} else {
			m["device_id"] = r.URL.Query().Get("device_id")
			m["device_name"] = r.URL.Query().Get("device_name")
		}
		did, name := str(m, "device_id"), str(m, "device_name")
		if len(did) < 8 || len(did) > 128 || len(name) < 1 || len(name) > 256 {
			return problem(400, "INVALID_DEVICE", "device_id and device_name required")
		}
		owner, sourceSession, sourceUser := "", "", ""
		var existing string
		err := s.db.QueryRow("SELECT user_id FROM devices WHERE id=?", did).Scan(&existing)
		if err != nil && err != sql.ErrNoRows {
			return err
		}
		if header := r.Header.Get("Authorization"); header != "" {
			if !strings.HasPrefix(header, "Bearer ") || strings.TrimPrefix(header, "Bearer ") == "" {
				return problem(401, "UNAUTHORIZED", "Bearer access token required")
			}
			principal, err := s.authenticate(strings.TrimPrefix(header, "Bearer "))
			if err != nil {
				return problem(401, "UNAUTHORIZED", "Valid existing session required")
			}
			if existing != "" && existing != str(principal.User, "id") {
				return problem(403, "DEVICE_OWNERSHIP", "Device belongs to a different account")
			}
			// Pin the device's current owner, including an unregistered device.
			// A session on another device cannot make the new device appear to
			// have already existed or merge unrelated account data into Zalo.
			owner = existing
			sourceSession, sourceUser = principal.SessionID, str(principal.User, "id")
		} else if existing != "" {
			return problem(409, "DEVICE_ALREADY_REGISTERED", "Authenticate the existing device session before linking Zalo")
		}
		state, proof, verifier := oauthRandom(), oauthRandom(), oauthVerifier()
		digest := sha256.Sum256([]byte(verifier))
		query := url.Values{"app_id": {str(p.Options, "app_id")}, "redirect_uri": {str(p.Options, "redirect_uri")}, "code_challenge": {base64.RawURLEncoding.EncodeToString(digest[:])}, "state": {state}}
		authorizeURL := "https://oauth.zaloapp.com/v4/permission?" + query.Encode()
		callback, _ := url.Parse(str(p.Options, "redirect_uri"))
		qr := url.URL{Scheme: "https", Host: callback.Host, Path: "/v1/auth/zalo/continue", RawQuery: url.Values{"state": {state}}.Encode()}
		attempt := &zaloAttempt{Device: did, Name: name, Owner: owner, SourceSession: sourceSession, SourceUser: sourceUser, Verifier: verifier, ProofHash: hash(proof), Binding: zaloBinding(p), AuthorizationURL: authorizeURL, UserCode: oauthPairCode(), Created: time.Now()}
		s.zaloMu.Lock()
		if s.zaloPending == nil {
			s.zaloPending = map[string]*zaloAttempt{}
		}
		for key, value := range s.zaloPending {
			if time.Since(value.Created) > 20*time.Minute {
				delete(s.zaloPending, key)
			}
		}
		if len(s.zaloPending) >= 1024 {
			s.zaloMu.Unlock()
			return problem(429, "OAUTH_LIMIT", "Too many pending sign-ins")
		}
		s.zaloPending[state] = attempt
		s.zaloMu.Unlock()
		send(w, 200, Object{"url": authorizeURL, "qr_payload": qr.String(), "user_code": attempt.UserCode, "state": state, "client_proof": proof, "callback_url": str(p.Options, "redirect_uri"), "expires_in": 600})
		return nil
	case "/v1/auth/zalo/status":
		if r.Method != "POST" {
			return problem(http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "POST required")
		}
		m, err := body(r)
		if err != nil {
			return err
		}
		state, proof, device := str(m, "state"), str(m, "client_proof"), str(m, "device_id")
		s.zaloMu.Lock()
		defer s.zaloMu.Unlock()
		a := s.zaloPending[state]
		if a == nil || state == "" || a.Busy || a.Binding != zaloBinding(p) || a.Device != device || subtle.ConstantTimeCompare([]byte(a.ProofHash), []byte(hash(proof))) != 1 {
			return problem(http.StatusConflict, "OAUTH_STATE_INVALID", "Sign-in request expired or changed")
		}
		status := "pending"
		switch {
		case time.Since(a.Created) >= 10*time.Minute:
			status = "expired"
		case a.Denied:
			status = "denied"
		case a.Code != "":
			status = "authorized"
		}
		send(w, http.StatusOK, Object{"status": status})
		return nil
	case "/v1/auth/zalo/continue":
		if r.Method != http.MethodGet {
			return problem(http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "GET required")
		}
		states := r.URL.Query()["state"]
		if len(states) != 1 || states[0] == "" {
			zaloHtml(w, http.StatusBadRequest, "Phiên đăng nhập không hợp lệ", "Quay lại TN Assistant để tạo mã QR mới.", "", "")
			return nil
		}
		state := states[0]
		s.zaloMu.Lock()
		a := s.zaloPending[state]
		if a == nil || a.Binding != zaloBinding(p) || time.Since(a.Created) >= 10*time.Minute {
			s.zaloMu.Unlock()
			zaloHtml(w, http.StatusGone, "Phiên đăng nhập hết hạn", "Quay lại TN Assistant và tạo mã QR mới.", "", "")
			return nil
		}
		snapshot := *a
		s.zaloMu.Unlock()
		if snapshot.Denied {
			zaloHtml(w, http.StatusOK, "Đăng nhập TN Assistant", "Yêu cầu đăng nhập Zalo đã bị từ chối. Hãy quay lại thiết bị để thử lại.", "", "")
		} else if snapshot.Code != "" {
			zaloHtml(w, http.StatusOK, "Đăng nhập TN Assistant", "Đã gửi xác nhận. Quay lại thiết bị để hoàn tất đăng nhập.", snapshot.UserCode, "")
		} else {
			zaloHtml(w, http.StatusOK, "Đăng nhập TN Assistant", "Dùng Zalo quét mã, xác nhận mã này khớp với mã trên thiết bị, rồi liên kết tài khoản.", snapshot.UserCode, snapshot.AuthorizationURL)
		}
		return nil
	case "/v1/auth/zalo/callback":
		if r.Method != "GET" {
			return problem(405, "METHOD_NOT_ALLOWED", "GET required")
		}
		state, code := r.URL.Query().Get("state"), r.URL.Query().Get("code")
		if state == "" {
			return problem(400, "OAUTH_STATE_REQUIRED", "Sign-in state required")
		}
		s.zaloMu.Lock()
		defer s.zaloMu.Unlock()
		a := s.zaloPending[state]
		if a == nil || a.Busy || time.Since(a.Created) > 10*time.Minute || a.Binding != zaloBinding(p) {
			return problem(409, "OAUTH_STATE_INVALID", "Sign-in request expired or already used")
		}
		if failure := r.URL.Query().Get("error"); failure != "" || r.URL.Query().Get("error_reason") != "" {
			a.Denied = true
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("Content-Security-Policy", "default-src 'none'")
			io.WriteString(w, "<!doctype html><meta charset=utf-8><title>TN Assistant</title><p>Đăng nhập Zalo chưa được chấp nhận. Quay lại TN Assistant để thử lại.</p>")
			return nil
		}
		if len(code) < 1 || len(code) > 4096 {
			return problem(400, "OAUTH_CODE_REQUIRED", "Authorization code required")
		}
		if a.Code != "" && a.Code != code {
			return problem(409, "OAUTH_CODE_CONFLICT", "Callback already received")
		}
		a.Code = code
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'none'")
		io.WriteString(w, "<!doctype html><meta charset=utf-8><title>TN Assistant</title><p>Đã nhận xác nhận Zalo. Quay lại TN Assistant để hoàn tất đăng nhập.</p>")
		return nil
	case "/v1/auth/zalo/complete":
		if r.Method != "POST" {
			return problem(405, "METHOD_NOT_ALLOWED", "POST required")
		}
		m, e := body(r)
		if e != nil {
			return e
		}
		state := str(m, "state")
		proof := str(m, "client_proof")
		s.zaloMu.Lock()
		a := s.zaloPending[state]
		if a == nil || a.Busy || time.Since(a.Created) > 10*time.Minute || a.Binding != zaloBinding(p) || a.ProofHash != hash(proof) || str(m, "device_id") != a.Device {
			s.zaloMu.Unlock()
			return problem(409, "OAUTH_STATE_INVALID", "Sign-in request expired, changed or already used")
		}
		code := str(m, "code")
		if a.Denied {
			s.zaloMu.Unlock()
			return problem(http.StatusUnauthorized, "ZALO_AUTH_REJECTED", "Đăng nhập Zalo chưa được chấp nhận")
		}
		if code == "" {
			code = a.Code
		}
		if len(code) < 1 || len(code) > 4096 {
			s.zaloMu.Unlock()
			return problem(400, "OAUTH_CODE_REQUIRED", "Authorization code required")
		}
		if a.Code != "" && code != a.Code {
			s.zaloMu.Unlock()
			return problem(409, "OAUTH_CODE_CONFLICT", "Callback does not match this sign-in")
		}
		if a.SourceSession != "" {
			var active int
			if err := s.db.QueryRow("SELECT count(*) FROM sessions WHERE id=? AND user_id=? AND revoked=0 AND access_expires>?", a.SourceSession, a.SourceUser, time.Now().Unix()).Scan(&active); err != nil || active != 1 {
				delete(s.zaloPending, state)
				s.zaloMu.Unlock()
				return problem(409, "OAUTH_SOURCE_SESSION_CHANGED", "The initiating session expired or signed out; start sign-in again")
			}
		}
		a.Busy = true
		delete(s.zaloPending, state)
		snapshot := *a
		s.zaloMu.Unlock()
		identity, e := zaloIdentity(r.Context(), p, code, snapshot.Verifier)
		if e != nil {
			return e
		}
		tokens, e := s.zaloSession(snapshot, p, identity)
		if e != nil {
			return e
		}
		send(w, 200, s.hydrateTokens(tokens))
		return nil
	default:
		return problem(404, "NOT_FOUND", "Unknown Zalo route")
	}
}
func zaloIdentity(ctx context.Context, p Provider, code, verifier string) (Object, error) {
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	client, egressMode, e := zaloHTTPClient()
	if e != nil {
		return nil, e
	}
	defer client.CloseIdleConnections()
	if egressMode == "gateway" && (!zaloGatewayURLAllowed(zaloTokenEndpoint, "oauth.zaloapp.com") || !zaloGatewayURLAllowed(zaloProfileEndpoint, "graph.zalo.me")) {
		return nil, problem(503, "ZALO_EGRESS_CONFIG_INVALID", "Zalo gateway destinations are invalid")
	}
	form := url.Values{"code": {code}, "app_id": {str(p.Options, "app_id")}, "grant_type": {"authorization_code"}, "code_verifier": {verifier}}
	req, e := http.NewRequestWithContext(ctx, "POST", zaloTokenEndpoint, strings.NewReader(form.Encode()))
	if e != nil {
		return nil, e
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("secret_key", os.Getenv(p.SecretEnv))
	read := func(req *http.Request) (Object, error) {
		resp, e := client.Do(req)
		if e != nil {
			if egressMode == "gateway" {
				return nil, problem(503, "ZALO_EGRESS_UNAVAILABLE", "Zalo gateway or sign-in service is unavailable")
			}
			return nil, problem(502, "ZALO_UNAVAILABLE", "Zalo sign-in service unavailable")
		}
		defer resp.Body.Close()
		var result Object
		payload, readErr := io.ReadAll(io.LimitReader(resp.Body, (1<<20)+1))
		if resp.StatusCode != 200 || readErr != nil || len(payload) > 1<<20 || json.Unmarshal(payload, &result) != nil || result == nil {
			return nil, problem(502, "ZALO_BAD_RESPONSE", "Zalo sign-in response invalid")
		}
		return result, nil
	}
	token, e := read(req)
	if e != nil {
		return nil, e
	}
	access := str(token, "access_token")
	if access == "" {
		return nil, problem(401, "ZALO_AUTH_REJECTED", "Zalo authorization was rejected")
	}
	req, e = http.NewRequestWithContext(ctx, "GET", zaloProfileEndpoint, nil)
	if e != nil {
		return nil, e
	}
	req.Header.Set("access_token", access)
	// Zalo verifies that this user token belongs to our registered application.
	// Keep the app secret and derived proof server-side, including on errors.
	proof := hmac.New(sha256.New, []byte(os.Getenv(p.SecretEnv)))
	proof.Write([]byte(access))
	req.Header.Set("appsecret_proof", hex.EncodeToString(proof.Sum(nil)))
	profile, e := read(req)
	if e != nil {
		return nil, e
	}
	id := str(profile, "id")
	profileError, verified := profile["error"].(float64)
	if !verified || profileError != 0 || id == "" || len(id) > 256 || len(str(profile, "name")) > 512 {
		// Numeric/shape diagnostics only: never log the token, proof or profile values.
		log.Printf("TN_ZALO_PROFILE_REJECTED numeric_error=%t error_code=%.0f has_id=%t", verified, profileError, id != "")
		if verified && profileError == -501 {
			return nil, problem(503, "ZALO_REGION_RESTRICTED", "Zalo profile verification requires a server IP in Vietnam")
		}
		return nil, problem(401, "ZALO_IDENTITY_INVALID", "Zalo identity could not be verified")
	}
	return profile, nil
}
func (s *Server) zaloSession(a zaloAttempt, p Provider, profile Object) (Object, error) {
	tx, e := s.db.Begin()
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	if a.SourceSession != "" {
		var active int
		if e = tx.QueryRow("SELECT count(*) FROM sessions WHERE id=? AND user_id=? AND revoked=0 AND access_expires>?", a.SourceSession, a.SourceUser, time.Now().Unix()).Scan(&active); e != nil {
			return nil, e
		}
		if active != 1 {
			return nil, problem(409, "OAUTH_SOURCE_SESSION_CHANGED", "The initiating session expired or signed out; start sign-in again")
		}
	}
	var current string
	e = tx.QueryRow("SELECT user_id FROM devices WHERE id=?", a.Device).Scan(&current)
	if e != nil && e != sql.ErrNoRows {
		return nil, e
	}
	if current != a.Owner {
		return nil, problem(409, "DEVICE_OWNERSHIP_CHANGED", "Device account changed during sign-in")
	}
	key := "zalo:" + hash(str(p.Options, "app_id")+":"+str(profile, "id"))
	var uid string
	e = tx.QueryRow("SELECT owner FROM records WHERE kind='identities' AND id=?", key).Scan(&uid)
	if e != nil && e != sql.ErrNoRows {
		return nil, e
	}
	if uid == "" {
		uid = ident()
		if a.Owner != "" {
			var role string
			if e = tx.QueryRow("SELECT role FROM users WHERE id=?", a.Owner).Scan(&role); e != nil {
				return nil, e
			}
			if role == "guest" {
				uid = a.Owner
			}
		}
		name := str(profile, "name")
		if name == "" {
			name = "Zalo"
		}
		if uid == a.Owner {
			_, e = tx.Exec("UPDATE users SET name=?,role='user' WHERE id=? AND role='guest'", name, uid)
		} else {
			_, e = tx.Exec("INSERT INTO users(id,name,role,created_at) VALUES(?,?,'user',?)", uid, name, now())
		}
		if e != nil {
			return nil, e
		}
		if _, e = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('identities',?,?,?,?)", key, uid, jsonText(Object{"provider": "zalo", "app_id": str(p.Options, "app_id")}), now()); e != nil {
			return nil, e
		}
	}
	var count, limit int
	if e = tx.QueryRow("SELECT device_limit FROM users WHERE id=?", uid).Scan(&limit); e != nil {
		return nil, e
	}
	if e = tx.QueryRow("SELECT count(*) FROM devices WHERE user_id=? AND id<>?", uid, a.Device).Scan(&count); e != nil {
		return nil, e
	}
	if count >= limit {
		return nil, problem(409, "DEVICE_LIMIT", "Account device limit reached")
	}
	if current != "" {
		if _, e = tx.Exec("UPDATE sessions SET revoked=1 WHERE device_id=?", a.Device); e != nil {
			return nil, e
		}
		_, e = tx.Exec("UPDATE devices SET user_id=?,name=? WHERE id=?", uid, a.Name, a.Device)
	} else {
		_, e = tx.Exec("INSERT INTO devices(id,user_id,name,created_at) VALUES(?,?,?,?)", a.Device, uid, a.Name, now())
	}
	if e != nil {
		return nil, e
	}
	result, e := s.tokens(tx, uid, a.Device, "")
	if e != nil {
		return nil, e
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	return result, nil
}
