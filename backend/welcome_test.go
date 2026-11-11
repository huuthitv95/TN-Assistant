package main

import (
	"crypto/md5"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const welcomeFixture = `{"status":0,"serverTimeInMilli":1,"data":{"version":7,"onboardMessages":[{"id":".kiki_ready.remote","message":"Ready","action":"SPEAK_ONLY","priority":0,"maxNumberOfTimes":-1}],"setupMessages":[{"id":"guide.1","message":"Ask me","action":"SPEAK_AND_LISTEN","priority":2,"maxNumberOfTimes":3,"audioLink":"https://fixture.invalid/guide.mp3","updatedTime":123}]}}`

func welcomeProviderFixture(t *testing.T, s *Server, g Object, endpoint string) {
	t.Helper()
	actionLogFixture(t, s, g, endpoint)
	config, _ := s.record("config", "providers")
	p := config["action_log"].(map[string]any)
	p["url"] = endpoint
	s.put("config", "providers", "", Object{"welcome": p})
}

func TestWelcomeOriginalFormDateAndCommands(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	requests := 0
	serverTime := time.Date(2026, 10, 5, 4, 12, 30, 0, time.UTC)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		r.ParseForm()
		if r.Method != "POST" || r.URL.Path != "/api/welcome_message" || r.Header.Get("Authorization") != "Token fixture-token UID fixture-original-user" || r.Header.Get("Accept") != "application/json" {
			t.Error("Original method, path, auth or accept mismatch")
		}
		for k, want := range map[string]string{"device_token": "fixture-device-token", "device_id": "fixture-original-device", "client_app_type": "APP_KIKI", "version_code": "206", "os_version": "28", "device_model": "LDPlayer", "is_android_box": "false"} {
			if r.Form.Get(k) != want {
				t.Error("Incorrect form field", k)
			}
		}
		inner := md5.Sum([]byte("APP_KIKI" + r.Form.Get("timestamp")))
		outer := md5.Sum([]byte("fixture-secret" + fmt.Sprintf("%x", inner)))
		if len(r.Form.Get("timestamp")) != 13 || r.Form.Get("hash") != fmt.Sprintf("%x", outer) {
			t.Error("Original signature missing")
		}
		for _, k := range []string{"asr", "session_key", "event", "log", "speaker_id"} {
			if _, ok := r.Form[k]; ok {
				t.Error("Unrelated field sent", k)
			}
		}
		w.Header().Set("Date", serverTime.Format(http.TimeFormat))
		w.Write([]byte(welcomeFixture))
	}))
	defer upstream.Close()
	welcomeProviderFixture(t, s, g, upstream.URL+"/api/welcome_message")
	code, reply := call(t, s, "GET", "/v1/welcome/rule", str(g, "access_token"), nil)
	requireStatus(t, 200, code, reply)
	if requests != 1 || reply["serverTimeInMilli"] != float64(serverTime.UnixMilli()) {
		t.Fatal("Source HTTP Date not used", reply)
	}
	data := reply["data"].(map[string]any)
	command := data["setupMessages"].([]any)[0].(map[string]any)
	if command["action"] != "SPEAK_AND_LISTEN" || command["maxNumberOfTimes"] != float64(3) || command["updatedTime"] != float64(123) || data["version"] != float64(7) {
		t.Fatal("Welcome command changed")
	}
	if _, exists := reply["text"]; exists {
		t.Fatal("Rule was replaced by a generic greeting")
	}
	// Welcome is not a Premium purchase or grant, and Basic can use it.
	if s.premiumFeatures(str(g["user"].(map[string]any), "id"))["driving_info"] == true {
		t.Fatal("Welcome granted Premium")
	}
}

func TestWelcomeAccountAndProviderGatesBeforeUpstream(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	other := guest(t, s)
	requests := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests++; w.Write([]byte(welcomeFixture)) }))
	defer upstream.Close()
	code, reply := call(t, s, "GET", "/v1/welcome/rule", str(g, "access_token"), nil)
	requireStatus(t, 503, code, reply)
	welcomeProviderFixture(t, s, g, upstream.URL+"/api/welcome_message")
	code, reply = call(t, s, "GET", "/v1/welcome/rule", str(other, "access_token"), nil)
	requireStatus(t, 403, code, reply)
	if str(reply["error"].(map[string]any), "code") != "PROVIDER_ACCOUNT_MISMATCH" {
		t.Fatal("Owner mismatch not distinguished")
	}
	code, reply = call(t, s, "GET", "/v1/welcome/rule", "", nil)
	requireStatus(t, 401, code, reply)
	welcomeProviderFixture(t, s, g, upstream.URL+"/api/welcome_message?credential=bad")
	code, reply = call(t, s, "GET", "/v1/welcome/rule", str(g, "access_token"), nil)
	requireStatus(t, 503, code, reply)
	welcomeProviderFixture(t, s, g, upstream.URL+"/api/event/")
	code, reply = call(t, s, "GET", "/v1/welcome/rule", str(g, "access_token"), nil)
	requireStatus(t, 503, code, reply)
	if requests != 0 {
		t.Fatal("A gated request reached the owner provider")
	}
}

func TestWelcomeResponseFailuresDoNotBecomeDefaultSpeech(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	reply := welcomeFixture
	httpCode := 200
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(httpCode); w.Write([]byte(reply)) }))
	defer upstream.Close()
	welcomeProviderFixture(t, s, g, upstream.URL+"/api/welcome_message")
	for _, value := range []string{`{}`, `null`, `{"status":"0","data":{}}`, strings.Replace(welcomeFixture, `"version":7`, `"version":null`, 1), strings.Replace(welcomeFixture, `"setupMessages":[`, `"setupMessages":null,"unused":[`, 1), strings.Replace(welcomeFixture, `"id":"guide.1"`, `"id":null`, 1), strings.Repeat("x", (2<<20)+1)} {
		reply = value
		code, out := call(t, s, "GET", "/v1/welcome/rule", str(g, "access_token"), nil)
		requireStatus(t, 502, code, out)
		if _, exists := out["text"]; exists {
			t.Fatal("Malformed rule became default speech")
		}
	}
	reply = welcomeFixture
	httpCode = 403
	code, out := call(t, s, "GET", "/v1/welcome/rule", str(g, "access_token"), nil)
	requireStatus(t, 502, code, out)
}

func TestWelcomeDAOOptionalFieldsAndNonzeroStatus(t *testing.T) {
	now := time.Now()
	reply, err := parseWelcomeRule([]byte(`{"status":19,"data":{"version":0,"onboardMessages":[{"id":"unknown"}],"setupMessages":[]}}`), now)
	if err != nil {
		t.Fatal(err)
	}
	row := reply["data"].(Object)["onboardMessages"].([]any)[0].(Object)
	if reply["status"] != 19 || reply["periodUpdate"] != -1 || row["action"] != "NOTHING" || row["message"] != "" || row["priority"] != -1 || row["maxNumberOfTimes"] != -1 || row["audioLink"] != nil {
		t.Fatal("DAO defaults lost", reply)
	}
	if row["updatedTime"].(int64) < now.UnixMilli() || reply["serverTimeInMilli"] != now.UnixMilli() {
		t.Fatal("Clock default mismatch")
	}
}

func TestWelcomeDeployedCodeZeroRequiresExplicitConsistentSuccess(t *testing.T) {
	observed := strings.Replace(welcomeFixture, `"status":0`, `"code":0`, 1)
	result, err := parseWelcomeRule([]byte(observed), time.Now())
	if err != nil || result["status"] != 0 || result["provider_result_field"] != "code" {
		t.Fatal("Observed deployed envelope rejected", err)
	}
	for _, code := range []string{`null`, `"0"`, `0.0`, `true`, `-110001`, `19`} {
		raw := strings.Replace(observed, `"code":0`, `"code":`+code, 1)
		if _, err := parseWelcomeRule([]byte(raw), time.Now()); err == nil {
			t.Fatal("Malformed/denied code accepted", code)
		}
	}
	raw := strings.Replace(welcomeFixture, `"status":0`, `"status":19,"code":0`, 1)
	if _, err := parseWelcomeRule([]byte(raw), time.Now()); err == nil {
		t.Fatal("Conflicting provider status/code accepted")
	}
}
