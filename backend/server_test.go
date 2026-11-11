package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"github.com/coder/websocket"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func testServer(t *testing.T) *Server {
	t.Helper()
	s, e := openServer(Config{DBPath: filepath.Join(t.TempDir(), "test.db"), AdminPassword: "fixture-admin-password", WebhookSecret: "fixture-webhook-secret"})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.db.Close() })
	return s
}
func call(t *testing.T, s *Server, method, path, token string, v any) (int, Object) {
	t.Helper()
	var b io.Reader
	if v != nil {
		b = strings.NewReader(jsonText(v))
	}
	r := httptest.NewRequest(method, path, b)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	var m Object
	if e := json.Unmarshal(w.Body.Bytes(), &m); e != nil {
		t.Fatalf("%s %s: invalid JSON status%d %s", method, path, w.Code, w.Body.String())
	}
	return w.Code, m
}
func guest(t *testing.T, s *Server) Object {
	status, m := call(t, s, "POST", "/v1/auth/guest", "", Object{"device_id": ident(), "device_name": "Fixture device"})
	if status != 200 {
		t.Fatalf("guest%d %v", status, m)
	}
	return m
}
func adminToken(t *testing.T, s *Server) string {
	status, m := call(t, s, "POST", "/v1/auth/login", "", Object{"username": "admin", "password": "fixture-admin-password"})
	if status != 200 {
		t.Fatalf("login%d %v", status, m)
	}
	return str(m, "access_token")
}
func requireStatus(t *testing.T, want, status int, m Object) {
	t.Helper()
	if status != want {
		t.Fatalf("wanted %d, got %d: %v", want, status, m)
	}
}
func TestAuthOwnershipRefreshReplayLogoutAndExpiry(t *testing.T) {
	s := testServer(t)
	a, b := guest(t, s), guest(t, s)
	token := str(a, "access_token")
	admin := adminToken(t, s)
	status, m := call(t, s, "GET", "/v1/admin/users", token, nil)
	requireStatus(t, 403, status, m)
	otherDevice := str(b["device"].(map[string]any), "id")
	status, m = call(t, s, "DELETE", "/v1/devices/"+otherDevice, token, nil)
	requireStatus(t, 403, status, m)
	status, m = call(t, s, "POST", "/v1/auth/refresh", "", Object{"refresh_token": str(a, "refresh_token")})
	requireStatus(t, 200, status, m)
	newAccess := str(m, "access_token")
	status, m = call(t, s, "GET", "/v1/me", token, nil)
	requireStatus(t, 401, status, m)
	status, m = call(t, s, "POST", "/v1/auth/refresh", "", Object{"refresh_token": str(a, "refresh_token")})
	requireStatus(t, 401, status, m)
	status, m = call(t, s, "GET", "/v1/me", newAccess, nil)
	requireStatus(t, 401, status, m)
	status, m = call(t, s, "POST", "/v1/auth/logout", "", Object{"refresh_token": str(b, "refresh_token")})
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "GET", "/v1/me", str(b, "access_token"), nil)
	requireStatus(t, 401, status, m)
	s.db.Exec("UPDATE sessions SET access_expires=0 WHERE access_hash=?", hash(admin))
	status, m = call(t, s, "GET", "/v1/admin/users", admin, nil)
	requireStatus(t, 401, status, m)
	var raw string
	s.db.QueryRow("SELECT refresh_hash FROM sessions LIMIT 1").Scan(&raw)
	if len(raw) != 64 || raw == str(a, "refresh_token") {
		t.Fatal("refresh token must be hashed")
	}
}
func TestPaymentAtomicIdempotenceActivationAndWebhook(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	uid := str(g["user"].(map[string]any), "id")
	token := str(g, "access_token")
	admin := adminToken(t, s)
	order, e := s.createOrder(uid, "premium-month")
	if e != nil {
		t.Fatal(e)
	}
	id := str(order, "id")
	var wg sync.WaitGroup
	errs := make(chan error, 10)
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); _, e := s.paymentEvent(ident(), id, "paid"); errs <- e }()
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	var grants int
	s.db.QueryRow("SELECT count(*) FROM entitlements WHERE source=?", "payment:"+id).Scan(&grants)
	if grants != 1 {
		t.Fatalf("payment granted %d times", grants)
	}
	raw := []byte(jsonText(Object{"event_id": "fixture-event", "order_id": id, "status": "paid"}))
	mac := hmac.New(sha256.New, []byte(s.cfg.WebhookSecret))
	mac.Write(raw)
	for i := 0; i < 2; i++ {
		r := httptest.NewRequest("POST", "/v1/payments/webhook", bytes.NewReader(raw))
		r.Header.Set("X-TN-Signature", hex.EncodeToString(mac.Sum(nil)))
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		if w.Code != 200 {
			t.Fatalf("webhook status%d %s", w.Code, w.Body.String())
		}
	}
	if _, e = s.paymentEvent("fixture-event", id, "failed"); e == nil {
		t.Fatal("event payload conflict accepted")
	}
	if _, e = s.paymentEvent(ident(), id, "cancelled"); e == nil {
		t.Fatal("paid order changed final status")
	}
	status, m := call(t, s, "POST", "/v1/admin/activation-codes", admin, Object{"product_id": "premium-month", "count": 1, "uses": 1})
	requireStatus(t, 201, status, m)
	code := str(m["items"].([]any)[0].(map[string]any), "code")
	for i := 0; i < 2; i++ {
		status, m = call(t, s, "POST", "/v1/activation/redeem", token, Object{"code": code})
		requireStatus(t, 200, status, m)
	}
	var remaining int
	s.db.QueryRow("SELECT remaining FROM activation_codes WHERE code=?", code).Scan(&remaining)
	if remaining != 0 {
		t.Fatal("code remaining mismatch")
	}
	s.db.QueryRow("SELECT count(*) FROM entitlements WHERE user_id=?", uid).Scan(&grants)
	if grants != 2 || len(s.entitlements(uid)) != 1 {
		t.Fatal("activation must create one renewal, with only the current period active")
	}
	other := guest(t, s)
	status, m = call(t, s, "POST", "/v1/activation/redeem", str(other, "access_token"), Object{"code": code})
	requireStatus(t, 409, status, m)
	failed, _ := s.createOrder(uid, "premium-month")
	s.paymentEvent(ident(), str(failed, "id"), "failed")
	s.db.QueryRow("SELECT count(*) FROM entitlements WHERE user_id=?", uid).Scan(&grants)
	if grants != 2 || len(s.entitlements(uid)) != 1 {
		t.Fatal("failed payment granted entitlement")
	}
}
func TestSettingsAdminCRUDContentLinkAndDelete(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	admin := adminToken(t, s)
	status, m := call(t, s, "PUT", "/v1/settings", token, Object{"volume": 73, "private_mode": true})
	requireStatus(t, 200, status, m)
	if m["volume"] != float64(73) || m["private_mode"] != true {
		t.Fatal("settings not saved")
	}
	status, m = call(t, s, "PUT", "/v1/settings", token, Object{"volume": 101})
	requireStatus(t, 400, status, m)
	status, m = call(t, s, "POST", "/v1/admin/content", admin, Object{"kind": "guide", "title": "Fixture title", "body": "Fixture content", "published": false})
	requireStatus(t, 200, status, m)
	id := str(m, "id")
	status, m = call(t, s, "GET", "/v1/content", token, nil)
	requireStatus(t, 200, status, m)
	if len(m["items"].([]any)) != 0 {
		t.Fatal("unpublished content exposed")
	}
	status, m = call(t, s, "PUT", "/v1/admin/content/"+id, admin, Object{"kind": "guide", "title": "Fixture title", "published": true})
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "GET", "/v1/content", token, nil)
	if len(m["items"].([]any)) != 1 {
		t.Fatal("published content missing")
	}
	status, m = call(t, s, "POST", "/v1/admin/users", admin, Object{"name": "Linked user", "role": "user", "device_limit": 1})
	requireStatus(t, 200, status, m)
	target := str(m, "id")
	status, m = call(t, s, "POST", "/v1/link/start", token, Object{})
	requireStatus(t, 200, status, m)
	code := str(m, "code")
	status, m = call(t, s, "POST", "/v1/admin/link/"+code+"/complete", admin, Object{"user_id": target})
	requireStatus(t, 200, status, m)
	if m["source"] != "admin" {
		t.Fatal("admin linking must be labeled")
	}
	status, m = call(t, s, "GET", "/v1/me", token, nil)
	requireStatus(t, 200, status, m)
	if str(m["user"].(map[string]any), "id") != target {
		t.Fatal("session not linked")
	}
	status, m = call(t, s, "GET", "/v1/link/"+code, token, nil)
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "GET", "/v1/admin/users", admin, nil)
	for _, v := range m["items"].([]any) {
		u := v.(map[string]any)
		if str(u, "username") == "admin" {
			status, m = call(t, s, "PUT", "/v1/admin/users/"+str(u, "id"), admin, Object{"name": "Administrator edited", "role": "admin", "device_limit": 10})
			requireStatus(t, 200, status, m)
			break
		}
	}
	adminToken(t, s)
	status, m = call(t, s, "DELETE", "/v1/me", token, nil)
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "GET", "/v1/me", token, nil)
	requireStatus(t, 401, status, m)
}
func TestProviderNotConfiguredAndRedaction(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	for _, path := range []string{"/v1/assistant/text", "/v1/tts", "/v1/traffic/check"} {
		status, m := call(t, s, "POST", path, token, Object{"text": "Fixture only", "plate": "TEST", "session_id": "test"})
		requireStatus(t, 503, status, m)
		if str(m["error"].(map[string]any), "code") != "PROVIDER_NOT_CONFIGURED" {
			t.Fatal(m)
		}
	}
	if e := s.saveProviders(Object{"asr": Object{"mode": "generic", "url": "https://example.com", "token": "literal"}}); e == nil {
		t.Fatal("literal credential accepted")
	}
	admin := adminToken(t, s)
	status, m := call(t, s, "GET", "/v1/admin/providers", admin, nil)
	requireStatus(t, 200, status, m)
	if strings.Contains(jsonText(m), "fixture-admin-password") {
		t.Fatal("credential leaked")
	}
}
func TestLegacyProviderHTTPFixtures(t *testing.T) {
	s := testServer(t)
	t.Setenv("FIXTURE_ASR_TOKEN", "fixture-asr-token")
	t.Setenv("FIXTURE_NLU_TOKEN", "fixture-nlu-token")
	t.Setenv("FIXTURE_DEVICE_TOKEN", "fixture-device-token")
	t.Setenv("FIXTURE_TTS_KEY", "fixture-key")
	t.Setenv("FIXTURE_TTS_SECRET", "fixture-secret")
	audio := []byte{1, 2, 3, 4, 5, 6}
	sum := md5.Sum(audio)
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/asr":
			if e := r.ParseMultipartForm(1 << 20); e != nil {
				t.Error(e)
				w.WriteHeader(400)
				return
			}
			if r.Header.Get("token") != "fixture-asr-token" || r.FormValue("dataSignature") != hex.EncodeToString(sum[:]) || r.FormValue("layout") != "ignored" || r.FormValue("sampleRate") != "16000" {
				t.Error("legacy ASR fields or auth mismatch")
			}
			f, h, e := r.FormFile("file")
			if e != nil {
				t.Error(e)
				return
			}
			b, _ := io.ReadAll(f)
			f.Close()
			if !bytes.Equal(b, audio) || h.Filename != "file.pcm" {
				t.Error("legacy multipart file mismatch")
			}
			send(w, 200, Object{"code": 0, "asr": "fixture transcript"})
		case "/nlu":
			r.ParseForm()
			var event Object
			if json.Unmarshal([]byte(r.FormValue("event")), &event) != nil || str(event, "event") != "MessageInput" || str(event["payload"].(map[string]any), "message") != "fixture transcript" {
				t.Error("legacy NLU MessageInput envelope mismatch")
			}
			if r.Header.Get("Authorization") != "Token fixture-nlu-token UID 42" || r.FormValue("device_token") != "fixture-device-token" || r.FormValue("asr") != "streaming" || r.FormValue("session_state") != "session.chat" {
				t.Error("legacy NLU form/auth mismatch")
			}
			send(w, 200, Object{"code": 0, "log_request_id": "fixture-id", "session_key": "fixture-session", "directives": []Object{{"action_code": "Volume", "payload": Object{"value": 3}, "action_id": "a", "required": []any{}}, {"action_code": "SpeechSynthesizer", "payload": Object{"utter_text": "fixture speech"}, "action_id": "b", "required": []any{}}, {"action_code": "Delay", "payload": Object{"value": 100}, "action_id": "c", "required": []any{}}}})
		case "/tts":
			r.ParseForm()
			if r.Form.Get("apikey") != "fixture-key" || r.Form.Get("sig") != legacyTTSSign(r.Form, "fixture-secret") || r.Form.Get("input") != "fixture speech" {
				t.Error("legacy TTS signature mismatch")
			}
			send(w, 200, Object{"error_code": 0, "data": Object{"url": "https://fixture.invalid/audio.m3u8", "retry": 2, "timeoutMilSecs": 1000, "requestId": 1}})
		}
	}))
	defer fixture.Close()
	config := Object{"asr": Object{"mode": "legacy_tn", "url": fixture.URL + "/asr", "token_env": "FIXTURE_ASR_TOKEN", "uid": "42", "client_app_type": "fixture", "version": "1"}, "nlu": Object{"mode": "legacy_tn", "url": fixture.URL + "/nlu", "token_env": "FIXTURE_NLU_TOKEN", "device_token_env": "FIXTURE_DEVICE_TOKEN", "uid": "42", "options": Object{"asr_type": "streaming"}}, "tts": Object{"mode": "legacy_tn", "url": fixture.URL + "/tts", "api_key_env": "FIXTURE_TTS_KEY", "secret_env": "FIXTURE_TTS_SECRET", "options": Object{"speaker_id": "1", "app_src": "fixture"}}}
	if e := s.saveProviders(config); e != nil {
		t.Fatal(e)
	}
	g := guest(t, s)
	token := str(g, "access_token")
	r := httptest.NewRequest("POST", "/v1/speech/decode?sample_rate=16000&channels=1", bytes.NewReader(audio))
	r.Header.Set("Content-Type", "audio/pcm")
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatalf("ASR fixture %d %s", w.Code, w.Body.String())
	}
	status, m := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "fixture transcript", "session_id": "fixture-session"})
	requireStatus(t, 200, status, m)
	ds := m["directives"].([]any)
	if len(ds) != 3 || ds[0].(map[string]any)["type"] != "Volume" || ds[1].(map[string]any)["type"] != "SpeechSynthesizer" || ds[2].(map[string]any)["type"] != "Delay" {
		t.Fatal("directive order changed")
	}
	if str(m, "utterance") != "fixture speech" {
		t.Fatal("SpeechSynthesizer utter_text not normalized")
	}
	payload := ds[1].(map[string]any)["payload"].(map[string]any)
	if payload["action_id"] != "b" {
		t.Fatal("legacy raw payload lost")
	}
	status, m = call(t, s, "POST", "/v1/tts", token, Object{"text": "fixture speech"})
	requireStatus(t, 200, status, m)
	if str(m, "url") != "https://fixture.invalid/audio.m3u8" {
		t.Fatal("TTS url mismatch")
	}
	if _, e := normalizeNLU(Object{"code": 1, "directives": []any{}}, "legacy_tn"); e == nil {
		t.Fatal("rejected NLU accepted")
	}
}
func TestWebsocketConfiguredFallbackAndLegacyFixture(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	host := httptest.NewServer(s.Handler())
	defer host.Close()
	dial := func() *websocket.Conn {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		c, _, e := websocket.Dial(ctx, "ws"+strings.TrimPrefix(host.URL, "http")+"/v1/speech/stream", &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
		if e != nil {
			t.Fatal(e)
		}
		return c
	}
	read := func(c *websocket.Conn) Object {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, b, e := c.Read(ctx)
		if e != nil {
			t.Fatal(e)
		}
		var m Object
		json.Unmarshal(b, &m)
		return m
	}
	c := dial()
	m := read(c)
	if str(m["error"].(map[string]any), "code") != "PROVIDER_NOT_CONFIGURED" {
		t.Fatal(m)
	}
	c.CloseNow()
	if e := s.saveProviders(Object{"asr": Object{"mode": "generic", "url": "https://fixture.invalid/decode"}}); e != nil {
		t.Fatal(e)
	}
	c = dial()
	m = read(c)
	if str(m["error"].(map[string]any), "code") != "STREAMING_UNSUPPORTED" {
		t.Fatal(m)
	}
	c.CloseNow()
	t.Setenv("FIXTURE_STREAM_TOKEN", "fixture-token")
	t.Setenv("FIXTURE_STREAM_SECRET", "fixture-secret")
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		ts := q.Get("timestamp")
		if len(ts) != 13 || q.Get("user_id") != "42" || q.Get("token") != "fixture-token" || r.Header.Get("Authorization") != hash(ts+hash("42fixture-secret")) {
			t.Error("legacy streaming auth/query mismatch")
		}
		up, e := websocket.Accept(w, r, nil)
		if e != nil {
			return
		}
		defer up.CloseNow()
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		typ, b, e := up.Read(ctx)
		if e != nil {
			return
		}
		if typ != websocket.MessageBinary || !bytes.Equal(b, []byte{1, 2, 3, 4}) {
			t.Error("PCM not forwarded")
		}
		up.Write(ctx, websocket.MessageText, []byte(jsonText(Object{"status": 0, "result": Object{"final": false, "hypotheses": []Object{{"transcript": "fixture partial"}}}})))
		typ, b, e = up.Read(ctx)
		if e != nil {
			return
		}
		if typ != websocket.MessageText || string(b) != "EOS" {
			t.Error("legacy end message mismatch")
		}
		up.Write(ctx, websocket.MessageText, []byte(jsonText(Object{"status": 0, "id": "fixture-id", "result": Object{"final": true, "hypotheses": []Object{{"transcript": "fixture final"}}}})))
	}))
	defer fixture.Close()
	if e := s.saveProviders(Object{"asr": Object{"mode": "legacy_tn", "stream_url": "ws" + strings.TrimPrefix(fixture.URL, "http"), "token_env": "FIXTURE_STREAM_TOKEN", "secret_env": "FIXTURE_STREAM_SECRET", "uid": "42", "client_app_type": "fixture", "version": "1"}}); e != nil {
		t.Fatal(e)
	}
	c = dial()
	defer c.CloseNow()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c.Write(ctx, websocket.MessageText, []byte(jsonText(Object{"type": "start", "session_id": "fixture", "sample_rate": 16000, "channels": 1, "encoding": "pcm_s16le"})))
	m = read(c)
	if m["type"] != "ready" {
		t.Fatal(m)
	}
	c.Write(ctx, websocket.MessageBinary, []byte{1, 2, 3, 4})
	m = read(c)
	if m["type"] != "partial" || m["text"] != "fixture partial" {
		t.Fatal(m)
	}
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"end"}`))
	m = read(c)
	if m["type"] != "final" || m["text"] != "fixture final" {
		t.Fatal(m)
	}
}
func TestBackupRestoreAndTenConcurrentClients(t *testing.T) {
	s := testServer(t)
	start := time.Now()
	var wg sync.WaitGroup
	errs := make(chan string, 10)
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r := httptest.NewRequest("POST", "/v1/auth/guest", strings.NewReader(jsonText(Object{"device_id": ident(), "device_name": "Concurrent fixture"})))
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, r)
			if w.Code != 200 {
				errs <- w.Body.String()
				return
			}
			var session Object
			json.Unmarshal(w.Body.Bytes(), &session)
			for j := 0; j < 20; j++ {
				r = httptest.NewRequest("GET", "/v1/me", nil)
				r.Header.Set("Authorization", "Bearer "+str(session, "access_token"))
				w = httptest.NewRecorder()
				s.Handler().ServeHTTP(w, r)
				if w.Code != 200 {
					errs <- w.Body.String()
					return
				}
			}
		}()
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		t.Fatal(e)
	}
	t.Logf("10 concurrent clients, 210 requests completed in %s; fixture local SQLite, no provider latency", time.Since(start))
	path := filepath.Join(t.TempDir(), "backup.db")
	if e := s.backup(path); e != nil {
		t.Fatal(e)
	}
	if e := s.backup(path); e == nil {
		t.Fatal("backup overwrote existing file")
	}
	restored := filepath.Join(t.TempDir(), "restored.db")
	if e := restoreDB(path, restored); e != nil {
		t.Fatal(e)
	}
	if e := restoreDB(path, restored); e == nil {
		t.Fatal("restore overwrote existing file")
	}
	copy, e := openServer(Config{DBPath: restored})
	if e != nil {
		t.Fatal(e)
	}
	defer copy.db.Close()
	var count int
	copy.db.QueryRow("SELECT count(*) FROM devices").Scan(&count)
	if count != 10 {
		t.Fatalf("restored %d devices", count)
	}
}
func TestGenericFixturePrivateHistoryBinaryTTSAndFailure(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	other := guest(t, s)
	audio := []byte{4, 3, 2, 1}
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/nlu":
			send(w, 200, Object{"utterance": "fixture only", "directives": []Object{}})
		case "/tts":
			w.Header().Set("Content-Type", "audio/pcm")
			w.Write(audio)
		default:
			w.WriteHeader(502)
		}
	}))
	defer fixture.Close()
	if e := s.saveProviders(Object{"nlu": Object{"mode": "generic", "url": fixture.URL + "/nlu"}, "tts": Object{"mode": "generic", "url": fixture.URL + "/tts"}}); e != nil {
		t.Fatal(e)
	}
	status, m := call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "fixture private text", "session_id": "fixture"})
	requireStatus(t, 200, status, m)
	status, m = call(t, s, "GET", "/v1/history", token, nil)
	if len(m["items"].([]any)) != 0 {
		t.Fatal("private history retained")
	}
	status, m = call(t, s, "POST", "/v1/tts", token, Object{"text": "fixture text"})
	requireStatus(t, 200, status, m)
	audioURL := str(m, "url")
	if !strings.HasPrefix(audioURL, "/v1/tts/audio/") {
		t.Fatalf("cached audio must resolve against the authenticated client origin: %q", audioURL)
	}
	path := strings.TrimPrefix(audioURL, s.cfg.PublicURL)
	serveAudio := func(method, rangeHeader string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, nil)
		r.Header.Set("Authorization", "Bearer "+token)
		if rangeHeader != "" {
			r.Header.Set("Range", rangeHeader)
		}
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		return w
	}
	w := serveAudio("GET", "")
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), audio) || w.Header().Get("Content-Length") != "4" || w.Header().Get("Accept-Ranges") != "bytes" {
		t.Fatal("binary TTS proxy mismatch")
	}
	partial := serveAudio("GET", "bytes=1-2")
	if partial.Code != http.StatusPartialContent || !bytes.Equal(partial.Body.Bytes(), audio[1:3]) ||
		partial.Header().Get("Content-Range") != "bytes 1-2/4" || partial.Header().Get("Content-Length") != "2" {
		t.Fatalf("bounded byte range mismatch: status=%d headers=%v body=%v", partial.Code, partial.Header(), partial.Body.Bytes())
	}
	suffix := serveAudio("GET", "bytes=-2")
	if suffix.Code != http.StatusPartialContent || !bytes.Equal(suffix.Body.Bytes(), audio[2:]) || suffix.Header().Get("Content-Range") != "bytes 2-3/4" {
		t.Fatalf("suffix byte range mismatch: status=%d headers=%v body=%v", suffix.Code, suffix.Header(), suffix.Body.Bytes())
	}
	head := serveAudio("HEAD", "")
	if head.Code != http.StatusOK || head.Body.Len() != 0 || head.Header().Get("Content-Length") != "4" {
		t.Fatalf("HEAD metadata mismatch: status=%d headers=%v body=%v", head.Code, head.Header(), head.Body.Bytes())
	}
	headRange := serveAudio("HEAD", "bytes=1-2")
	if headRange.Code != http.StatusPartialContent || headRange.Body.Len() != 0 || headRange.Header().Get("Content-Range") != "bytes 1-2/4" || headRange.Header().Get("Content-Length") != "2" {
		t.Fatalf("HEAD range metadata mismatch: status=%d headers=%v", headRange.Code, headRange.Header())
	}
	unsatisfiable := serveAudio("GET", "bytes=9-")
	if unsatisfiable.Code != http.StatusRequestedRangeNotSatisfiable || unsatisfiable.Header().Get("Content-Range") != "bytes */4" {
		t.Fatalf("unsatisfiable range mismatch: status=%d headers=%v", unsatisfiable.Code, unsatisfiable.Header())
	}
	multiple := serveAudio("GET", "bytes=0-0,2-2")
	if multiple.Code != http.StatusOK || !bytes.Equal(multiple.Body.Bytes(), audio) {
		t.Fatalf("unsupported multi-range should return full audio: status=%d body=%v", multiple.Code, multiple.Body.Bytes())
	}
	status, m = call(t, s, "GET", path, str(other, "access_token"), nil)
	requireStatus(t, 403, status, m)
	id := strings.TrimPrefix(path, "/v1/tts/audio/")
	record, e := s.record("tts_audio", id)
	if e != nil {
		t.Fatal(e)
	}
	record["expires_at"] = "2000-01-01T00:00:00Z"
	s.put("tts_audio", id, str(g["user"].(map[string]any), "id"), record)
	status, m = call(t, s, "GET", path, token, nil)
	requireStatus(t, 410, status, m)
	if e = s.saveProviders(Object{"nlu": Object{"mode": "generic", "url": fixture.URL + "/failure"}}); e != nil {
		t.Fatal(e)
	}
	status, m = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "fixture failure", "session_id": "fixture"})
	requireStatus(t, 502, status, m)
}
func TestGenericWebsocketCancellationFixture(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	observed := make(chan string, 1)
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		up, e := websocket.Accept(w, r, nil)
		if e != nil {
			return
		}
		defer up.CloseNow()
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		for {
			_, raw, e := up.Read(ctx)
			if e != nil {
				observed <- "connection closed before cancel"
				return
			}
			var m Object
			json.Unmarshal(raw, &m)
			if str(m, "type") == "cancel" {
				observed <- "cancel"
				return
			}
		}
	}))
	defer fixture.Close()
	if e := s.saveProviders(Object{"asr": Object{"mode": "generic", "stream_url": "ws" + strings.TrimPrefix(fixture.URL, "http")}}); e != nil {
		t.Fatal(e)
	}
	host := httptest.NewServer(s.Handler())
	defer host.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, e := websocket.Dial(ctx, "ws"+strings.TrimPrefix(host.URL, "http")+"/v1/speech/stream", &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + str(g, "access_token")}}})
	if e != nil {
		t.Fatal(e)
	}
	defer c.CloseNow()
	c.Write(ctx, websocket.MessageText, []byte(jsonText(Object{"type": "start", "session_id": "fixture", "sample_rate": 16000, "channels": 1, "encoding": "pcm_s16le"})))
	if _, _, e = c.Read(ctx); e != nil {
		t.Fatal(e)
	}
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"cancel"}`))
	select {
	case got := <-observed:
		if got != "cancel" {
			t.Fatal(got)
		}
	case <-ctx.Done():
		t.Fatal("cancel not forwarded")
	}
}
