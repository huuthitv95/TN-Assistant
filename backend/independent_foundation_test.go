package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/bcrypt"
)

func independentTestServer(t *testing.T) *Server {
	t.Helper()
	s, err := openServer(Config{DBPath: filepath.Join(t.TempDir(), "independent.db"), RuntimeMode: "independent", AdminPassword: "fixture-admin-password"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.db.Close() })
	return s
}

func runtimeObject(t *testing.T, s *Server) Object {
	t.Helper()
	cfg, err := s.runtimeConfig()
	if err != nil {
		t.Fatal(err)
	}
	var m Object
	if err = json.Unmarshal([]byte(jsonText(cfg)), &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func tnRegister(t *testing.T, s *Server, username, device string) Object {
	t.Helper()
	m := Object{"username": username, "password": "registered-password", "name": "TN account"}
	if device != "" {
		m["device_id"], m["device_name"] = device, "TN device"
	}
	status, out := call(t, s, "POST", "/v1/auth/register", "", m)
	requireStatus(t, 200, status, out)
	return out
}

func TestIndependentRuntimeConfigAtomicRevisionAndValidation(t *testing.T) {
	s := independentTestServer(t)
	admin, g := adminToken(t, s), guest(t, s)
	status, out := call(t, s, "GET", "/v1/admin/runtime/config", str(g, "access_token"), nil)
	requireStatus(t, 403, status, out)
	cfg := runtimeObject(t, s)
	if str(cfg, "runtime_mode") != "independent" || str(cfg, "cost_policy") != "free_only" || cfg["legacy_fallback"] != false {
		t.Fatal(cfg)
	}
	status, out = call(t, s, "PUT", "/v1/admin/runtime/config", admin, Object{"config": cfg})
	requireStatus(t, 400, status, out)
	for _, change := range []Object{
		{"legacy_fallback": true}, {"runtime_mode": "legacy_tn"}, {"cost_policy": "paid"}, {"asr_url": "http://127.0.0.1:8091?"}, {"asr_url": "http://169.254.169.254"}, {"tts_url": "https://example.test"}, {"max_devices": 101}, {"speech_queue_limit": 0}, {"driving_enabled": nil}, {"unknown": true},
	} {
		candidate := runtimeObject(t, s)
		for key, value := range change {
			candidate[key] = value
		}
		status, out = call(t, s, "PUT", "/v1/admin/runtime/config", admin, Object{"base_revision": 1, "config": candidate})
		requireStatus(t, 400, status, out)
	}
	cfg["driving_enabled"] = true
	status, out = call(t, s, "PUT", "/v1/admin/runtime/config", admin, Object{"base_revision": 1, "config": cfg})
	requireStatus(t, 200, status, out)
	if integer(out, "revision", 0) != 2 || out["driving_enabled"] != true {
		t.Fatal(out)
	}
	status, out = call(t, s, "PUT", "/v1/admin/runtime/config", admin, Object{"base_revision": 1, "config": cfg})
	requireStatus(t, 409, status, out)
	var audit int
	if err := s.db.QueryRow("SELECT count(*) FROM records WHERE kind='runtime_audit' AND json_extract(data,'$.action')='runtime_config_updated'").Scan(&audit); err != nil || audit != 1 {
		t.Fatal(audit, err)
	}
	if _, err := s.db.Exec(`CREATE TRIGGER reject_runtime_audit BEFORE INSERT ON records WHEN NEW.kind='runtime_audit' BEGIN SELECT RAISE(ABORT,'fixture audit failure'); END`); err != nil {
		t.Fatal(err)
	}
	cfg = runtimeObject(t, s)
	cfg["news_enabled"] = true
	status, out = call(t, s, "PUT", "/v1/admin/runtime/config", admin, Object{"base_revision": 2, "config": cfg})
	requireStatus(t, 500, status, out)
	current := runtimeObject(t, s)
	if integer(current, "revision", 0) != 2 || current["news_enabled"] != false {
		t.Fatal("config escaped failed audit transaction", current)
	}
}

func TestIndependentConcurrentRuntimeEditorsHaveOneWinner(t *testing.T) {
	s := independentTestServer(t)
	admin, cfg := adminToken(t, s), runtimeObject(t, s)
	statuses := make(chan int, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, _ := call(t, s, "PUT", "/v1/admin/runtime/config", admin, Object{"base_revision": 1, "config": cfg})
			statuses <- status
		}()
	}
	wg.Wait()
	close(statuses)
	seen := map[int]int{}
	for status := range statuses {
		seen[status]++
	}
	if seen[200] != 1 || seen[409] != 1 {
		t.Fatal(seen)
	}
}

func TestIndependentMigrationRetiresExecutableLegacyButPreservesData(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	uid := str(g["user"].(map[string]any), "id")
	for _, entry := range []struct {
		kind, id string
		data     Object
	}{
		{"assistant_request", "legacy-request", Object{"fingerprint": "keep-tombstone", "status": "complete", "result": Object{"directives": []Object{{"type": "CallApi"}}}, "nlu_provider_binding": "old-provider"}},
		{"nlu_session", "legacy-session", Object{"session_key": "old-sensitive-session"}},
		{"assistant_location", "legacy-callback", Object{"fingerprint": "callback-tombstone", "result": Object{"status": "completed"}}},
		{"history", "keep-history", Object{"text": "preserved owner history"}},
	} {
		if err := s.put(entry.kind, entry.id, uid, entry.data); err != nil {
			t.Fatal(err)
		}
	}
	s.cfg.RuntimeMode = "independent"
	if err := s.initializeIndependentRuntime(); err != nil {
		t.Fatal(err)
	}
	request, err := s.record("assistant_request", "legacy-request")
	if err != nil || str(request, "fingerprint") != "keep-tombstone" || str(request, "status") != "runtime_retired" || request["result"] != nil || request["nlu_provider_binding"] != nil {
		t.Fatal(request, err)
	}
	nlu, _ := s.record("nlu_session", "legacy-session")
	if nlu["session_key"] != nil {
		t.Fatal("legacy session retained", nlu)
	}
	history, err := s.record("history", "keep-history")
	if err != nil || str(history, "text") != "preserved owner history" {
		t.Fatal(history, err)
	}
	if _, err = s.authenticate(str(g, "access_token")); err != nil {
		t.Fatal("TN guest session changed", err)
	}
	s.cfg.RuntimeMode = ""
	if !s.independentRuntime() {
		t.Fatal("committed independent mode was not enforced")
	}
	if err = s.initializeIndependentRuntime(); err != nil {
		t.Fatal(err)
	}
	if integer(runtimeObject(t, s), "generation", 0) != 1 {
		t.Fatal("generation changed on restart")
	}
}

func TestTNRegistrationGuestUpgradePreservesOwnershipAndRevokesSessions(t *testing.T) {
	s := independentTestServer(t)
	g, other := guest(t, s), guest(t, s)
	uid, did := str(g["user"].(map[string]any), "id"), str(g["device"].(map[string]any), "id")
	if err := s.put("history", "guest-history", uid, Object{"text": "keep"}); err != nil {
		t.Fatal(err)
	}
	payload := Object{"username": "guest.upgrade", "password": "registered-password", "name": "Owner", "device_id": did, "device_name": "Owned device"}
	status, out := call(t, s, "POST", "/v1/auth/register", str(other, "access_token"), payload)
	requireStatus(t, 403, status, out)
	status, out = call(t, s, "POST", "/v1/auth/register", "", payload)
	requireStatus(t, 409, status, out)
	status, out = call(t, s, "POST", "/v1/auth/register", str(g, "access_token"), payload)
	requireStatus(t, 200, status, out)
	if str(out["user"].(map[string]any), "id") != uid || str(out["user"].(map[string]any), "role") != "user" {
		t.Fatal(out)
	}
	if _, err := s.authenticate(str(g, "access_token")); err == nil {
		t.Fatal("guest session remained valid")
	}
	if _, err := s.record("history", "guest-history"); err != nil {
		t.Fatal(err)
	}
	var passwordHash string
	if err := s.db.QueryRow("SELECT password_hash FROM users WHERE id=?", uid).Scan(&passwordHash); err != nil {
		t.Fatal(err)
	}
	if cost, err := bcrypt.Cost([]byte(passwordHash)); err != nil || cost != 12 {
		t.Fatal(cost, err)
	}
	if bcrypt.CompareHashAndPassword([]byte(passwordHash), []byte("registered-password")) != nil {
		t.Fatal("password invalid")
	}
	status, out = call(t, s, "POST", "/v1/auth/register", "", Object{"username": "GUEST.UPGRADE", "password": "registered-password"})
	requireStatus(t, 409, status, out)
}

func TestTNLoginDeviceBindingFleetAndReset(t *testing.T) {
	s := independentTestServer(t)
	u := tnRegister(t, s, "registered.user", ident())
	uid := str(u["user"].(map[string]any), "id")
	did := str(u["device"].(map[string]any), "id")
	foreign := guest(t, s)
	login := Object{"username": "registered.user", "password": "registered-password", "device_id": str(foreign["device"].(map[string]any), "id"), "device_name": "Foreign"}
	status, out := call(t, s, "POST", "/v1/auth/login", "", login)
	requireStatus(t, 409, status, out)
	login["device_id"] = did
	status, out = call(t, s, "POST", "/v1/auth/login", "", login)
	requireStatus(t, 200, status, out)
	principal, err := s.authenticate(str(out, "access_token"))
	if err != nil || str(principal.Device, "id") != did {
		t.Fatal(principal, err)
	}
	admin := adminToken(t, s)
	cfg := runtimeObject(t, s)
	cfg["max_devices"] = 2
	status, out = call(t, s, "PUT", "/v1/admin/runtime/config", admin, Object{"base_revision": 1, "config": cfg})
	requireStatus(t, 200, status, out)
	login["device_id"] = ident()
	status, out = call(t, s, "POST", "/v1/auth/login", "", login)
	requireStatus(t, 409, status, out)
	status, out = call(t, s, "POST", "/v1/auth/guest", "", Object{"device_id": ident(), "device_name": "Over limit"})
	requireStatus(t, 409, status, out)
	status, out = call(t, s, "POST", "/v1/admin/users/"+uid+"/reset-password", admin, Object{"password": "new-registered-password"})
	requireStatus(t, 200, status, out)
	status, out = call(t, s, "GET", "/v1/me", str(u, "access_token"), nil)
	requireStatus(t, 401, status, out)
	status, out = call(t, s, "POST", "/v1/auth/refresh", "", Object{"refresh_token": str(u, "refresh_token")})
	requireStatus(t, 401, status, out)
	status, out = call(t, s, "POST", "/v1/auth/login", "", Object{"username": "registered.user", "password": "registered-password"})
	requireStatus(t, 401, status, out)
	status, out = call(t, s, "POST", "/v1/auth/login", "", Object{"username": "registered.user", "password": "new-registered-password"})
	requireStatus(t, 200, status, out)
	adminPrincipal, err := s.authenticate(admin)
	if err != nil {
		t.Fatal(err)
	}
	status, out = call(t, s, "POST", "/v1/admin/users/"+str(adminPrincipal.User, "id")+"/reset-password", admin, Object{"password": "new-admin-password"})
	requireStatus(t, 403, status, out)
}

func TestTNRegistrationValidationAndAuthResetAtomicity(t *testing.T) {
	s := independentTestServer(t)
	for _, payload := range []Object{
		{"username": "a", "password": "long-password"}, {"username": "valid.name", "password": "short"}, {"username": "valid.name", "password": strings.Repeat("x", 73)}, {"username": "valid.name", "password": "long-password", "role": "admin"},
	} {
		status, out := call(t, s, "POST", "/v1/auth/register", "", payload)
		requireStatus(t, 400, status, out)
	}
	u := tnRegister(t, s, "reset.atomic", "")
	admin, uid := adminToken(t, s), str(u["user"].(map[string]any), "id")
	if _, err := s.db.Exec(`CREATE TRIGGER reject_reset_audit BEFORE INSERT ON records WHEN NEW.kind='runtime_audit' AND json_extract(NEW.data,'$.action')='user_password_reset' BEGIN SELECT RAISE(ABORT,'fixture audit failure'); END`); err != nil {
		t.Fatal(err)
	}
	status, out := call(t, s, "POST", "/v1/admin/users/"+uid+"/reset-password", admin, Object{"password": "changed-password"})
	requireStatus(t, 500, status, out)
	if _, err := s.authenticate(str(u, "access_token")); err != nil {
		t.Fatal("sessions revoked on rolled back reset", err)
	}
	status, out = call(t, s, "POST", "/v1/auth/login", "", Object{"username": "reset.atomic", "password": "registered-password"})
	requireStatus(t, 200, status, out)
}

func TestIndependentDeviceEventsOwnershipIdempotenceAndActions(t *testing.T) {
	s := independentTestServer(t)
	g, foreign := guest(t, s), guest(t, s)
	token, uid, did := str(g, "access_token"), str(g["user"].(map[string]any), "id"), str(g["device"].(map[string]any), "id")
	event := Object{"event_id": "event.1", "kind": "media_duration", "occurred_at": now(), "data": Object{"duration_ms": 1000}}
	status, out := call(t, s, "POST", "/v1/device/events", adminToken(t, s), event)
	requireStatus(t, 403, status, out)
	status, out = call(t, s, "POST", "/v1/device/events", token, event)
	requireStatus(t, 200, status, out)
	if out["replayed"] != false {
		t.Fatal(out)
	}
	status, out = call(t, s, "POST", "/v1/device/events", token, event)
	requireStatus(t, 200, status, out)
	if out["replayed"] != true {
		t.Fatal(out)
	}
	event["data"] = Object{"duration_ms": 2000}
	status, out = call(t, s, "POST", "/v1/device/events", token, event)
	requireStatus(t, 409, status, out)
	status, out = call(t, s, "POST", "/v1/device/events", token, Object{"logs": []any{}})
	requireStatus(t, 400, status, out)
	key := hash(uid+"\x00"+did) + ":request.1"
	if err := s.put("assistant_request", key, uid, Object{"status": "complete", "runtime_generation": 1, "result": Object{"directives": []Object{{"type": "LaunchApp", "payload": Object{"action_id": "action.1"}}}}}); err != nil {
		t.Fatal(err)
	}
	action := Object{"request_id": "request.1", "action_id": "action.1", "result": "success", "details": Object{"effect": "activity_started"}}
	status, out = call(t, s, "POST", "/v1/device/actions/results", str(foreign, "access_token"), action)
	requireStatus(t, 404, status, out)
	status, out = call(t, s, "POST", "/v1/device/actions/results", token, action)
	requireStatus(t, 200, status, out)
	status, out = call(t, s, "POST", "/v1/device/actions/results", token, action)
	requireStatus(t, 200, status, out)
	if out["replayed"] != true {
		t.Fatal(out)
	}
	action["result"] = "failed"
	status, out = call(t, s, "POST", "/v1/device/actions/results", token, action)
	requireStatus(t, 409, status, out)
	if err := s.put("assistant_request", key+"old", uid, Object{"status": "complete", "runtime_generation": 0, "result": Object{"directives": []Object{{"action_id": "action.1"}}}}); err != nil {
		t.Fatal(err)
	}
	action["request_id"] = "request.1old"
	status, out = call(t, s, "POST", "/v1/device/actions/results", token, action)
	requireStatus(t, 409, status, out)
	var events, actions int
	s.db.QueryRow("SELECT count(*) FROM records WHERE kind='device_event'").Scan(&events)
	s.db.QueryRow("SELECT count(*) FROM records WHERE kind='device_action_result'").Scan(&actions)
	if events != 1 || actions != 1 {
		t.Fatal(events, actions)
	}
}

func TestIndependentPrivateDeviceEventsDiscardDetailsAndPrivateFingerprints(t *testing.T) {
	s := independentTestServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	uid := str(g["user"].(map[string]any), "id")
	did := str(g["device"].(map[string]any), "id")
	status, response := call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
	requireStatus(t, 200, status, response)
	event := Object{
		"event_id": "private.media.1", "kind": "media_duration", "occurred_at": now(),
		"data": Object{"media_title": "PRIVATE_MEDIA_TITLE_SENTINEL", "package_name": "PRIVATE_PACKAGE_SENTINEL", "lat": 10.1, "lon": 106.2},
	}
	status, response = call(t, s, "POST", "/v1/device/events", token, event)
	requireStatus(t, 200, status, response)
	if response["replayed"] != false {
		t.Fatal(response)
	}
	key := hash(uid + "\x00" + did + "\x00" + str(event, "event_id"))
	var raw string
	if err := s.db.QueryRow("SELECT data FROM records WHERE kind='device_event' AND id=? AND owner=?", key, uid).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	for _, sensitive := range []string{"PRIVATE_MEDIA_TITLE_SENTINEL", "PRIVATE_PACKAGE_SENTINEL", "10.1", "106.2", "\"data\""} {
		if strings.Contains(raw, sensitive) {
			t.Fatalf("private event retained %q: %s", sensitive, raw)
		}
	}
	if !strings.Contains(raw, `"private_mode":true`) || !strings.Contains(raw, `"kind":"media_duration"`) {
		t.Fatalf("privacy-safe event tombstone missing: %s", raw)
	}
	status, response = call(t, s, "POST", "/v1/device/events", token, event)
	requireStatus(t, 200, status, response)
	if response["replayed"] != true {
		t.Fatal(response)
	}
	// A private payload is never retained just to detect a changed retry body.
	event["data"] = Object{"media_title": "PRIVATE_DIFFERENT_SENTINEL"}
	status, response = call(t, s, "POST", "/v1/device/events", token, event)
	requireStatus(t, 200, status, response)
	if response["replayed"] != true {
		t.Fatal(response)
	}
	if err := s.db.QueryRow("SELECT data FROM records WHERE kind='device_event' AND id=? AND owner=?", key, uid).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(raw, "PRIVATE_DIFFERENT_SENTINEL") {
		t.Fatalf("private event retry changed its stored tombstone: %s", raw)
	}
}

func TestIndependentPrivateActionsAcceptOwnedReceiptsWithoutRetainingSpeechOrDetails(t *testing.T) {
	s := independentTestServer(t)
	account, foreign := guest(t, s), guest(t, s)
	token := str(account, "access_token")
	uid, did := str(account["user"].(map[string]any), "id"), str(account["device"].(map[string]any), "id")
	status, response := call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
	requireStatus(t, 200, status, response)
	input := Object{"text": "Âm lượng 35%", "mode": "commands", "request_id": "private-volume-request"}
	status, response = call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, status, response)
	directives := objectDirectives(response)
	if len(directives) != 1 || str(directives[0], "type") != "PlayerVolume" {
		t.Fatal("private command did not issue volume action")
	}
	actionID := str(directives[0], "action_id")
	key := hash(uid+"\x00"+did) + ":private-volume-request"
	// Answer bodies can be evicted, while issued random IDs still authorize a
	// late acknowledgement. Turning private mode off must not expose old details.
	if _, err := s.db.Exec("UPDATE records SET data=json_remove(data,'$.result') WHERE kind='assistant_request' AND id=?", key); err != nil {
		t.Fatal(err)
	}
	status, response = call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": false})
	requireStatus(t, 200, status, response)
	receipt := Object{"request_id": "private-volume-request", "action_id": actionID, "result": "success",
		"details": Object{"private_speech": "PRIVATE_ACTION_DETAIL_SENTINEL"}}
	status, response = call(t, s, "POST", "/v1/device/actions/results", str(foreign, "access_token"), receipt)
	requireStatus(t, 404, status, response)
	status, response = call(t, s, "POST", "/v1/device/actions/results", token, receipt)
	requireStatus(t, 200, status, response)
	if response["accepted"] != true || response["replayed"] != false {
		t.Fatal("private receipt was not accepted")
	}
	status, response = call(t, s, "POST", "/v1/device/actions/results", token, receipt)
	requireStatus(t, 200, status, response)
	if response["replayed"] != true {
		t.Fatal("private receipt replay was not detected")
	}
	receipt["details"] = Object{"private_speech": "different"}
	status, response = call(t, s, "POST", "/v1/device/actions/results", token, receipt)
	requireStatus(t, 409, status, response)
	receipt["action_id"] = "not-issued"
	status, response = call(t, s, "POST", "/v1/device/actions/results", token, receipt)
	requireStatus(t, 404, status, response)
	status, response = call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 409, status, response)
	rows, err := s.db.Query("SELECT data FROM records WHERE kind IN ('assistant_request','device_action_result','history','ai_conversation')")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var raw string
		if err := rows.Scan(&raw); err != nil {
			t.Fatal(err)
		}
		if strings.Contains(raw, "PRIVATE_ACTION_DETAIL_SENTINEL") || strings.Contains(raw, "Âm lượng 35%") || strings.Contains(raw, "adjust_percentage") {
			t.Fatal("private action transcript, arguments or receipt details were retained")
		}
	}
}

func TestIndependentEvictedActionReceiptsHonorCurrentPrivacyAndRuntimeGeneration(t *testing.T) {
	s := independentTestServer(t)
	account := guest(t, s)
	token := str(account, "access_token")
	uid, did := str(account["user"].(map[string]any), "id"), str(account["device"].(map[string]any), "id")
	status, response := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "Giảm âm lượng", "mode": "commands", "request_id": "evicted-action"})
	requireStatus(t, 200, status, response)
	id := str(objectDirectives(response)[0], "action_id")
	key := hash(uid+"\x00"+did) + ":evicted-action"
	if _, err := s.db.Exec("UPDATE records SET data=json_remove(data,'$.result') WHERE kind='assistant_request' AND id=?", key); err != nil {
		t.Fatal(err)
	}
	status, response = call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
	requireStatus(t, 200, status, response)
	receipt := Object{"request_id": "evicted-action", "action_id": id, "result": "failed", "details": Object{"note": "PRIVATE_LATE_DETAIL"}}
	status, response = call(t, s, "POST", "/v1/device/actions/results", token, receipt)
	requireStatus(t, 200, status, response)
	var raw string
	if err := s.db.QueryRow("SELECT data FROM records WHERE kind='device_action_result'").Scan(&raw); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(raw, "PRIVATE_LATE_DETAIL") || strings.Contains(raw, "details") {
		t.Fatal("new privacy selection was ignored for a late receipt")
	}
	cfg := runtimeObject(t, s)
	cfg["generation"] = integer(cfg, "generation", 1) + 1
	if err := s.put("config", "runtime", "", cfg); err != nil {
		t.Fatal(err)
	}
	status, response = call(t, s, "POST", "/v1/device/actions/results", token, receipt)
	requireStatus(t, 409, status, response)
	if str(response["error"].(map[string]any), "code") != "DEVICE_EVENT_RETIRED" {
		t.Fatal("old private receipt escaped the generation boundary")
	}
}

func TestSpeechHealthRequiresLoadedReadinessAndRejectsRedirects(t *testing.T) {
	for _, entry := range []struct {
		name     string
		response Object
		healthy  bool
	}{
		{"configuration_only", Object{"configured": true}, false},
		{"status_ok_only", Object{"status": "ok"}, false},
		{"not_loaded", Object{"ready": true, "model_loaded": false}, false},
		{"failed_dependency", Object{"ready": true, "checks": Object{"model": Object{"healthy": false}}}, false},
		{"explicit_failure", Object{"ready": true, "status": "error"}, false},
		{"explicit_ready", Object{"ready": true, "model_loaded": true}, true},
		{"loaded_ok", Object{"status": "ok", "model_loaded": true}, true},
	} {
		t.Run(entry.name, func(t *testing.T) {
			worker := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { send(w, 200, entry.response) }))
			defer worker.Close()
			check := speechWorkerHealth(context.Background(), worker.URL)
			if (check["healthy"] == true) != entry.healthy {
				t.Fatal(check)
			}
		})
	}
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "https://example.test", http.StatusTemporaryRedirect)
	}))
	defer redirect.Close()
	if speechWorkerHealth(context.Background(), redirect.URL)["healthy"] == true {
		t.Fatal("redirect accepted")
	}
}

func TestIndependentPublicReadyAndAuthenticatedCapabilities(t *testing.T) {
	s := independentTestServer(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	r := httptest.NewRequest("GET", "/v1/ready", nil).WithContext(ctx)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != 503 || !strings.Contains(w.Body.String(), `"ready":false`) {
		t.Fatal(w.Code, w.Body.String())
	}
	status, out := call(t, s, "GET", "/v1/capabilities", "", nil)
	requireStatus(t, 401, status, out)
	status, out = call(t, s, "GET", "/v1/capabilities", str(guest(t, s), "access_token"), nil)
	requireStatus(t, 200, status, out)
	contactsFound := false
	for _, value := range out["items"].([]any) {
		item := value.(map[string]any)
		if str(item, "id") == "traffic_violations" && str(item, "status") != "BLOCKED" {
			t.Fatal(item)
		}
		if str(item, "id") == "contacts" {
			contactsFound = true
			if str(item, "status") != "CLIENT_VALIDATION_REQUIRED" || str(item, "reason") != "android_contact_resolver_requires_permission_and_confirmation" {
				t.Fatal("Contact support must describe the Android adapter without claiming device acceptance", item)
			}
		}
	}
	if !contactsFound || str(basicPhoneDirective("gọi cho Nguyễn An"), "type") != "PhoneCall" {
		t.Fatal("Contact capability must correspond to a routable, device-confirmed action")
	}
	if _, err := openServer(Config{DBPath: filepath.Join(t.TempDir(), "invalid.db"), RuntimeMode: "legacy_tn"}); err == nil {
		t.Fatal("production legacy mode accepted")
	}
	t.Setenv("TN_RUNTIME_MODE", "")
	if configEnv().RuntimeMode != "independent" {
		t.Fatal("production did not default independent")
	}
	_ = time.Second
}
