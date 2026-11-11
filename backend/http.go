package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"golang.org/x/crypto/bcrypt"
	"html/template"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type apiError struct {
	Status        int
	Code, Message string
}

func (e *apiError) Error() string                { return e.Message }
func problem(status int, code, msg string) error { return &apiError{status, code, msg} }
func send(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, e error) {
	var a *apiError
	if errors.As(e, &a) {
		send(w, a.Status, Object{"error": Object{"code": a.Code, "message": a.Message}})
		return
	}
	if errors.Is(e, sql.ErrNoRows) {
		send(w, 404, Object{"error": Object{"code": "NOT_FOUND", "message": "Không tìm thấy dữ liệu"}})
		return
	}
	send(w, 500, Object{"error": Object{"code": "INTERNAL_ERROR", "message": "Không thể hoàn thành yêu cầu"}})
}
func body(r *http.Request) (Object, error) {
	defer r.Body.Close()
	var m Object
	d := json.NewDecoder(io.LimitReader(r.Body, 2<<20))
	if e := d.Decode(&m); e != nil || m == nil {
		return nil, problem(400, "INVALID_JSON", "JSON object required")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return nil, problem(400, "INVALID_JSON", "Exactly one JSON object required")
	}
	return m, nil
}
func (s *Server) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		defer func() {
			if recover() != nil {
				fail(w, problem(500, "INTERNAL_ERROR", "Không thể hoàn thành yêu cầu"))
			}
		}()
		if r.URL.Path == "/v1/ready" {
			if r.Method != "GET" {
				fail(w, problem(405, "METHOD_NOT_ALLOWED", "GET required"))
				return
			}
			ready := s.runtimeReadiness(r.Context())
			status := 503
			if ready["ready"] == true {
				status = 200
			}
			send(w, status, ready)
			return
		}
		if r.URL.Path == "/v1/health" && r.Method == "GET" {
			p, _ := s.providers()
			states := Object{}
			for _, k := range []string{"asr", "nlu", "tts", "zalo", "driving", "action_log", "welcome"} {
				states[k] = Object{"configured": p[k].configured(k)}
			}
			if s.independentRuntime() {
				if deepgramSelected() {
					states["asr"] = Object{"configured": strings.TrimSpace(os.Getenv("TN_DEEPGRAM_API_KEY")) != "", "provider": "deepgram", "enabled": os.Getenv("TN_DEEPGRAM_ENABLED") == "true"}
				}
				if azureTTSSelected() {
					_, _, err := azureTTSReady()
					states["tts"] = Object{"configured": err == nil, "provider": "azure"}
				} else if originalTTSSelected() {
					p, err := s.originalTTSProvider()
					states["tts"] = Object{"configured": err == nil, "provider": "original_tts", "speaker_id": str(p.Options, "speaker_id"), "external_dependency": true}
				} else if strings.EqualFold(os.Getenv("TN_TTS_PROVIDER"), "disabled") {
					states["tts"] = Object{"configured": false, "provider": "disabled"}
				}
			}
			send(w, 200, Object{"status": "ok", "sandbox": true, "providers": states})
			return
		}
		if strings.HasPrefix(r.URL.Path, "/v1/auth/zalo/") {
			if e := s.zaloAuth(w, r); e != nil {
				fail(w, e)
			}
			return
		}
		if strings.HasPrefix(r.URL.Path, "/v1/auth/") {
			if e := s.auth(w, r); e != nil {
				fail(w, e)
			}
			return
		}
		if r.URL.Path == "/v1/payments/webhook" {
			if e := s.webhook(w, r); e != nil {
				fail(w, e)
			}
			return
		}
		if strings.HasPrefix(r.URL.Path, "/sandbox/checkout/") {
			s.checkout(w, r)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/downloads/") {
			s.download(w, r)
			return
		}
		if r.URL.Path == "/v1/releases/latest" {
			if e := s.latestRelease(w, r); e != nil {
				fail(w, e)
			}
			return
		}
		if strings.HasPrefix(r.URL.Path, "/v1/") {
			token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if token == r.Header.Get("Authorization") || token == "" {
				fail(w, problem(401, "UNAUTHORIZED", "Bearer access token required"))
				return
			}
			p, e := s.authenticate(token)
			if e != nil {
				fail(w, problem(401, "TOKEN_EXPIRED", "Access token expired or revoked"))
				return
			}
			if e = s.api(w, r, p); e != nil {
				fail(w, e)
			}
			return
		}
		if r.Method != "GET" {
			http.NotFound(w, r)
			return
		}
		dir := s.cfg.StaticDir
		if dir == "" {
			dir = "static"
		}
		clean := filepath.Clean(strings.TrimPrefix(r.URL.Path, "/"))
		path := filepath.Join(dir, clean)
		if _, e := os.Stat(path); e != nil || clean == "." {
			path = filepath.Join(dir, "index.html")
		}
		if _, e := os.Stat(path); e != nil {
			send(w, 200, Object{"service": "TN Assistant backend", "dashboard": "Build admin and set TN_STATIC_DIR", "health": "/v1/health"})
			return
		}
		http.ServeFile(w, r, path)
	})
}
func (s *Server) auth(w http.ResponseWriter, r *http.Request) error {
	if r.Method != "POST" {
		return problem(405, "METHOD_NOT_ALLOWED", "POST required")
	}
	if r.URL.Path == "/v1/auth/register" {
		return s.registerTN(w, r)
	}
	m, e := body(r)
	if e != nil {
		return e
	}
	tx, e := s.db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var uid, did, family string
	switch r.URL.Path {
	case "/v1/auth/guest":
		did = str(m, "device_id")
		name := str(m, "device_name")
		if len(did) < 8 || len(did) > 128 || name == "" {
			return problem(400, "INVALID_DEVICE", "device_id and device_name required")
		}
		var existing string
		e = tx.QueryRow("SELECT user_id FROM devices WHERE id=?", did).Scan(&existing)
		if e == nil {
			return problem(409, "DEVICE_ALREADY_REGISTERED", "Use the saved refresh token or ask an administrator to unlink this device")
		}
		if e != sql.ErrNoRows {
			return e
		}
		if e = s.checkFleetDeviceLimit(tx); e != nil {
			return e
		}
		uid = ident()
		if _, e = tx.Exec("INSERT INTO users(id,name,role,created_at) VALUES(?,?,?,?)", uid, name, "guest", now()); e != nil {
			return e
		}
		if _, e = tx.Exec("INSERT INTO devices VALUES(?,?,?,?)", did, uid, name, now()); e != nil {
			return e
		}
	case "/v1/auth/login":
		var pass string
		e = tx.QueryRow("SELECT id,password_hash FROM users WHERE username=?", str(m, "username")).Scan(&uid, &pass)
		if e != nil || bcrypt.CompareHashAndPassword([]byte(pass), []byte(str(m, "password"))) != nil {
			return problem(401, "INVALID_CREDENTIALS", "Invalid username or password")
		}
		if str(m, "device_id") != "" {
			did, e = s.bindLoginDevice(tx, uid, m)
			if e != nil {
				return e
			}
		}
	case "/v1/auth/refresh":
		var id string
		var dev sql.NullString
		var revoked, expiry int64
		e = tx.QueryRow("SELECT id,user_id,device_id,family,revoked,refresh_expires FROM sessions WHERE refresh_hash=?", hash(str(m, "refresh_token"))).Scan(&id, &uid, &dev, &family, &revoked, &expiry)
		if e != nil {
			return problem(401, "INVALID_REFRESH", "Invalid refresh token")
		}
		if revoked != 0 {
			tx.Exec("UPDATE sessions SET revoked=1 WHERE family=?", family)
			tx.Commit()
			return problem(401, "REFRESH_REPLAY", "Refresh token replay detected; session family revoked")
		}
		if expiry <= time.Now().Unix() {
			return problem(401, "REFRESH_EXPIRED", "Refresh token expired")
		}
		did = dev.String
		tx.Exec("UPDATE sessions SET revoked=1 WHERE id=?", id)
	case "/v1/auth/logout":
		_, e = tx.Exec("UPDATE sessions SET revoked=1 WHERE family IN (SELECT family FROM sessions WHERE refresh_hash=?)", hash(str(m, "refresh_token")))
		if e != nil {
			return e
		}
		if e = tx.Commit(); e != nil {
			return e
		}
		send(w, 200, Object{"ok": true})
		return nil
	default:
		return problem(404, "NOT_FOUND", "Unknown auth route")
	}
	out, e := s.tokens(tx, uid, did, family)
	if e != nil {
		return e
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	send(w, 200, s.hydrateTokens(out))
	return nil
}
func (s *Server) api(w http.ResponseWriter, r *http.Request, p *Principal) error {
	if r.URL.Path == "/v1/capabilities" {
		if r.Method != "GET" {
			return problem(405, "METHOD_NOT_ALLOWED", "GET required")
		}
		out, e := s.runtimeCapabilities()
		if e != nil {
			return e
		}
		send(w, 200, out)
		return nil
	}
	if r.URL.Path == "/v1/device/events" || r.URL.Path == "/v1/device/actions/results" {
		return s.deviceIngest(w, r, p)
	}
	if handled, e := s.managedAPI(w, r, p); handled {
		return e
	}
	if handled, e := s.addressBookAPI(w, r, p); handled {
		return e
	}
	uid := str(p.User, "id")
	path := r.URL.Path
	method := r.Method
	admin := str(p.User, "role") == "admin"
	if strings.HasPrefix(path, "/v1/admin/") {
		if !admin {
			return problem(403, "FORBIDDEN", "Administrator role required")
		}
		return s.admin(w, r, p)
	}
	switch {
	case path == "/v1/me" && method == "GET":
		send(w, 200, Object{"user": p.User, "device": p.Device, "entitlements": s.entitlements(uid), "premium_features": s.premiumFeatures(uid), "local_premium": s.localPremium(uid), "account_plan": s.accountPlan(uid), "device_limit": p.User["device_limit"]})
	case path == "/v1/me" && method == "DELETE":
		if admin {
			return problem(409, "ADMIN_SELF_DELETE", "Use another administrator to delete this administrator account")
		}
		if e := s.deleteUser(uid); e != nil {
			return e
		}
		send(w, 200, Object{"ok": true})
	case path == "/v1/car/models" && method == "GET":
		cfg, _ := s.record("config", "public")
		if models, ok := cfg["car_models"].([]any); ok {
			send(w, 200, Object{"items": models})
			return nil
		}
		items, e := s.list("car_models", "", "")
		if e != nil {
			return e
		}
		send(w, 200, Object{"items": items})
	case path == "/v1/devices" && method == "GET":
		items, e := s.deviceList(uid)
		if e != nil {
			return e
		}
		send(w, 200, Object{"items": items})
	case strings.HasPrefix(path, "/v1/devices/") && method == "DELETE":
		id := strings.TrimPrefix(path, "/v1/devices/")
		if e := s.deleteDevice(id, uid, admin); e != nil {
			return e
		}
		send(w, 200, Object{"ok": true})
	case path == "/v1/settings" && (method == "GET" || method == "PUT"):
		defaults := Object{"map_package": "com.google.android.apps.maps", "music_package": "", "video_package": "", "tv_package": "", "radio_package": "", "media_package": "", "conversation_idle_timeout_ms": 10000, "command_end_silence_ms": 1000, "ai_mode": s.defaultAIMode(uid), "wake_word_enabled": false, "wake_word_data_sharing": false, "welcome_enabled": true, "volume": 60, "traffic_enabled": true, "private_mode": false}
		var b string
		if s.db.QueryRow("SELECT data FROM settings WHERE user_id=?", uid).Scan(&b) == nil {
			var saved Object
			json.Unmarshal([]byte(b), &saved)
			for k, v := range saved {
				defaults[k] = v
			}
		}
		// A saved switch cannot keep a Premium-only capability after entitlement expiry.
		if s.premiumFeatures(uid)["wake_word"] != true {
			defaults["wake_word_enabled"] = false
		}
		if _, exists := defaults["traffic_warning_mode"]; !exists {
			if defaults["traffic_enabled"] == true {
				defaults["traffic_warning_mode"] = "sound"
			} else {
				defaults["traffic_warning_mode"] = "none"
			}
		}
		if _, exists := defaults["traffic_overspeed_offset"]; !exists {
			defaults["traffic_overspeed_offset"] = 0
		}
		if _, exists := defaults["traffic_always_on_map"]; !exists {
			defaults["traffic_always_on_map"] = false
		}
		if method == "PUT" {
			m, e := body(r)
			if e != nil {
				return e
			}
			for k, v := range m {
				if k == "wake_word_enabled" && v == true && s.premiumFeatures(uid)["wake_word"] != true {
					return problem(403, "FEATURE_NOT_AVAILABLE", "Premium required for wake-word activation")
				}
				switch k {
				case "map_package", "music_package", "video_package", "tv_package", "radio_package", "media_package":
					if _, ok := v.(string); !ok {
						return problem(400, "INVALID_SETTINGS", "Package fields must be strings")
					}
				case "wake_word_enabled", "wake_word_data_sharing", "welcome_enabled", "traffic_enabled", "traffic_always_on_map", "private_mode":
					if _, ok := v.(bool); !ok {
						return problem(400, "INVALID_SETTINGS", "Enabled fields must be boolean")
					}
				case "traffic_warning_mode":
					if v != "none" && v != "display" && v != "sound" && v != "voice" {
						return problem(400, "INVALID_SETTINGS", "Unknown traffic warning mode")
					}
				case "traffic_overspeed_offset":
					if n, ok := v.(float64); !ok || (n != -5 && n != 0 && n != 5) {
						return problem(400, "INVALID_SETTINGS", "Overspeed offset must be -5, 0 or 5 km/h")
					}
				case "conversation_idle_timeout_ms", "command_end_silence_ms":
					allowed := map[string][]int{"conversation_idle_timeout_ms": {5000, 10000, 15000, 30000, 60000}, "command_end_silence_ms": {500, 1000, 1500, 2000, 3000}}
					valid := false
					for _, n := range allowed[k] {
						if value, ok := v.(float64); ok && value == float64(n) {
							valid = true
						}
					}
					if !valid {
						return problem(400, "INVALID_SETTINGS", "Unsupported assistant timeout")
					}
				case "ai_mode":
					if v != "commands" && v != "qa" && v != "tools" {
						return problem(400, "INVALID_SETTINGS", "Unknown AI mode")
					}
					if v != "commands" && !s.aiPremium(uid) {
						return problem(403, "FEATURE_NOT_AVAILABLE", "Premium required for AI")
					}
					if v == "tools" && s.premiumFeatures(uid)["ai_tools"] != true {
						return problem(403, "FEATURE_NOT_AVAILABLE", "AI tools not included")
					}
				case "agent_voice":
					if voice, ok := v.(string); !ok || (voice != "south" && voice != "north") {
						return problem(400, "INVALID_SETTINGS", "agent_voice must be south or north")
					}
				case "volume":
					n, ok := v.(float64)
					if !ok || n < 0 || n > 100 || n != float64(int(n)) {
						return problem(400, "INVALID_SETTINGS", "volume must be integer 0..100")
					}
				default:
					return problem(400, "INVALID_SETTINGS", "Unknown setting "+k)
				}
				defaults[k] = v
			}
			if mode, ok := m["traffic_warning_mode"]; ok {
				defaults["traffic_enabled"] = mode != "none"
			} else if enabled, ok := m["traffic_enabled"].(bool); ok {
				if !enabled {
					defaults["traffic_warning_mode"] = "none"
				} else if defaults["traffic_warning_mode"] == "none" {
					defaults["traffic_warning_mode"] = "sound"
				}
			}
			tx, err := s.db.Begin()
			if err != nil {
				return err
			}
			defer tx.Rollback()
			if m["private_mode"] == true {
				// Latch privacy for every already accepted in-flight turn, even
				// if the user turns it off again before a provider responds.
				if _, err = tx.Exec("UPDATE records SET data=json_set(data,'$.private_mode',json('true')) WHERE kind='assistant_request' AND owner=? AND json_extract(data,'$.status') IS NULL", uid); err != nil {
					return err
				}
			}
			if _, err = tx.Exec("INSERT INTO settings VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET data=excluded.data", uid, jsonText(defaults)); err != nil {
				return err
			}
			if e = tx.Commit(); e != nil {
				return e
			}
		}
		if !s.aiPremium(uid) {
			defaults["ai_mode"] = "commands"
		}
		// Present TN's default without saving an implicit voice selection on an
		// unrelated update. Existing users retain the configured TTS default
		// until they explicitly select a voice.
		if defaults["agent_voice"] != "south" && defaults["agent_voice"] != "north" {
			defaults["agent_voice"] = "south"
		}
		send(w, 200, defaults)
	case path == "/v1/config" && method == "GET":
		m, _ := s.record("config", "public")
		if m == nil {
			m = Object{"sandbox": true, "device_limit": 10, "private_mode": false, "floating_microphone_enabled": false, "notification_enabled": true, "guideline_enabled": true, "map_packages": []string{"com.google.android.apps.maps", "com.vietmap.s1OBU", "com.navitel"}, "car_models": []any{}}
		}
		send(w, 200, m)
	case path == "/v1/products" && method == "GET":
		out, e := s.list("products", "", "")
		if e != nil {
			return e
		}
		send(w, 200, Object{"items": out})
	case path == "/v1/activation/redeem" && method == "POST":
		m, e := body(r)
		if e != nil {
			return e
		}
		out, e := s.redeem(uid, str(m, "code"))
		if e != nil {
			return e
		}
		send(w, 200, out)
	case path == "/v1/link/start" && method == "POST":
		code := ident()[:12]
		expires := time.Now().Add(10 * time.Minute).UTC().Format(time.RFC3339Nano)
		_, e := s.db.Exec("INSERT INTO link_codes(code,user_id,device_id,status,expires_at) VALUES(?,?,?,?,?)", code, uid, str(p.Device, "id"), "pending", expires)
		if e != nil {
			return e
		}
		send(w, 200, Object{"code": code, "expires_at": expires, "qr_payload": s.cfg.PublicURL + "/link/" + code})
	case strings.HasPrefix(path, "/v1/link/"):
		id := strings.TrimPrefix(path, "/v1/link/")
		if strings.HasSuffix(id, "/complete") && method == "POST" {
			if !admin {
				return problem(403, "FORBIDDEN", "Administrator role required")
			}
			m, e := body(r)
			if e != nil {
				return e
			}
			out, e := s.completeLink(strings.TrimSuffix(id, "/complete"), str(m, "user_id"))
			if e != nil {
				return e
			}
			send(w, 200, out)
			return nil
		}
		if method != "GET" {
			return problem(405, "METHOD_NOT_ALLOWED", "GET required")
		}
		var owner, status, expires, source string
		var src sql.NullString
		e := s.db.QueryRow("SELECT user_id,status,expires_at,source FROM link_codes WHERE code=?", id).Scan(&owner, &status, &expires, &src)
		source = src.String
		if e != nil {
			return e
		}
		if owner != uid && !admin {
			var target sql.NullString
			s.db.QueryRow("SELECT linked_user_id FROM link_codes WHERE code=?", id).Scan(&target)
			if target.String != uid {
				return problem(403, "FORBIDDEN", "Link belongs to another user")
			}
		}
		if expires < now() && status == "pending" {
			status = "expired"
		}
		send(w, 200, Object{"code": id, "status": status, "expires_at": expires, "source": source})
	case path == "/v1/auth/zalo/start" && method == "GET":
		return s.zaloStart(w, r, p)
	case path == "/v1/payments" && (method == "GET" || method == "POST"):
		if method == "POST" {
			m, e := body(r)
			if e != nil {
				return e
			}
			out, e := s.createOrder(uid, str(m, "product_id"))
			if e != nil {
				return e
			}
			send(w, 201, out)
		} else {
			out, e := s.orders(uid)
			if e != nil {
				return e
			}
			send(w, 200, Object{"items": out})
		}
	case strings.HasPrefix(path, "/v1/payments/"):
		id := strings.TrimPrefix(path, "/v1/payments/")
		action := strings.HasSuffix(id, "/sandbox")
		if action {
			id = strings.TrimSuffix(id, "/sandbox")
		}
		out, e := s.order(id)
		if e != nil {
			return e
		}
		if str(out, "user_id") != uid && !admin {
			return problem(403, "FORBIDDEN", "Order belongs to another user")
		}
		if action && method == "POST" {
			m, e := body(r)
			if e != nil {
				return e
			}
			out, e = s.paymentEvent(ident(), id, str(m, "status"))
			if e != nil {
				return e
			}
		} else if method != "GET" {
			return problem(405, "METHOD_NOT_ALLOWED", "Unsupported payment operation")
		}
		send(w, 200, out)
	case path == "/v1/history" && (method == "GET" || method == "POST"):
		if method == "POST" {
			m, e := body(r)
			if e != nil {
				return e
			}
			if str(m, "kind") == "" || str(m, "text") == "" {
				return problem(400, "INVALID_HISTORY", "kind and text required")
			}
			m["created_at"] = now()
			id := ident()
			if e = s.put("history", id, uid, m); e != nil {
				return e
			}
			send(w, 201, m)
		} else {
			out, e := s.list("history", uid, "")
			if e != nil {
				return e
			}
			send(w, 200, Object{"items": out})
		}
	case path == "/v1/content" && method == "GET":
		out, e := s.list("content", "", "")
		if e != nil {
			return e
		}
		published := []Object{}
		for _, m := range out {
			if m["published"] == true {
				published = append(published, m)
			}
		}
		send(w, 200, Object{"items": published})
	case path == "/v1/welcome" && method == "GET":
		if s.independentRuntime() {
			send(w, 200, Object{"text": "Xin chào, TN Assistant sẵn sàng hỗ trợ bạn.", "rules": []any{}})
			return nil
		}
		m, _ := s.record("config", "welcome")
		if m == nil {
			m = Object{"text": "Xin chào, TN Assistant sẵn sàng hỗ trợ bạn.", "rules": []any{}}
		}
		send(w, 200, m)
	case path == "/v1/welcome/rule" && method == "GET":
		return s.welcomeRule(w, r, p)
	case path == "/v1/weather/current" && method == "GET":
		return s.currentWeather(w, r)
	case strings.HasPrefix(path, "/v1/driving/area/") && method == "POST":
		return s.drivingArea(w, r, p, strings.TrimPrefix(path, "/v1/driving/area/"))
	case path == "/v1/driving/log" && method == "POST":
		return s.drivingActionLog(w, r, p)
	case path == "/v1/traffic/config" && method == "GET":
		m, _ := s.record("config", "traffic")
		if m == nil {
			m = Object{"enabled": false, "configured": false}
		}
		send(w, 200, m)
	case path == "/v1/traffic/check" && method == "POST":
		if s.independentRuntime() {
			return problem(503, "TRAFFIC_VIOLATIONS_API_REQUIRED", "Automatic violation lookup is unavailable until an authorized TN API is configured")
		}
		m, e := body(r)
		if e != nil {
			return e
		}
		if str(m, "plate") == "" {
			return problem(400, "INVALID_PLATE", "plate required")
		}
		out, e := s.providerJSON(r.Context(), "traffic", m)
		if e != nil {
			return e
		}
		send(w, 200, out)
	case path == "/v1/diagnostics" && (method == "GET" || method == "POST"):
		if method == "POST" {
			m, e := body(r)
			if e != nil {
				return e
			}
			if str(m, "kind") == "" || str(m, "message") == "" {
				return problem(400, "INVALID_DIAGNOSTIC", "kind and message required")
			}
			m["created_at"] = now()
			m["user_id"] = uid
			if e = s.put("diagnostics", ident(), uid, m); e != nil {
				return e
			}
			send(w, 201, m)
		} else {
			owner := uid
			if admin {
				owner = ""
			}
			out, e := s.list("diagnostics", owner, "")
			if e != nil {
				return e
			}
			send(w, 200, Object{"items": out})
		}
	case strings.HasPrefix(path, "/v1/releases/repair/") && method == "GET":
		release, e := s.repairRelease(strings.TrimPrefix(path, "/v1/releases/repair/"))
		if e != nil {
			return e
		}
		send(w, 200, release)
	case path == "/v1/releases/latest" && method == "GET":
		return s.latestRelease(w, r)
	case path == "/v1/assistant/text" && method == "POST":
		return s.assistant(w, r, p)
	case path == "/v1/assistant/location" && method == "POST":
		return s.assistantLocation(w, r, p)
	case path == "/v1/assistant/call-api" && method == "POST":
		return s.assistantCallAPI(w, r, p)
	case path == "/v1/assistant/traffic-violations" && method == "POST":
		return s.assistantTrafficViolations(w, r, p)
	case path == "/v1/speech/decode" && method == "POST":
		return s.decode(w, r)
	case path == "/v1/tts" && method == "POST":
		return s.tts(w, r, p)
	case strings.HasPrefix(path, "/v1/tts/audio/") && (method == "GET" || method == "HEAD"):
		m, e := s.record("tts_audio", strings.TrimPrefix(path, "/v1/tts/audio/"))
		if e != nil {
			return e
		}
		if str(m, "owner") != uid {
			return problem(403, "FORBIDDEN", "Audio belongs to another user")
		}
		if str(m, "expires_at") < now() {
			return problem(410, "AUDIO_EXPIRED", "Audio link expired")
		}
		data, e := hex.DecodeString(str(m, "data"))
		if e != nil {
			return e
		}
		writeAudioRange(w, r, data, str(m, "mime_type"))
	case path == "/v1/speech/wake-word/detect" && method == "POST":
		return s.remoteWakeWord(w, r)
	case path == "/v1/speech/stream" && method == "GET":
		return s.stream(w, r)
	default:
		return problem(404, "NOT_FOUND", "Unknown API route")
	}
	return nil
}

func (s *Server) latestRelease(w http.ResponseWriter, r *http.Request) error {
	if r.Method != "GET" {
		return problem(405, "METHOD_NOT_ALLOWED", "GET required")
	}
	applicationID := r.URL.Query().Get("application_id")
	if applicationID != "" && applicationID != "vn.tn.assistant" && applicationID != "vn.tn.assistant.preview" {
		return problem(400, "INVALID_APPLICATION_ID", "Unsupported application_id")
	}
	items, err := s.list("releases", "", "")
	if err != nil {
		return err
	}
	var best Object
	for _, item := range items {
		if item["published"] == false {
			continue
		}
		if applicationID == "" {
			// Preserve the old endpoint for pre-channel catalog entries only;
			// never let a legacy client receive a TN/Preview package by accident.
			if str(item, "application_id") != "" {
				continue
			}
		} else if str(item, "application_id") != applicationID {
			continue
		}
		if best == nil || integer(item, "version_code", 0) > integer(best, "version_code", 0) {
			best = item
		}
	}
	if best == nil {
		send(w, 200, Object{"available": false})
	} else {
		send(w, 200, Object{
			"available":      true,
			"application_id": str(best, "application_id"),
			"version_code":   integer(best, "version_code", 0),
			"version_name":   str(best, "version_name"),
			"min_sdk":        integer(best, "min_sdk", 0),
			"url":            str(best, "url"),
			"sha256":         str(best, "sha256"),
			"notes":          str(best, "notes"),
			"published":      true,
		})
	}
	return nil
}
func (s *Server) deviceList(uid string) ([]Object, error) {
	query := "SELECT id,user_id,name,created_at FROM devices"
	args := []any{}
	if uid != "" {
		query += " WHERE user_id=?"
		args = append(args, uid)
	}
	rows, e := s.db.Query(query, args...)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Object{}
	for rows.Next() {
		var id, u, n, c string
		rows.Scan(&id, &u, &n, &c)
		out = append(out, Object{"id": id, "user_id": u, "name": n, "created_at": c, "capabilities": Object{}})
	}
	return out, rows.Err()
}
func (s *Server) deleteDevice(id, uid string, admin bool) error {
	tx, e := s.db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var owner string
	if e = tx.QueryRow("SELECT user_id FROM devices WHERE id=?", id).Scan(&owner); e != nil {
		return e
	}
	if owner != uid && !admin {
		return problem(403, "FORBIDDEN", "Device belongs to another user")
	}
	tx.Exec("DELETE FROM sessions WHERE device_id=?", id)
	tx.Exec("UPDATE link_codes SET device_id=NULL WHERE device_id=?", id)
	if _, e = tx.Exec("DELETE FROM devices WHERE id=?", id); e != nil {
		return e
	}
	return tx.Commit()
}
func (s *Server) completeLink(code, uid string) (Object, error) {
	tx, e := s.db.Begin()
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	var status, expires, oldUID string
	var device sql.NullString
	if e = tx.QueryRow("SELECT status,expires_at,user_id,device_id FROM link_codes WHERE code=?", code).Scan(&status, &expires, &oldUID, &device); e != nil {
		return nil, e
	}
	if status != "pending" || expires < now() {
		return nil, problem(409, "LINK_NOT_PENDING", "Link code expired or already completed")
	}
	var limit, count int
	if e = tx.QueryRow("SELECT device_limit FROM users WHERE id=?", uid).Scan(&limit); e != nil {
		return nil, e
	}
	tx.QueryRow("SELECT count(*) FROM devices WHERE user_id=?", uid).Scan(&count)
	if count >= limit && uid != oldUID {
		return nil, problem(409, "DEVICE_LIMIT", "Device limit reached")
	}
	if device.Valid && device.String != "" {
		if _, e = tx.Exec("UPDATE devices SET user_id=? WHERE id=?", uid, device.String); e != nil {
			return nil, e
		}
		tx.Exec("UPDATE sessions SET user_id=? WHERE device_id=? AND revoked=0", uid, device.String)
	}
	if _, e = tx.Exec("UPDATE link_codes SET status='completed',linked_user_id=?,source='admin' WHERE code=?", uid, code); e != nil {
		return nil, e
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	return Object{"code": code, "status": "completed", "source": "admin", "expires_at": expires, "reauth_required": false}, nil
}
func (s *Server) download(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/downloads/")
	if name == "" || filepath.Base(name) != name || strings.ContainsAny(name, "/\\") {
		http.NotFound(w, r)
		return
	}
	dir := s.cfg.DownloadDir
	if dir == "" {
		dir = "downloads"
	}
	http.ServeFile(w, r, filepath.Join(dir, name))
}
func (s *Server) checkout(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/sandbox/checkout/")
	if r.Method != "GET" {
		fail(w, problem(405, "METHOD_NOT_ALLOWED", "Use authenticated sandbox API to confirm"))
		return
	}
	m, e := s.order(id)
	if e != nil {
		fail(w, e)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	t := template.Must(template.New("checkout").Parse(`<!doctype html><html lang="vi"><meta charset="utf-8"><title>TN Assistant SANDBOX</title><body><h1>SANDBOX — Không thu tiền thật</h1><p>Đơn {{.id}} · {{.amount}} {{.currency}}</p><p>Trạng thái: {{.status}}</p><p>Xác nhận, hủy hoặc thất bại bằng nút trong ứng dụng/dashboard đã đăng nhập.</p></body></html>`))
	t.Execute(w, m)
}
func (s *Server) webhook(w http.ResponseWriter, r *http.Request) error {
	if r.Method != "POST" {
		return problem(405, "METHOD_NOT_ALLOWED", "POST required")
	}
	if s.cfg.WebhookSecret == "" {
		return problem(503, "WEBHOOK_NOT_CONFIGURED", "Set TN_WEBHOOK_SECRET")
	}
	raw, e := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if e != nil {
		return e
	}
	mac := hmac.New(sha256.New, []byte(s.cfg.WebhookSecret))
	mac.Write(raw)
	sig, e := hex.DecodeString(r.Header.Get("X-TN-Signature"))
	if e != nil || !hmac.Equal(sig, mac.Sum(nil)) {
		return problem(401, "INVALID_SIGNATURE", "Invalid webhook HMAC")
	}
	var m Object
	if json.Unmarshal(raw, &m) != nil || str(m, "event_id") == "" {
		return problem(400, "INVALID_WEBHOOK", "event_id required")
	}
	out, e := s.paymentEvent(str(m, "event_id"), str(m, "order_id"), str(m, "status"))
	if e != nil {
		return e
	}
	send(w, 200, out)
	return nil
}
func (s *Server) order(id string) (Object, error) {
	var uid, product, status, currency, created string
	var amount int64
	e := s.db.QueryRow("SELECT user_id,product_id,status,amount,currency,created_at FROM payments WHERE id=?", id).Scan(&uid, &product, &status, &amount, &currency, &created)
	if e != nil {
		return nil, e
	}
	return Object{"id": id, "user_id": uid, "product_id": product, "status": status, "amount": amount, "currency": currency, "created_at": created, "sandbox": true, "checkout_url": s.cfg.PublicURL + "/sandbox/checkout/" + id}, nil
}
func (s *Server) orders(uid string) ([]Object, error) {
	query := "SELECT id FROM payments"
	args := []any{}
	if uid != "" {
		query += " WHERE user_id=?"
		args = append(args, uid)
	}
	query += " ORDER BY created_at DESC LIMIT 500"
	rows, e := s.db.Query(query, args...)
	if e != nil {
		return nil, e
	}
	ids := []string{}
	for rows.Next() {
		var id string
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	out := []Object{}
	for _, id := range ids {
		m, e := s.order(id)
		if e != nil {
			return nil, e
		}
		out = append(out, m)
	}
	return out, nil
}
func (s *Server) createOrder(uid, pid string) (Object, error) {
	p, e := s.record("products", pid)
	if e != nil {
		return nil, e
	}
	if integer(p, "duration_days", 0) <= 0 || integer(p, "price", -1) < 0 {
		return nil, problem(400, "INVALID_PRODUCT", "Product must have positive duration and nonnegative price")
	}
	id := ident()
	_, e = s.db.Exec("INSERT INTO payments VALUES(?,?,?,?,?,?,?,?)", id, uid, pid, "pending", integer(p, "price", 0), str(p, "currency"), integer(p, "duration_days", 30), now())
	if e != nil {
		return nil, e
	}
	return s.order(id)
}
func grant(tx *sql.Tx, uid, pid, source string, days int) (string, error) {
	id := ident()
	var prev string
	if tx.QueryRow("SELECT id FROM entitlements WHERE source=?", source).Scan(&prev) == nil {
		return prev, nil
	}
	start := time.Now().UTC()
	var end string
	tx.QueryRow("SELECT max(expires_at) FROM entitlements WHERE user_id=? AND product_id=?", uid, pid).Scan(&end)
	if t, e := time.Parse(time.RFC3339Nano, end); e == nil && t.After(start) {
		start = t
	}
	_, e := tx.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", id, uid, pid, source, start.Format(time.RFC3339Nano), start.AddDate(0, 0, days).Format(time.RFC3339Nano))
	return id, e
}
func (s *Server) paymentEvent(event, id, status string) (Object, error) {
	if status != "paid" && status != "failed" && status != "cancelled" {
		return nil, problem(400, "INVALID_STATUS", "status must be paid, failed, cancelled")
	}
	tx, e := s.db.Begin()
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	var prevID, prevStatus string
	e = tx.QueryRow("SELECT order_id,status FROM payment_events WHERE event_id=?", event).Scan(&prevID, &prevStatus)
	if e == nil {
		if prevID != id || prevStatus != status {
			return nil, problem(409, "EVENT_CONFLICT", "event_id reused with a different payload")
		}
		tx.Rollback()
		out, e := s.order(id)
		if out != nil {
			out["duplicate"] = true
		}
		return out, e
	}
	var uid, pid, current string
	var days int
	if e = tx.QueryRow("SELECT user_id,product_id,status,duration_days FROM payments WHERE id=?", id).Scan(&uid, &pid, &current, &days); e != nil {
		return nil, e
	}
	if current != "pending" && current != status {
		return nil, problem(409, "ORDER_FINAL", "Final order status cannot change")
	}
	if _, e = tx.Exec("INSERT INTO payment_events VALUES(?,?,?,?)", event, id, status, now()); e != nil {
		return nil, e
	}
	if current == "pending" {
		if status == "paid" {
			if _, e = grant(tx, uid, pid, "payment:"+id, days); e != nil {
				return nil, e
			}
		}
		if _, e = tx.Exec("UPDATE payments SET status=? WHERE id=?", status, id); e != nil {
			return nil, e
		}
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	return s.order(id)
}
func (s *Server) redeem(uid, code string) (Object, error) {
	if code == "" {
		return nil, problem(400, "INVALID_CODE", "code required")
	}
	tx, e := s.db.Begin()
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	var existing string
	if tx.QueryRow("SELECT entitlement_id FROM redemptions WHERE code=? AND user_id=?", code, uid).Scan(&existing) == nil {
		tx.Rollback()
		return Object{"id": existing, "duplicate": true}, nil
	}
	var product string
	var uses int
	var expiry sql.NullString
	if e = tx.QueryRow("SELECT product_id,remaining,expires_at FROM activation_codes WHERE code=?", code).Scan(&product, &uses, &expiry); e != nil {
		return nil, problem(400, "INVALID_CODE", "Activation code does not exist")
	}
	if uses <= 0 || expiry.Valid && activationExpiryElapsed(expiry.String, time.Now()) {
		return nil, problem(409, "CODE_EXHAUSTED", "Code expired or exhausted")
	}
	var raw string
	if e = tx.QueryRow("SELECT data FROM records WHERE kind='products' AND id=?", product).Scan(&raw); e != nil {
		return nil, e
	}
	var p Object
	json.Unmarshal([]byte(raw), &p)
	id, e := grant(tx, uid, product, "activation:"+code+":"+uid, integer(p, "duration_days", 30))
	if e != nil {
		return nil, e
	}
	if _, e = tx.Exec("UPDATE activation_codes SET remaining=remaining-1 WHERE code=? AND remaining>0", code); e != nil {
		return nil, e
	}
	if _, e = tx.Exec("INSERT INTO redemptions VALUES(?,?,?)", code, uid, id); e != nil {
		return nil, e
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	return Object{"id": id, "product_id": product, "source": "activation", "duplicate": false}, nil
}
