package main

import (
	"bytes"
	"crypto/md5"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func actionLogFixture(t *testing.T, s *Server, g Object, endpoint string) {
	t.Helper()
	for key, value := range map[string]string{"LOG_SECRET": "fixture-secret", "LOG_TOKEN": "fixture-token", "LOG_DEVICE_TOKEN": "fixture-device-token", "LOG_UID": "fixture-original-user", "LOG_DEVICE": "fixture-original-device"} {
		t.Setenv(key, value)
	}
	s.put("config", "providers", "", Object{"action_log": Object{"mode": "legacy_tn", "url": endpoint, "token_env": "LOG_TOKEN", "device_token_env": "LOG_DEVICE_TOKEN", "secret_env": "LOG_SECRET", "client_app_type": "APP_KIKI", "version": "206", "uid_env": "LOG_UID", "options": Object{"owner_user_id": str(g["user"].(map[string]any), "id"), "device_id_env": "LOG_DEVICE", "asr_type": "KIKI", "nlu_type": "hybrid_nlu", "micro_source": "unknown", "form_fields": Object{"os_version": "28", "device_model": "LDPlayer", "is_android_box": "false"}}}})
	s.put("config", "public", "", Object{"speed_limit_feedback": Object{"enabled": true}})
}
func fixtureLogInput() Object {
	encoded := func(n int) string { return base64.URLEncoding.EncodeToString(bytes.Repeat([]byte{0x5a}, n)) }
	envelope := func(code int) Object {
		return Object{"action_code": code, "user_id": "owned-account-id", "device_id": "owned-device-id", "log_content": encoded(32), "meta": Object{"content_format": "encrypted_array", "encryption_setting": Object{"iv": encoded(256), "secret_key": encoded(256)}}}
	}
	return Object{"logs": []any{envelope(12), envelope(30)}, "lat": 10.7, "lon": 106.7, "session_key": "tn-conversation-label"}
}

func fixtureMediaLogInput() Object {
	input := fixtureLogInput()
	row := input["logs"].([]any)[0].(Object)
	row["action_code"] = 10
	input["logs"] = []any{row}
	// KikiInfoDataProvider starts with this explicit UNKNOWN sentinel. It is
	// transport context, not a GPS fix or a Premium road snapshot.
	input["lat"], input["lon"], input["session_key"] = -1.0, -1.0, ""
	return input
}

func TestMediaActionLogBasicOriginalTransportAndReceipt(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	uid := str(g["user"].(map[string]any), "id")
	if s.premiumFeatures(uid)["driving_info"] == true {
		t.Fatal("media fixture must exercise an ordinary Basic account")
	}
	input := fixtureMediaLogInput()
	requests := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if err := r.ParseForm(); err != nil {
			t.Fatal(err)
		}
		if r.Method != "POST" || r.URL.Path != "/log/action/v2" || r.Header.Get("Authorization") != "Token fixture-token UID fixture-original-user" {
			t.Error("media did not use the Original action-v2 transport")
		}
		for key, want := range map[string]string{"device_token": "fixture-device-token", "asr": "KIKI", "nlu_type": "hybrid_nlu", "micro_source": "unknown", "session_state": "session.chat", "device_id": "fixture-original-device", "lat": "-1", "lon": "-1"} {
			if r.Form.Get(key) != want {
				t.Error("media form mismatch", key)
			}
		}
		if value, exists := r.Form["session_key"]; !exists || len(value) != 1 || value[0] != "" {
			t.Error("empty initial assistant session must remain present")
		}
		if _, exists := r.Form["asr_request_id"]; exists {
			t.Error("null ASR request ID must be omitted")
		}
		inner := md5.Sum([]byte("APP_KIKI" + r.Form.Get("timestamp")))
		outer := md5.Sum([]byte("fixture-secret" + fmt.Sprintf("%x", inner)))
		if r.Form.Get("client_app_type") != "APP_KIKI" || r.Form.Get("version_code") != "206" || len(r.Form.Get("timestamp")) != 13 || r.Form.Get("hash") != fmt.Sprintf("%x", outer) {
			t.Error("media lost the common Original interceptor fields/signature")
		}
		event, err := url.QueryUnescape(r.Form.Get("event"))
		if err != nil || !strings.Contains(event, `"intent": "report_ki_ki_asr_fail"`) || !strings.Contains(r.Form.Get("event"), "%7B") {
			t.Error("Original pre-encoded event not preserved for media")
		}
		var logs []Object
		if json.Unmarshal([]byte(r.Form.Get("log")), &logs) != nil || len(logs) != 1 {
			t.Fatal("expected one encrypted media envelope")
		}
		row := logs[0]
		original := input["logs"].([]any)[0].(Object)
		if row["action_code"] != float64(10) || row["user_id"] != "fixture-original-user" || row["device_id"] != "fixture-original-device" || row["log_content"] != original["log_content"] {
			t.Error("media identity binding or ciphertext preservation failed")
		}
		gotMeta, _ := json.Marshal(row["meta"])
		wantMeta, _ := json.Marshal(original["meta"])
		if !bytes.Equal(gotMeta, wantMeta) {
			t.Error("wrapped media encryption metadata changed")
		}
		w.Write([]byte(`{"code":0,"message":"accepted"}`))
	}))
	defer provider.Close()
	actionLogFixture(t, s, g, provider.URL+"/log/action/v2")
	// The Premium feedback toggle has no bearing on Basic duration reports.
	s.put("config", "public", "", Object{"speed_limit_feedback": Object{"enabled": false}})
	status, response := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), input)
	requireStatus(t, 200, status, response)
	if requests != 1 || response["acknowledged"] != true || response["groups"] != float64(1) || response["provider_result_code"] != float64(0) {
		t.Fatal("valid Basic media event was not acknowledged")
	}
}

func TestMediaActionLogStillRequiresLiveOwnerSession(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	other := guest(t, s)
	requests := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests++; w.Write([]byte(`{"status":0}`)) }))
	defer provider.Close()
	actionLogFixture(t, s, g, provider.URL+"/log/action/v2")
	for _, token := range []string{"", "invalid-token"} {
		status, response := call(t, s, "POST", "/v1/driving/log", token, fixtureMediaLogInput())
		requireStatus(t, 401, status, response)
	}
	status, response := call(t, s, "POST", "/v1/driving/log", str(other, "access_token"), fixtureMediaLogInput())
	requireStatus(t, 403, status, response)
	if str(response["error"].(map[string]any), "code") != "PROVIDER_ACCOUNT_MISMATCH" {
		t.Fatal("Basic media must retain provider owner binding")
	}
	if _, err := s.db.Exec("UPDATE sessions SET revoked=1 WHERE user_id=?", str(g["user"].(map[string]any), "id")); err != nil {
		t.Fatal(err)
	}
	status, response = call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), fixtureMediaLogInput())
	requireStatus(t, 401, status, response)
	if requests != 0 {
		t.Fatal("unowned or revoked media request reached the provider")
	}
}

func TestMediaActionLogRejectsMalformedAndMixedEnvelopes(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	requests := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests++; w.Write([]byte(`{"status":0}`)) }))
	defer provider.Close()
	actionLogFixture(t, s, g, provider.URL+"/log/action/v2")
	cases := []struct {
		name   string
		mutate func(Object)
	}{
		{"plaintext", func(m Object) { m["logs"].([]any)[0].(Object)["log_content"] = "plaintext" }},
		{"unencrypted array", func(m Object) { m["logs"].([]any)[0].(Object)["log_content"] = []any{Object{"action": "PLAY"}} }},
		{"wrong format", func(m Object) { m["logs"].([]any)[0].(Object)["meta"].(Object)["content_format"] = "array" }},
		{"missing RSA IV", func(m Object) {
			delete(m["logs"].([]any)[0].(Object)["meta"].(Object)["encryption_setting"].(Object), "iv")
		}},
		{"missing RSA key", func(m Object) {
			delete(m["logs"].([]any)[0].(Object)["meta"].(Object)["encryption_setting"].(Object), "secret_key")
		}},
		{"missing latitude", func(m Object) { delete(m, "lat") }},
		{"invalid latitude", func(m Object) { m["lat"] = 91 }},
		{"invalid longitude", func(m Object) { m["lon"] = -181 }},
		{"string coordinate", func(m Object) { m["lon"] = "-1" }},
		{"missing session", func(m Object) { delete(m, "session_key") }},
		{"nonstring session", func(m Object) { m["session_key"] = 1 }},
		{"oversized session", func(m Object) { m["session_key"] = strings.Repeat("s", 513) }},
		{"two media", func(m Object) { m["logs"] = append(m["logs"].([]any), fixtureMediaLogInput()["logs"].([]any)[0]) }},
		{"media then road", func(m Object) { m["logs"] = append(m["logs"].([]any), fixtureLogInput()["logs"].([]any)[0]) }},
		{"road then media", func(m Object) { m["logs"] = []any{fixtureLogInput()["logs"].([]any)[0], m["logs"].([]any)[0]} }},
		{"media then feedback", func(m Object) { m["logs"] = append(m["logs"].([]any), fixtureLogInput()["logs"].([]any)[1]) }},
	}
	for _, premium := range []bool{false, true} {
		if premium {
			grantDrivingFixture(t, s, g)
		}
		for _, tc := range cases {
			t.Run(fmt.Sprintf("premium=%v/%s", premium, tc.name), func(t *testing.T) {
				input := fixtureMediaLogInput()
				tc.mutate(input)
				status, response := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), input)
				requireStatus(t, 400, status, response)
			})
		}
	}
	if requests != 0 {
		t.Fatal("malformed or mixed media reached the provider")
	}
}

func TestMediaActionLogProviderRejectionIsNotAcknowledged(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	requests := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests++; w.Write([]byte(`{"status":-1}`)) }))
	defer provider.Close()
	actionLogFixture(t, s, g, provider.URL+"/log/action/v2")
	status, response := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), fixtureMediaLogInput())
	requireStatus(t, 502, status, response)
	if requests != 1 || response["acknowledged"] != nil || str(response["error"].(map[string]any), "code") != "ACTION_LOG_REJECTED" {
		t.Fatal("media rejection was converted into success")
	}
}

func TestDrivingActionLogOriginalFormAndReceipt(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	grantDrivingFixture(t, s, g)
	input := fixtureLogInput()
	requests := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		r.ParseForm()
		if r.Method != "POST" || r.URL.Path != "/log/action/v2" || r.Header.Get("Authorization") != "Token fixture-token UID fixture-original-user" {
			t.Error("Original method/path/auth mismatch")
		}
		for key, want := range map[string]string{"device_token": "fixture-device-token", "asr": "KIKI", "nlu_type": "hybrid_nlu", "micro_source": "unknown", "session_key": "", "session_state": "session.chat", "device_id": "fixture-original-device", "lat": "10.7", "lon": "106.7"} {
			if r.Form.Get(key) != want {
				t.Error("form field mismatch", key)
			}
		}
		if _, exists := r.Form["asr_request_id"]; exists {
			t.Error("null ASR request ID must be omitted")
		}
		inner := md5.Sum([]byte("APP_KIKI" + r.Form.Get("timestamp")))
		outer := md5.Sum([]byte("fixture-secret" + fmt.Sprintf("%x", inner)))
		if r.Form.Get("client_app_type") != "APP_KIKI" || r.Form.Get("version_code") != "206" || len(r.Form.Get("timestamp")) != 13 || r.Form.Get("hash") != fmt.Sprintf("%x", outer) || r.Form.Get("os_version") != "28" || r.Form.Get("device_model") != "LDPlayer" {
			t.Error("Original request interceptor fields/signature missing")
		}
		event, err := url.QueryUnescape(r.Form.Get("event"))
		if err != nil || !strings.Contains(event, `"intent": "report_ki_ki_asr_fail"`) || !strings.Contains(r.Form.Get("event"), "%7B") {
			t.Error("Original pre-encoded event not preserved")
		}
		var logs []Object
		if json.Unmarshal([]byte(r.Form.Get("log")), &logs) != nil || len(logs) != 2 {
			t.Fatal("form log is not outer envelope array")
		}
		for i, row := range logs {
			if row["action_code"] != float64([]int{12, 30}[i]) || row["user_id"] != "fixture-original-user" || row["device_id"] != "fixture-original-device" || row["log_content"] != input["logs"].([]any)[i].(Object)["log_content"] {
				t.Error("identity or encrypted content changed incorrectly")
			}
		}
		w.Write([]byte(`{"status":0}`))
	}))
	defer provider.Close()
	actionLogFixture(t, s, g, provider.URL+"/log/action/v2")
	status, m := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), input)
	requireStatus(t, 200, status, m)
	if m["acknowledged"] != true || requests != 1 {
		t.Fatal("status=0 not acknowledged")
	}
}
func TestDrivingActionLogGatesBeforeUsingOwnerCredentials(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	input := fixtureLogInput()
	requests := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests++; w.Write([]byte(`{"status":0}`)) }))
	defer provider.Close()
	actionLogFixture(t, s, g, provider.URL+"/log/action/v2")
	status, m := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), input)
	requireStatus(t, 403, status, m)
	grantDrivingFixture(t, s, g)
	other := guest(t, s)
	// Grant this second account a distinct valid entitlement without reusing fixture PK.
	uid := str(other["user"].(map[string]any), "id")
	if _, err := s.db.Exec("INSERT INTO entitlements SELECT 'other-log-right',?,product_id,'other-log-source',starts_at,expires_at FROM entitlements WHERE id='fixture-driving-right'", uid); err != nil {
		t.Fatal(err)
	}
	status, m = call(t, s, "POST", "/v1/driving/log", str(other, "access_token"), input)
	requireStatus(t, 403, status, m)
	if str(m["error"].(map[string]any), "code") != "PROVIDER_ACCOUNT_MISMATCH" {
		t.Fatal("another premium account used owner credentials")
	}
	s.put("config", "public", "", Object{"speed_limit_feedback": Object{"enabled": "true"}})
	status, m = call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), input)
	requireStatus(t, 403, status, m)
	if requests != 0 {
		t.Fatal("gated request reached provider")
	}
}
func TestDrivingActionLogRejectsPlaintextMalformedGroupsAndCoordinates(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	grantDrivingFixture(t, s, g)
	requests := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests++; w.Write([]byte(`{"status":0}`)) }))
	defer provider.Close()
	actionLogFixture(t, s, g, provider.URL+"/log/action/v2")
	cases := []func(Object){
		func(m Object) { m["logs"] = []any{} },
		func(m Object) { m["logs"] = []any{m["logs"].([]any)[1]} },
		func(m Object) { m["logs"].([]any)[0].(Object)["log_content"] = []any{Object{"latitude": 10.7}} },
		func(m Object) { m["logs"].([]any)[0].(Object)["log_content"] = "plaintext" },
		func(m Object) { m["logs"].([]any)[1].(Object)["action_code"] = 12 },
		func(m Object) { m["logs"].([]any)[1].(Object)["action_code"] = 31 },
		func(m Object) { m["lat"] = 91 }, func(m Object) { m["lon"] = "106.7" }, func(m Object) { delete(m, "session_key") },
	}
	for _, mutate := range cases {
		m := fixtureLogInput()
		mutate(m)
		status, response := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), m)
		requireStatus(t, 400, status, response)
	}
	if requests != 0 {
		t.Fatal("invalid envelope reached provider")
	}
}
func TestDrivingActionLogRequiresJSONStatusZero(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	grantDrivingFixture(t, s, g)
	reply := `{"status":0}`
	httpStatus := 200
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(httpStatus); w.Write([]byte(reply)) }))
	defer provider.Close()
	actionLogFixture(t, s, g, provider.URL+"/log/action/v2")
	for _, invalid := range []string{`{"status":-1}`, `{"ok":true}`, `{"status":"0"}`, `{"status":0.5}`, `{"code":null}`, `{"code":"0"}`, `{"code":1}`, `{"code":0,"status":-1}`, `{"status":0,"code":1}`, `null`, `not-json`} {
		reply = invalid
		status, m := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), fixtureLogInput())
		requireStatus(t, 502, status, m)
		if m["acknowledged"] != nil {
			t.Fatal("provider error falsely acknowledged")
		}
	}
	for _, accepted := range []string{`{"code":0,"message":"fixture-success"}`, `{"status":0}`, `{"code":0,"status":0}`} {
		reply = accepted
		status, m := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), fixtureLogInput())
		requireStatus(t, 200, status, m)
		if m["acknowledged"] != true || m["provider_result_code"] != float64(0) {
			t.Fatal("Explicit deployed result=0 not acknowledged")
		}
	}
	reply = `{"status":0}`
	httpStatus = 403
	status, m := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), fixtureLogInput())
	requireStatus(t, 502, status, m)
}
