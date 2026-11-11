package main

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Each test uses an isolated SQLite database and an in-process HTTP provider.
// None of these fixtures reads runtime configuration or calls a real provider.
func callAPIFixture(t *testing.T, s *Server, action func(string) Object, callback http.HandlerFunc) *httptest.Server {
	t.Helper()
	t.Setenv("CALL_API_FIXTURE_AUTH", "fixture-auth")
	t.Setenv("CALL_API_FIXTURE_DEVICE_TOKEN", "fixture-device-token")
	t.Setenv("CALL_API_FIXTURE_DEVICE", "fixture-device")
	t.Setenv("CALL_API_FIXTURE_SECRET", "fixture-secret")
	t.Setenv("CALL_API_FIXTURE_HEADER", "fixture-header")
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Error("fixture request form was invalid")
		}
		if r.URL.Path != "/nlu" {
			callback(w, r)
			return
		}
		var event Object
		json.Unmarshal([]byte(r.FormValue("event")), &event)
		payload, _ := event["payload"].(map[string]any)
		send(w, 200, Object{"code": 0, "directives": []Object{{"action_code": "CallApi", "action_id": "callback-action", "required": []any{}, "payload": action(str(payload, "message"))}}})
	}))
	t.Cleanup(fixture.Close)
	if err := s.saveProviders(Object{"nlu": Object{"mode": "legacy_tn", "url": fixture.URL + "/nlu", "token_env": "CALL_API_FIXTURE_AUTH", "device_token_env": "CALL_API_FIXTURE_DEVICE_TOKEN", "secret_env": "CALL_API_FIXTURE_SECRET", "header_env": Object{"X-Api-Key": "CALL_API_FIXTURE_HEADER"}, "uid": "42", "client_app_type": "fixture-app", "version": "82", "options": Object{"device_id_env": "CALL_API_FIXTURE_DEVICE", "form_fields": Object{"device_type": "ANDROID_CAR_HEAD"}}}}); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func callAPIAction(wait bool) Object {
	return Object{"pathUrl": "/callback", "payload": Object{"message": "fixture payload", "nested": Object{"enabled": true}, "items": []any{1, "two"}}, "timeout": 1000, "wait_result": wait}
}

func callAPISource(t *testing.T, s *Server, token, requestID, text string) Object {
	t.Helper()
	status, response := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": text, "mode": "commands", "request_id": requestID})
	requireStatus(t, 200, status, response)
	return response
}

func callAPIInput(requestID string) Object {
	return Object{"request_id": requestID, "action_id": "callback-action"}
}

func waitCallAPIWorkers(t *testing.T, s *Server) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		s.locationMu.Lock()
		busy := len(s.callAPIWorkers)
		s.locationMu.Unlock()
		if busy == 0 {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("bounded CallApi worker did not finish")
}

func TestCallAPIOriginalFormSuccessReplayAndNoResponseInjection(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	callAPIFixture(t, s, func(string) Object { return callAPIAction(true) }, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Method != "POST" || r.URL.Path != "/callback" || !strings.HasPrefix(r.Header.Get("Content-Type"), "application/x-www-form-urlencoded") {
			t.Error("CallApi did not use the Original POST form contract")
		}
		if err := r.ParseForm(); err != nil {
			t.Error("CallApi callback form was invalid")
		}
		var payload Object
		if json.Unmarshal([]byte(r.FormValue("payload")), &payload) != nil || jsonText(payload) != jsonText(callAPIAction(true)["payload"]) {
			t.Error("CallApi lost its nested JSON payload")
		}
		if r.FormValue("message") != "" || r.FormValue("nested") != "" {
			t.Error("CallApi flattened payload instead of sending one JSON form field")
		}
		if r.Header.Get("Authorization") != "Token fixture-auth UID 42" || r.FormValue("device_token") != "fixture-device-token" || r.FormValue("device_id") != "fixture-device" || r.FormValue("device_type") != "ANDROID_CAR_HEAD" {
			t.Error("CallApi lost configured authentication/common fields")
		}
		digest := func(value string) string { sum := md5.Sum([]byte(value)); return hex.EncodeToString(sum[:]) }
		if r.FormValue("hash") != digest("fixture-secret"+digest("fixture-app"+r.FormValue("timestamp"))) {
			t.Error("CallApi signature did not match common Original fields")
		}
		send(w, 200, Object{"code": 0, "private": "PRIVATE_UPSTREAM_RESPONSE", "directives": []Object{{"type": "OpenApp", "payload": Object{"package_name": "injected.app"}}}})
	})
	account := guest(t, s)
	token := str(account, "access_token")
	source := callAPISource(t, s, token, "call-success", "fixture callback request")
	directives := source["directives"].([]any)
	action := directives[0].(map[string]any)
	payload := action["payload"].(map[string]any)
	if str(action, "type") != "CallApi" || str(payload, "pathUrl") != "/callback" || payload["wait_result"] != true || payload["payload"] == nil || str(payload, "action_id") != "callback-action" {
		t.Fatal("NLU normalization dropped Original CallApi fields")
	}
	for index := 0; index < 2; index++ {
		status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("call-success"))
		requireStatus(t, 200, status, response)
		if str(response, "result") != "success" || str(response, "status") != "completed" || response["upstream_confirmed"] != true || response["replayed"] != (index == 1) {
			t.Fatal("waiting CallApi success/replay acknowledgement mismatch")
		}
		if strings.Contains(jsonText(response), "PRIVATE_UPSTREAM_RESPONSE") || response["directives"] != nil {
			t.Fatal("CallApi injected upstream response into Android")
		}
	}
	if calls.Load() != 1 {
		t.Fatal("CallApi replay repeated upstream")
	}
}

func TestCallAPIFailureOutcomesRemainTerminal(t *testing.T) {
	for _, test := range []struct {
		name string
		http int
		body string
	}{
		{"nonzero", 200, `{"code":7}`},
		{"missing", 200, `{"data":"no code"}`},
		{"fraction_nonzero", 200, `{"code":1.5}`},
		{"string_nonzero", 200, `{"code":"-1.5"}`},
		{"boolean", 200, `{"code":false}`},
		{"nan_string", 200, `{"code":"NaN"}`},
		{"infinity_string", 200, `{"code":"Infinity"}`},
		{"invalid", 200, `not json`},
		{"array", 200, `[]`},
		{"http", 503, `{"code":0}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			s := testServer(t)
			var calls atomic.Int32
			callAPIFixture(t, s, func(string) Object { return callAPIAction(true) }, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				w.WriteHeader(test.http)
				fmt.Fprint(w, test.body)
			})
			token := str(guest(t, s), "access_token")
			callAPISource(t, s, token, "call-failed", "fixture failed callback")
			for index := 0; index < 2; index++ {
				status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("call-failed"))
				requireStatus(t, 200, status, response)
				if str(response, "result") != "fail" || response["upstream_confirmed"] != false || response["replayed"] != (index == 1) {
					t.Fatal("failed CallApi became success or executable again")
				}
			}
			if calls.Load() != 1 {
				t.Fatal("ambiguous/failed CallApi repeated upstream")
			}
		})
	}
}

func TestCallAPIOriginalGetIntFiniteStatusCoercions(t *testing.T) {
	for _, test := range []struct {
		name  string
		value any
		want  int32
		valid bool
	}{
		{"zero", float64(0), 0, true},
		{"string_zero", "0", 0, true},
		{"positive_fraction", float64(0.5), 0, true},
		{"negative_fraction", float64(-0.9), 0, true},
		{"fraction_string", " -0.9 ", 0, true},
		{"nonzero_fraction", float64(1.5), 1, true},
		{"negative_nonzero_fraction", "-1.5", -1, true},
		{"scientific", "1e-2", 0, true},
		{"hex", "0x0p0", 0, true},
		{"java_suffix", "0.5F", 0, true},
		{"positive_saturation", float64(1e100), math.MaxInt32, true},
		{"negative_saturation", "-1e100", math.MinInt32, true},
		{"missing", nil, 0, false},
		{"bool", false, 0, false},
		{"object", Object{}, 0, false},
		{"nonnumeric", "success", 0, false},
		{"underscore", "0_0", 0, false},
		{"nan", math.NaN(), 0, false},
		{"nan_string", "NaN", 0, false},
		{"infinity", math.Inf(1), 0, false},
		{"infinity_string", "Infinity", 0, false},
		{"overlong", strings.Repeat("0", 257), 0, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			got, valid := originalCallAPIStatus(test.value)
			if valid != test.valid || got != test.want {
				t.Fatalf("getInt status mismatch: got %d/%v want %d/%v", got, valid, test.want, test.valid)
			}
		})
	}
}

func TestCallAPIWaitingSuccessAcceptsOriginalFiniteStatusCoercion(t *testing.T) {
	s := testServer(t)
	callAPIFixture(t, s, func(text string) Object {
		action := callAPIAction(true)
		action["payload"] = Object{"status_case": text}
		return action
	}, func(w http.ResponseWriter, r *http.Request) {
		var payload Object
		json.Unmarshal([]byte(r.FormValue("payload")), &payload)
		switch str(payload, "status_case") {
		case "fixture string zero":
			send(w, 200, Object{"code": "0"})
		case "fixture positive fraction":
			send(w, 200, Object{"code": 0.5})
		default:
			send(w, 200, Object{"code": -0.9})
		}
	})
	token := str(guest(t, s), "access_token")
	for index, text := range []string{"fixture string zero", "fixture positive fraction", "fixture negative fraction"} {
		id := fmt.Sprintf("coerced-status-%d", index)
		callAPISource(t, s, token, id, text)
		status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput(id))
		requireStatus(t, 200, status, response)
		if str(response, "result") != "success" || response["upstream_confirmed"] != true {
			t.Fatal("finite Original getInt zero status was not accepted")
		}
	}
}

func TestCallAPIRejectsClientInjectionAndUnboundOrigins(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	callAPIFixture(t, s, func(text string) Object {
		action := callAPIAction(true)
		if strings.HasPrefix(text, "path:") {
			action["pathUrl"] = strings.TrimPrefix(text, "path:")
		}
		return action
	}, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); send(w, 200, Object{"code": 0}) })
	token := str(guest(t, s), "access_token")
	callAPISource(t, s, token, "injection-source", "fixture callback injection")
	for _, field := range []string{"pathUrl", "payload", "timeout", "wait_result", "package_name", "operation"} {
		input := callAPIInput("injection-source")
		input[field] = "/admin"
		status, response := call(t, s, "POST", "/v1/assistant/call-api", token, input)
		requireStatus(t, 400, status, response)
	}
	for index, path := range []string{"http://127.0.0.1:1/private", "https://foreign.invalid/private", "//foreign.invalid/private", "http://user:password@localhost/private", "/callback?private=1", "/callback#private", `\foreign.invalid\private`} {
		id := fmt.Sprintf("call-path-%d", index)
		callAPISource(t, s, token, id, "path:"+path)
		status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput(id))
		requireStatus(t, 409, status, response)
	}
	if calls.Load() != 0 {
		t.Fatal("client-injected or foreign URL received a request")
	}
}

func TestCallAPIRedirectCannotReplayCredentialsOrPayload(t *testing.T) {
	s := testServer(t)
	var redirected atomic.Int32
	foreign := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { redirected.Add(1); send(w, 200, Object{"code": 0}) }))
	defer foreign.Close()
	callAPIFixture(t, s, func(string) Object { return callAPIAction(true) }, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", foreign.URL+"/private")
		w.WriteHeader(http.StatusTemporaryRedirect)
	})
	token := str(guest(t, s), "access_token")
	callAPISource(t, s, token, "call-redirect", "fixture callback redirect")
	status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("call-redirect"))
	requireStatus(t, 200, status, response)
	if str(response, "result") != "fail" || redirected.Load() != 0 {
		t.Fatal("CallApi followed a credential-bearing redirect")
	}
}

func TestCallAPIBindsAccountDeviceActionAgeAndProvider(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	callAPIFixture(t, s, func(string) Object { return callAPIAction(true) }, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); send(w, 200, Object{"code": 0}) })
	first, second := guest(t, s), guest(t, s)
	token, other := str(first, "access_token"), str(second, "access_token")
	callAPISource(t, s, token, "bound-call", "fixture bound callback")
	status, response := call(t, s, "POST", "/v1/assistant/call-api", other, callAPIInput("bound-call"))
	requireStatus(t, 404, status, response)
	uid, did := str(first["user"].(map[string]any), "id"), str(second["device"].(map[string]any), "id")
	s.db.Exec("UPDATE devices SET user_id=? WHERE id=?", uid, did)
	s.db.Exec("UPDATE sessions SET user_id=? WHERE device_id=?", uid, did)
	status, response = call(t, s, "POST", "/v1/assistant/call-api", other, callAPIInput("bound-call"))
	requireStatus(t, 404, status, response)
	input := callAPIInput("bound-call")
	input["action_id"] = "other-action"
	status, response = call(t, s, "POST", "/v1/assistant/call-api", token, input)
	requireStatus(t, 404, status, response)
	t.Setenv("CALL_API_FIXTURE_AUTH", "changed-fixture-auth")
	status, response = call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("bound-call"))
	requireStatus(t, 409, status, response)
	t.Setenv("CALL_API_FIXTURE_AUTH", "fixture-auth")
	t.Setenv("CALL_API_FIXTURE_HEADER", "changed-fixture-header")
	status, response = call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("bound-call"))
	requireStatus(t, 409, status, response)
	t.Setenv("CALL_API_FIXTURE_HEADER", "fixture-header")
	providers, err := s.providers()
	if err != nil {
		t.Fatal(err)
	}
	p := providers["nlu"]
	t.Setenv("CALL_API_FIXTURE_ALTERNATE_HEADER", "fixture-header")
	p.HeaderEnv = map[string]string{"X-Api-Key": "CALL_API_FIXTURE_ALTERNATE_HEADER"}
	var configuration Object
	json.Unmarshal([]byte(jsonText(p)), &configuration)
	if err := s.saveProviders(Object{"nlu": configuration}); err != nil {
		t.Fatal(err)
	}
	status, response = call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("bound-call"))
	requireStatus(t, 409, status, response)
	p.HeaderEnv = map[string]string{"X-Api-Key": "CALL_API_FIXTURE_HEADER"}
	json.Unmarshal([]byte(jsonText(p)), &configuration)
	if err := s.saveProviders(Object{"nlu": configuration}); err != nil {
		t.Fatal(err)
	}
	s.db.Exec("UPDATE records SET data=json_set(data,'$.created_at','2000-01-01T00:00:00Z') WHERE kind='assistant_request'")
	status, response = call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("bound-call"))
	requireStatus(t, 409, status, response)
	if calls.Load() != 0 {
		t.Fatal("unbound, expired or changed-provider action reached upstream")
	}
}

func TestCallAPIPrivatePayloadIsVolatileAndReplayOnce(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	callAPIFixture(t, s, func(string) Object {
		action := callAPIAction(true)
		action["payload"] = Object{"private": "PRIVATE_CALL_API_BODY"}
		return action
	}, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); send(w, 200, Object{"code": 0}) })
	token := str(guest(t, s), "access_token")
	status, response := call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
	requireStatus(t, 200, status, response)
	callAPISource(t, s, token, "private-call", "PRIVATE_CALL_API_QUESTION")
	for index := 0; index < 2; index++ {
		status, response = call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("private-call"))
		requireStatus(t, 200, status, response)
		if str(response, "result") != "success" || response["replayed"] != (index == 1) {
			t.Fatal("private CallApi replay mismatch")
		}
	}
	rows, err := s.db.Query("SELECT data FROM records")
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var data string
		rows.Scan(&data)
		if strings.Contains(data, "PRIVATE_CALL_API_BODY") || strings.Contains(data, "PRIVATE_CALL_API_QUESTION") {
			t.Fatal("private CallApi request or body persisted")
		}
	}
	rows.Close()
	s.locationMu.Lock()
	for key, value := range s.privateLocations {
		value.Expires = time.Now().Add(-time.Second)
		s.privateLocations[key] = value
	}
	s.locationMu.Unlock()
	status, response = call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("private-call"))
	requireStatus(t, 404, status, response)
	if calls.Load() != 1 {
		t.Fatal("expired private CallApi reached upstream again")
	}
}

func TestCallAPIWaitingTimeoutAndCancellationAreTerminal(t *testing.T) {
	for _, cancelled := range []bool{false, true} {
		t.Run(fmt.Sprint(cancelled), func(t *testing.T) {
			s := testServer(t)
			var calls atomic.Int32
			started, released := make(chan struct{}), make(chan struct{})
			callAPIFixture(t, s, func(string) Object { action := callAPIAction(true); action["timeout"] = 30; return action }, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				close(started)
				<-r.Context().Done()
				close(released)
			})
			token := str(guest(t, s), "access_token")
			callAPISource(t, s, token, "call-timeout", "fixture bounded callback")
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if cancelled {
				go func() { <-started; cancel() }()
			}
			r := httptest.NewRequest("POST", "/v1/assistant/call-api", strings.NewReader(jsonText(callAPIInput("call-timeout")))).WithContext(ctx)
			r.Header.Set("Authorization", "Bearer "+token)
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, r)
			var response Object
			json.Unmarshal(w.Body.Bytes(), &response)
			if w.Code != 200 || str(response, "result") != "fail" || response["upstream_confirmed"] != false {
				t.Fatal("waiting CallApi timeout/cancel was not failure")
			}
			select {
			case <-released:
			case <-time.After(time.Second):
				t.Fatal("upstream request context was not cancelled")
			}
			status, replay := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("call-timeout"))
			requireStatus(t, 200, status, replay)
			if str(replay, "result") != "fail" || replay["replayed"] != true || calls.Load() != 1 {
				t.Fatal("timeout/cancel retry repeated ambiguous provider operation")
			}
		})
	}
}

func TestCallAPIConcurrentWaitingRequestCannotRepeatInFlightPost(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	started, release := make(chan struct{}), make(chan struct{})
	callAPIFixture(t, s, func(string) Object { return callAPIAction(true) }, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		close(started)
		<-release
		send(w, 200, Object{"code": 0})
	})
	token := str(guest(t, s), "access_token")
	callAPISource(t, s, token, "inflight-call", "fixture in flight callback")
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("inflight-call"))
		if status != 200 || str(response, "result") != "success" {
			t.Error("first waiting callback did not complete successfully")
		}
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		close(release)
		t.Fatal("first waiting callback did not reach provider")
	}
	status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("inflight-call"))
	requireStatus(t, 409, status, response)
	close(release)
	<-finished
	if calls.Load() != 1 {
		t.Fatal("concurrent waiting callback duplicated the in-flight operation")
	}
}

func TestCallAPIZeroTimeoutPreservesWaitAndDispatchDistinction(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	callAPIFixture(t, s, func(text string) Object {
		action := callAPIAction(strings.Contains(text, " wait"))
		action["timeout"] = 0
		if strings.Contains(text, "fractional") {
			action["timeout"] = "0.5"
		}
		return action
	}, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); send(w, 200, Object{"code": 0}) })
	token := str(guest(t, s), "access_token")
	for _, shape := range []string{"numeric", "fractional-string"} {
		for _, wait := range []bool{true, false} {
			id, text, expected := "zero-dispatch-"+shape, "fixture zero dispatch "+shape, "success"
			if wait {
				id, text, expected = "zero-wait-"+shape, "fixture zero wait "+shape, "fail"
			}
			callAPISource(t, s, token, id, text)
			status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput(id))
			requireStatus(t, 200, status, response)
			if str(response, "result") != expected || response["upstream_confirmed"] != false {
				t.Fatal("zero deadline conflated waited result with accepted background dispatch")
			}
		}
	}
	waitCallAPIWorkers(t, s)
	if calls.Load() != 0 {
		t.Fatal("already-expired CallApi deadline reached upstream")
	}
}

func TestCallAPINonWaitingDispatchAndConcurrentReplayDoNotWaitForProvider(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	started, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	callAPIFixture(t, s, func(string) Object { return callAPIAction(false) }, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		close(started)
		<-release
		send(w, 200, Object{"code": 9}) // A late failure cannot change the dispatch result.
		close(done)
	})
	token := str(guest(t, s), "access_token")
	callAPISource(t, s, token, "nonwait-call", "fixture background callback")
	type observation struct {
		status int
		result Object
	}
	observed := make(chan observation, 1)
	go func() {
		status, result := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("nonwait-call"))
		observed <- observation{status, result}
	}()
	select {
	case first := <-observed:
		requireStatus(t, 200, first.status, first.result)
		if str(first.result, "result") != "success" || str(first.result, "status") != "dispatched" || first.result["upstream_confirmed"] != false || first.result["wait_result"] != false {
			t.Fatal("non-waiting CallApi misrepresented dispatch as provider completion")
		}
	case <-time.After(time.Second):
		close(release)
		t.Fatal("wait_result=false waited for provider response")
	}
	select {
	case <-started:
	case <-time.After(time.Second):
		close(release)
		t.Fatal("accepted CallApi never dispatched")
	}
	var wg sync.WaitGroup
	for index := 0; index < 5; index++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, replay := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("nonwait-call"))
			if status != 200 || replay["replayed"] != true || str(replay, "result") != "success" {
				t.Error("accepted CallApi concurrent replay was not idempotent")
			}
		}()
	}
	wg.Wait()
	close(release)
	<-done
	waitCallAPIWorkers(t, s)
	status, replay := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("nonwait-call"))
	requireStatus(t, 200, status, replay)
	if str(replay, "result") != "success" || replay["upstream_confirmed"] != false || calls.Load() != 1 {
		t.Fatal("late background failure changed dispatch result or repeated callback")
	}
}

func TestCallAPIBoundedWorkersAndRevokedScopeDoNotDispatch(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	callAPIFixture(t, s, func(string) Object { return callAPIAction(false) }, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); send(w, 200, Object{"code": 0}) })
	account := guest(t, s)
	token := str(account, "access_token")
	principal, err := s.authenticate(token)
	if err != nil || !s.callAPIScopeCurrent(principal) {
		t.Fatal("fixture identity was not active")
	}
	for index := 0; index < 8; index++ {
		if !s.reserveCallAPIWorker() {
			t.Fatal("expected eight worker slots")
		}
	}
	callAPISource(t, s, token, "busy-call", "fixture busy callback")
	status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("busy-call"))
	requireStatus(t, 200, status, response)
	if str(response, "result") != "fail" || str(response, "reason") != "busy" {
		t.Fatal("worker limit did not reject an extra callback")
	}
	for index := 0; index < 8; index++ {
		s.releaseCallAPIWorker()
	}
	status, response = call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("busy-call"))
	requireStatus(t, 200, status, response)
	if str(response, "result") != "fail" || response["replayed"] != true {
		t.Fatal("rejected busy callback was later retried upstream")
	}
	s.db.Exec("UPDATE sessions SET revoked=1 WHERE id=?", principal.SessionID)
	if s.callAPIScopeCurrent(principal) {
		t.Fatal("revoked session still authorized a background callback")
	}
	s.db.Exec("UPDATE sessions SET revoked=0 WHERE id=?", principal.SessionID)
	other := guest(t, s)
	if _, err := s.db.Exec("UPDATE devices SET user_id=? WHERE id=?", str(other["user"].(map[string]any), "id"), str(principal.Device, "id")); err != nil {
		t.Fatal(err)
	}
	if s.callAPIScopeCurrent(principal) {
		t.Fatal("unlinked device still authorized a background callback")
	}
	if calls.Load() != 0 {
		t.Fatal("capacity rejection reached upstream")
	}
}

func TestCallAPIFieldDefaultsBoundsAndMalformedAction(t *testing.T) {
	_, _, timeout, wait, err := originalCallAPIFields(Object{"pathUrl": "/callback", "payload": Object{}})
	if err != nil || timeout != 5*time.Second || wait {
		t.Fatal("Original CallApi defaults changed")
	}
	_, _, timeout, wait, err = originalCallAPIFields(Object{"pathUrl": "/callback", "payload": Object{}, "timeout": "35000", "wait_result": "TRUE"})
	if err != nil || timeout != 30*time.Second || !wait {
		t.Fatal("bounded numeric-string timeout / wait boolean mismatch")
	}
	for _, invalid := range []Object{{}, {"pathUrl": "/callback"}, {"pathUrl": "/callback", "payload": []any{}}, {"pathUrl": "", "payload": Object{}}, {"pathUrl": "/callback", "payload": Object{"huge": strings.Repeat("x", 512<<10)}}} {
		if _, _, _, _, err := originalCallAPIFields(invalid); err == nil {
			t.Fatal("malformed stored CallApi became executable")
		}
	}
}

func TestCallAPIOptLongFiniteTimeoutCoercions(t *testing.T) {
	for _, test := range []struct {
		name  string
		value any
		want  time.Duration
	}{
		{"fractional_string_zero", "0.5", 0},
		{"fractional_string_truncated", "12.9", 12 * time.Millisecond},
		{"negative_fraction", "-0.9", 0},
		{"scientific", "1.29e2", 129 * time.Millisecond},
		{"java_f_suffix", "12.9F", 12 * time.Millisecond},
		{"java_d_suffix", "12.9d", 12 * time.Millisecond},
		{"hex_exponent", "0x1.9p3", 12 * time.Millisecond},
		{"trimmed", "\t12.9\r\n", 12 * time.Millisecond},
		{"large_finite", "1e100", 30 * time.Second},
		{"large_negative_finite", "-1e100", 0},
		{"malformed_default", "not a number", 5 * time.Second},
		{"underscore_default", "1_2", 5 * time.Second},
		{"boolean_default", true, 5 * time.Second},
		{"missing_default", nil, 5 * time.Second},
		{"nan_boundary", "NaN", 0},
		{"infinity_boundary", "Infinity", 0},
		{"overflow_boundary", "1e9999", 0},
		{"overlong_boundary", strings.Repeat("0", 257), 0},
	} {
		t.Run(test.name, func(t *testing.T) {
			action := callAPIAction(true)
			action["timeout"] = test.value
			_, _, timeout, _, err := originalCallAPIFields(action)
			if err != nil || timeout != test.want {
				t.Fatalf("optLong timeout mismatch: got %v want %v", timeout, test.want)
			}
		})
	}
}

func TestCallAPIGenericNLUDirectiveCannotBorrowLegacyCallbackCredentials(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		send(w, 200, Object{"directives": []Object{{"type": "CallApi", "payload": callAPIAction(true)}}})
	}))
	defer fixture.Close()
	if err := s.saveProviders(Object{"nlu": Object{"mode": "generic", "url": fixture.URL}}); err != nil {
		t.Fatal(err)
	}
	token := str(guest(t, s), "access_token")
	callAPISource(t, s, token, "generic-call", "fixture generic callback")
	// Add the ID to the generic fixture action; it still lacks a verified
	// Original provider binding and therefore cannot acquire callback rights.
	s.db.Exec(`UPDATE records SET data=json_set(data,'$.result.directives[0].payload.action_id','callback-action') WHERE kind='assistant_request'`)
	status, response := call(t, s, "POST", "/v1/assistant/call-api", token, callAPIInput("generic-call"))
	requireStatus(t, 409, status, response)
	if calls.Load() != 1 {
		t.Fatal("generic NLU action became a legacy callback")
	}
}

func TestCallAPIProviderBindingCanonicalizesEmptyHeadersButBindsCredentials(t *testing.T) {
	p := Provider{Mode: "legacy_tn", URL: "https://fixture.invalid/nlu"}
	before := locationProviderBinding(p)
	p.HeaderEnv = map[string]string{}
	if locationProviderBinding(p) != before {
		t.Fatal("empty HeaderEnv allocation invalidated an unchanged provider")
	}
	t.Setenv("CALL_API_BINDING_HEADER", "fixture-header")
	p.HeaderEnv["X-Api-Key"] = "CALL_API_BINDING_HEADER"
	headerBound := locationProviderBinding(p)
	if headerBound == before {
		t.Fatal("configured header reference was not bound")
	}
	t.Setenv("CALL_API_BINDING_HEADER", "changed-fixture-header")
	if locationProviderBinding(p) == headerBound {
		t.Fatal("resolved header credential changed without changing provider binding")
	}
}
