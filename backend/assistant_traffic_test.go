package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func trafficUploadFixture(t *testing.T, s *Server, callback func(http.ResponseWriter, *http.Request)) {
	t.Helper()
	t.Setenv("TRAFFIC_TEST_AUTH", "fixture-auth")
	t.Setenv("TRAFFIC_TEST_TOKEN", "fixture-device-token")
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		if r.URL.Path != "/nlu" {
			callback(w, r)
			return
		}
		var event Object
		json.Unmarshal([]byte(r.FormValue("event")), &event)
		payload, _ := event["payload"].(map[string]any)
		send(w, 200, Object{"code": 0, "directives": []Object{{"action_code": "CheckTrafficViolation", "action_id": "violation-action", "required": []any{}, "payload": Object{"registrationNumber": "TEST-69", "queryEnabled": str(payload, "message") != "cached only"}}}})
	}))
	t.Cleanup(fixture.Close)
	if err := s.saveProviders(Object{"nlu": Object{"mode": "legacy_tn", "url": fixture.URL + "/nlu", "token_env": "TRAFFIC_TEST_AUTH", "device_token_env": "TRAFFIC_TEST_TOKEN", "uid": "42"}}); err != nil {
		t.Fatal(err)
	}
}

func TestTrafficUploadPinsOriginalPlateAndNeverRepeatsChangedOrCrossAccountRows(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	trafficUploadFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/api/android-dvd/update-traffic-violations" || r.FormValue("registration_number") != "TEST-69" || r.Header.Get("Authorization") != "Token fixture-auth UID 42" {
			t.Error("traffic upload lost Original path/plate/auth contract")
		}
		var rows []Object
		if json.Unmarshal([]byte(r.FormValue("violations")), &rows) != nil || len(rows) != 1 || str(rows[0], "offence") != "TEST synthetic offence" || len(rows[0]) != 5 {
			t.Error("violations did not preserve normalized five-field array")
		}
		send(w, 200, Object{"code": 0})
	})
	account, other := guest(t, s), guest(t, s)
	token := str(account, "access_token")
	locationTestCommand(t, s, token, "TEST traffic query", "traffic-source")
	input := Object{"request_id": "traffic-source", "action_id": "violation-action", "violations": []Object{{"offence": "TEST synthetic offence"}}}
	code, result := call(t, s, "POST", "/v1/assistant/traffic-violations", token, input)
	requireStatus(t, 200, code, result)
	if result["transport_ack"] != true || str(result, "status") != "submitted" || result["storage_confirmed"] != nil {
		t.Fatal("transport response claimed unverified provider storage")
	}
	code, result = call(t, s, "POST", "/v1/assistant/traffic-violations", token, input)
	requireStatus(t, 200, code, result)
	if result["replayed"] != true || calls.Load() != 1 {
		t.Fatal("retry uploaded violations twice")
	}
	code, result = call(t, s, "POST", "/v1/assistant/traffic-violations", str(other, "access_token"), input)
	requireStatus(t, 404, code, result)
	input["violations"] = []Object{{"offence": "different result"}}
	code, result = call(t, s, "POST", "/v1/assistant/traffic-violations", token, input)
	requireStatus(t, 409, code, result)
	input["registration_number"] = "another-plate"
	code, result = call(t, s, "POST", "/v1/assistant/traffic-violations", token, input)
	requireStatus(t, 400, code, result)
	if calls.Load() != 1 {
		t.Fatal("untrusted or conflicting traffic data reached provider")
	}
}

func TestTrafficUploadRejectsMalformedRowsAndCachedOnlyAction(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	trafficUploadFixture(t, s, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); send(w, 200, Object{"code": 0}) })
	account := guest(t, s)
	token := str(account, "access_token")
	locationTestCommand(t, s, token, "cached only", "cached-traffic")
	input := Object{"request_id": "cached-traffic", "action_id": "violation-action", "violations": []Object{}}
	code, result := call(t, s, "POST", "/v1/assistant/traffic-violations", token, input)
	requireStatus(t, 409, code, result)
	tooMany := make([]Object, 501)
	for index := range tooMany {
		tooMany[index] = Object{}
	}
	for _, invalid := range []any{nil, "[]", Object{}, []any{nil}, []Object{{"offence": 42}}, []Object{{"url": "http://evil.invalid"}}, []Object{{"offence": strings.Repeat("x", 2001)}}, tooMany} {
		input["violations"] = invalid
		code, result = call(t, s, "POST", "/v1/assistant/traffic-violations", token, input)
		requireStatus(t, 400, code, result)
	}
	if calls.Load() != 0 {
		t.Fatal("invalid traffic array reached upstream")
	}
}

func TestTrafficPrivateUploadRetainsNoPlateOrRowsAndFailureIsTerminal(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	trafficUploadFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		send(w, 200, Object{"code": 9})
	})
	account := guest(t, s)
	token := str(account, "access_token")
	code, result := call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
	requireStatus(t, 200, code, result)
	locationTestCommand(t, s, token, "PRIVATE_TRAFFIC_QUESTION", "private-traffic")
	input := Object{"request_id": "private-traffic", "action_id": "violation-action", "violations": []Object{{"offence": "PRIVATE_TRAFFIC_RESULT"}}}
	code, result = call(t, s, "POST", "/v1/assistant/traffic-violations", token, input)
	requireStatus(t, 502, code, result)
	code, result = call(t, s, "POST", "/v1/assistant/traffic-violations", token, input)
	requireStatus(t, 409, code, result)
	rows, err := s.db.Query("SELECT data FROM records")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var value string
		rows.Scan(&value)
		if strings.Contains(value, "PRIVATE_TRAFFIC_QUESTION") || strings.Contains(value, "PRIVATE_TRAFFIC_RESULT") || strings.Contains(value, "TEST-69") {
			t.Fatal("private traffic question/plate/rows persisted")
		}
	}
	if calls.Load() != 1 {
		t.Fatal("failed traffic upload was repeated")
	}
}
