package main

import (
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func locationTestFixture(t *testing.T, s *Server, callback func(http.ResponseWriter, *http.Request)) *httptest.Server {
	t.Helper()
	t.Setenv("LOCATION_FIXTURE_AUTH", "fixture-auth")
	t.Setenv("LOCATION_FIXTURE_DEVICE_TOKEN", "fixture-device-token")
	t.Setenv("LOCATION_FIXTURE_DEVICE", "fixture-device")
	t.Setenv("LOCATION_FIXTURE_SECRET", "fixture-secret")
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		if r.URL.Path != "/nlu" {
			callback(w, r)
			return
		}
		var event Object
		json.Unmarshal([]byte(r.FormValue("event")), &event)
		payload, _ := event["payload"].(map[string]any)
		path := "/address"
		if strings.HasPrefix(str(payload, "message"), "path:") {
			path = strings.TrimPrefix(str(payload, "message"), "path:")
		}
		if str(payload, "message") == "compound" || str(payload, "message") == "compound-by-name" {
			kind := "resolve_geo"
			if str(payload, "message") == "compound-by-name" {
				kind = "by_name"
			}
			send(w, 200, Object{"code": 0, "directives": []Object{{"action_code": "CompoundCommand", "action_id": "map-action", "required": []any{}, "payload": Object{"map": Object{"type": kind, "data": Object{"data": Object{"searchLocationRequest": Object{"pathUrl": "/address", "payload": Object{"query": "compound fixture"}}}}, "execute_apps": []string{"com.google.android.apps.maps"}}}}}})
			return
		}
		send(w, 200, Object{"code": 0, "session_key": "server-session", "directives": []Object{{"action_code": "MapDirectionGGParser", "action_id": "map-action", "required": []any{}, "payload": Object{"data": Object{
			"searchLocationRequest": Object{"pathUrl": path, "timeout": 5000, "payload": Object{"query": "fixture location", "options": Object{"nearby": true}}},
			"ggParserApiRequest":    Object{"pathUrl": "/gg", "timeout": 5000, "payload": Object{"echo": "fixture marker"}},
		}}}}})
	}))
	config := Object{"nlu": Object{"mode": "legacy_tn", "url": fixture.URL + "/nlu", "token_env": "LOCATION_FIXTURE_AUTH", "device_token_env": "LOCATION_FIXTURE_DEVICE_TOKEN", "secret_env": "LOCATION_FIXTURE_SECRET", "uid": "42", "client_app_type": "fixture-app", "version": "69", "options": Object{"device_id_env": "LOCATION_FIXTURE_DEVICE", "form_fields": Object{"device_type": "ANDROID_CAR_HEAD"}}}}
	if err := s.saveProviders(config); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(fixture.Close)
	return fixture
}

func TestLocationCompoundUsesOnlyStoredResolveGeoMapChild(t *testing.T) {
	s := testServer(t)
	var count atomic.Int32
	locationTestFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		if r.FormValue("query") != "compound fixture" {
			t.Error("compound callback lost nested Original payload")
		}
		send(w, 200, Object{"code": 0, "data": Object{"lat": 10.0, "lon": 106.0}})
	})
	account := guest(t, s)
	token := str(account, "access_token")
	locationTestCommand(t, s, token, "compound", "compound-request")
	code, result := call(t, s, "POST", "/v1/assistant/location", token, Object{"request_id": "compound-request", "action_id": "map-action", "operation": "address"})
	requireStatus(t, 200, code, result)
	locationTestCommand(t, s, token, "compound-by-name", "compound-no-callback")
	code, result = call(t, s, "POST", "/v1/assistant/location", token, Object{"request_id": "compound-no-callback", "action_id": "map-action", "operation": "address"})
	requireStatus(t, 404, code, result)
	if count.Load() != 1 {
		t.Fatal("compound by_name acquired a location callback")
	}
}

func locationTestCommand(t *testing.T, s *Server, token, text, requestID string) {
	t.Helper()
	code, response := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": text, "mode": "commands", "request_id": requestID})
	requireStatus(t, 200, code, response)
	if _, leaked := response["_nlu_provider_binding"]; leaked {
		t.Fatal("internal provider binding exposed")
	}
}

func TestLocationOriginalContractReplayOwnershipAndParserValidation(t *testing.T) {
	s := testServer(t)
	var addressCalls, ggCalls atomic.Int32
	locationTestFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Token fixture-auth UID 42" || r.FormValue("device_token") != "fixture-device-token" || r.FormValue("device_id") != "fixture-device" {
			t.Error("location callback lost bound provider authentication")
		}
		md := func(value string) string { sum := md5.Sum([]byte(value)); return hex.EncodeToString(sum[:]) }
		if r.FormValue("hash") != md("fixture-secret"+md("fixture-app"+r.FormValue("timestamp"))) {
			t.Error("location common signature mismatch")
		}
		switch r.URL.Path {
		case "/address":
			addressCalls.Add(1)
			if r.FormValue("query") != "fixture location" || r.FormValue("options") != `{"nearby":true}` {
				t.Error("address payload does not match Original toMapString")
			}
			send(w, 200, Object{"code": 0, "data": Object{"lat": 10.1, "lon": 106.1, "name": "Fixture place", "fullAddress": "Fixture address"}})
		case "/gg":
			ggCalls.Add(1)
			if r.FormValue("echo_result") != `{"echo":"fixture marker"}` || !strings.Contains(r.FormValue("parser_result"), `"lat":10.2`) {
				t.Error("GG callback did not preserve echo/parser_result JSON")
			}
			send(w, 200, Object{"code": 0, "data": Object{"speech": Object{"utter_text": "Fixture direction speech"}, "direction": Object{"destination": Object{"lat": 10.2, "lon": 106.2}, "package_name": []string{"com.google.android.apps.maps"}, "googleMapsPreview": Object{"enabled": true}}}})
		}
	})
	first, second := guest(t, s), guest(t, s)
	token := str(first, "access_token")
	locationTestCommand(t, s, token, "fixture direction", "location-source")
	input := Object{"request_id": "location-source", "action_id": "map-action", "operation": "address"}
	code, address := call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 200, code, address)
	if str(address, "status") != "resolved" || address["address"].(map[string]any)["lat"] != 10.1 {
		t.Fatal("address not resolved from provider")
	}
	code, replay := call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 200, code, replay)
	if replay["replayed"] != true || addressCalls.Load() != 1 {
		t.Fatal("address callback retry repeated upstream")
	}
	code, response := call(t, s, "POST", "/v1/assistant/location", str(second, "access_token"), input)
	requireStatus(t, 404, code, response)
	// Changing the second session's owner simulates same-user QR linking, but
	// does not authorize this other device to consume the first device's action.
	uid := str(first["user"].(map[string]any), "id")
	did := str(second["device"].(map[string]any), "id")
	s.db.Exec("UPDATE devices SET user_id=? WHERE id=?", uid, did)
	s.db.Exec("UPDATE sessions SET user_id=? WHERE device_id=?", uid, did)
	code, response = call(t, s, "POST", "/v1/assistant/location", str(second, "access_token"), input)
	requireStatus(t, 404, code, response)
	input["operation"] = "gg_parser"
	input["parser_result"] = Object{"lat": 10.2, "lon": 106.2, "name": "Fixture parsed", "rating": 4.5, "is_closed": false}
	code, response = call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 200, code, response)
	if str(response, "status") != "resolved" || response["direction"].(map[string]any)["googleMapsPreview"] == nil {
		t.Fatal("resolved direction metadata lost")
	}
	input["parser_result"] = Object{"lat": 10.3, "lon": 106.2}
	code, response = call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 409, code, response)
	input["parser_result"] = Object{"lat": 200, "lon": 106.2}
	code, response = call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 400, code, response)
	if ggCalls.Load() != 1 {
		t.Fatal("invalid or conflicting parser result reached provider")
	}
}

func TestLocationRejectsUnboundURLsProviderChangesAndExpiredActions(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	locationTestFixture(t, s, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.WriteHeader(500) })
	account := guest(t, s)
	token := str(account, "access_token")
	for index, path := range []string{"http://127.0.0.1:1/private", "https://evil.invalid/steal", "//evil.invalid/steal", "http://user:password@localhost/private", "/location?token=bad", "/location#secret", `\evil.invalid\private`} {
		requestID := "ssrf-" + string(rune('a'+index))
		locationTestCommand(t, s, token, "path:"+path, requestID)
		code, response := call(t, s, "POST", "/v1/assistant/location", token, Object{"request_id": requestID, "action_id": "map-action", "operation": "address"})
		requireStatus(t, 409, code, response)
	}
	locationTestCommand(t, s, token, "fixture direction", "provider-change")
	input := Object{"request_id": "provider-change", "action_id": "map-action", "operation": "address", "pathUrl": "/admin"}
	code, response := call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 400, code, response)
	delete(input, "pathUrl")
	t.Setenv("LOCATION_FIXTURE_AUTH", "another-fixture-account")
	code, response = call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 409, code, response)
	t.Setenv("LOCATION_FIXTURE_AUTH", "fixture-auth")
	s.db.Exec("UPDATE records SET data=json_set(data,'$.created_at','2000-01-01T00:00:00Z') WHERE kind='assistant_request'")
	code, response = call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 409, code, response)
	if calls.Load() != 0 {
		t.Fatal("rejected URL, changed provider or expired action reached upstream")
	}
}

func TestLocationPrivateCallbacksRemainVolatileAndReplayOnce(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	locationTestFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		send(w, 200, Object{"code": 0, "data": Object{"lat": 10.1, "lon": 106.1, "name": "PRIVATE_DESTINATION"}})
	})
	account := guest(t, s)
	token := str(account, "access_token")
	code, response := call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
	requireStatus(t, 200, code, response)
	locationTestCommand(t, s, token, "PRIVATE_LOCATION_QUESTION", "private-location")
	input := Object{"request_id": "private-location", "action_id": "map-action", "operation": "address"}
	for index := 0; index < 2; index++ {
		code, response = call(t, s, "POST", "/v1/assistant/location", token, input)
		requireStatus(t, 200, code, response)
		if response["replayed"] != (index == 1) {
			t.Fatal("private callback replay status mismatch")
		}
	}
	rows, err := s.db.Query("SELECT data FROM records")
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var value string
		rows.Scan(&value)
		if strings.Contains(value, "PRIVATE_LOCATION_QUESTION") || strings.Contains(value, "PRIVATE_DESTINATION") || strings.Contains(value, "searchLocationRequest") {
			t.Fatal("private location content persisted")
		}
	}
	rows.Close()
	s.locationMu.Lock()
	for key, value := range s.privateLocations {
		value.Expires = time.Now().Add(-time.Second)
		s.privateLocations[key] = value
	}
	s.locationMu.Unlock()
	code, response = call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 404, code, response)
	if calls.Load() != 1 {
		t.Fatal("private callback replayed upstream")
	}
}

func TestLocationConcurrentCallbackAndProviderFailureNeverRepeat(t *testing.T) {
	s := testServer(t)
	started := make(chan struct{})
	release := make(chan struct{})
	var calls atomic.Int32
	locationTestFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		close(started)
		<-release
		w.WriteHeader(502)
	})
	account := guest(t, s)
	token := str(account, "access_token")
	locationTestCommand(t, s, token, "fixture direction", "concurrent-location")
	input := Object{"request_id": "concurrent-location", "action_id": "map-action", "operation": "address"}
	finished := make(chan int, 1)
	go func() {
		code, _ := call(t, s, "POST", "/v1/assistant/location", token, input)
		finished <- code
	}()
	<-started
	code, response := call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 409, code, response)
	close(release)
	if code = <-finished; code != 502 {
		t.Fatalf("provider failure returned %d", code)
	}
	code, response = call(t, s, "POST", "/v1/assistant/location", token, input)
	requireStatus(t, 409, code, response)
	if calls.Load() != 1 {
		t.Fatal("concurrent/interrupted callback was sent twice")
	}
}

func TestLocationResponseRejectsMalformedCoordinatesAndPreservesSpeechOnly(t *testing.T) {
	for _, response := range []Object{
		{"code": 0, "data": Object{"lat": -1.0, "lon": -1.0}},
		{"code": 0, "data": Object{"lat": 100.0, "lon": 106.0}},
		{"code": 0, "data": Object{"lat": "10", "lon": 106.0}},
		{"code": 1, "data": Object{"lat": 10.0, "lon": 106.0}},
	} {
		if _, err := normalizeLocationResponse(response, "address"); err == nil {
			t.Fatal("invalid address accepted")
		}
	}
	result, err := normalizeLocationResponse(Object{"code": 1, "data": Object{"speech": Object{"utter_text": "Không tìm được địa điểm"}}}, "gg_parser")
	if err != nil || str(result, "status") != "speech" || result["direction"] != nil {
		t.Fatal("Original code1 speech-only response became navigation success")
	}
	if _, err = normalizeLocationResponse(Object{"code": 0, "data": Object{"direction": Object{"destination": Object{"lat": 10.0, "lon": 106.0}}}}, "gg_parser"); err == nil {
		t.Fatal("missing required Original speech accepted")
	}
}
