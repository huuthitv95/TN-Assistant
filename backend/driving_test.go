package main

import (
	"crypto/md5"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func configureDrivingFixture(t *testing.T, s *Server, endpoint string) {
	t.Helper()
	for key, value := range map[string]string{"DRIVING_TEST_TOKEN": "fixture-token", "DRIVING_TEST_DEVICE_TOKEN": "fixture-device-token", "DRIVING_TEST_SECRET": "fixture-secret", "DRIVING_TEST_UID": "fixture-uid", "DRIVING_TEST_DEVICE": "fixture-device"} {
		t.Setenv(key, value)
	}
	s.put("config", "providers", "", Object{"driving": Object{"mode": "legacy_tn", "url": endpoint, "token_env": "DRIVING_TEST_TOKEN", "device_token_env": "DRIVING_TEST_DEVICE_TOKEN", "secret_env": "DRIVING_TEST_SECRET", "uid_env": "DRIVING_TEST_UID", "client_app_type": "fixture-app", "version": "fixture-version", "options": Object{"device_id_env": "DRIVING_TEST_DEVICE"}}})
}
func grantDrivingFixture(t *testing.T, s *Server, g Object) {
	t.Helper()
	uid := str(g["user"].(map[string]any), "id")
	s.put("products", "fixture-driving", "", Object{"id": "fixture-driving", "features": []any{"driving_info"}})
	_, e := s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", "fixture-driving-right", uid, "fixture-driving", "fixture", time.Now().Add(-time.Hour).UTC().Format(time.RFC3339Nano), time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano))
	if e != nil {
		t.Fatal(e)
	}
}
func TestDrivingDoesNotReplaceOriginalPremiumWithSandboxEntitlement(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	requests := 0
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"code":-110001,"data":null}`))
	}))
	defer provider.Close()
	configureDrivingFixture(t, s, provider.URL+"/area")
	input := Object{"lat": 10.7769, "lon": 106.7009, "bearing": 0}
	status, m := call(t, s, "POST", "/v1/driving/area/speedlimit", token, input)
	requireStatus(t, 403, status, m)
	if requests != 0 || str(m["error"].(map[string]any), "code") != "FEATURE_NOT_AVAILABLE" {
		t.Fatal("Basic own account must not request paid provider data")
	}
	grantDrivingFixture(t, s, g)
	for _, area := range []string{"speedlimit", "speedcam"} {
		status, m = call(t, s, "POST", "/v1/driving/area/"+area, token, input)
		requireStatus(t, 403, status, m)
		if str(m["error"].(map[string]any), "code") != "DRIVING_PREMIUM_REQUIRED" || m["data"] != nil {
			t.Fatal("provider denial was converted into paid data success")
		}
	}
	if requests != 2 {
		t.Fatal("expected separate Original area calls")
	}
}
func TestDrivingHealthRequiresItsActualOriginalProtocolAndCredentials(t *testing.T) {
	s := testServer(t)
	configureDrivingFixture(t, s, "http://127.0.0.1/area")
	health := func() bool {
		status, m := call(t, s, "GET", "/v1/health", "", nil)
		requireStatus(t, 200, status, m)
		return m["providers"].(map[string]any)["driving"].(map[string]any)["configured"] == true
	}
	if !health() {
		t.Fatal("Health did not expose the configured driving provider")
	}
	t.Setenv("DRIVING_TEST_SECRET", "")
	if health() {
		t.Fatal("Missing signing secret was accepted")
	}
	t.Setenv("DRIVING_TEST_SECRET", "fixture-secret")
	t.Setenv("DRIVING_TEST_DEVICE", "")
	if health() {
		t.Fatal("Missing Original device identity was accepted")
	}
	if (Provider{Mode: "generic", URL: "http://127.0.0.1/area"}).configured("driving") {
		t.Fatal("Unsupported generic area protocol was reported as configured")
	}
}
func TestDrivingFormSignatureValidationAndPayloadProtocol(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	grantDrivingFixture(t, s, g)
	requests := 0
	reply := `{"code":0,"data":[{"bearing":0,"latitude":10.7,"longitude":106.7,"name":"Fixture road","max_speed":50,"distance":10,"id_sy":42}]}`
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.URL.Path != "/area/speedlimit" || r.Method != "POST" || r.Header.Get("Authorization") != "Token fixture-token UID fixture-uid" {
			t.Error("wrong Original route/method/auth scheme")
		}
		r.ParseForm()
		md5text := func(x string) string { v := md5.Sum([]byte(x)); return hex.EncodeToString(v[:]) }
		expected := md5text("fixture-secret" + md5text("fixture-app"+r.Form.Get("timestamp")))
		if r.Form.Get("hash") != expected || r.Form.Get("device_id") != "fixture-device" || r.Form.Get("lat") != "10.7769" || r.Form.Get("version_code") != "fixture-version" {
			t.Error("form identity/signature/coordinates incorrect")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(reply))
	}))
	defer provider.Close()
	configureDrivingFixture(t, s, provider.URL+"/area")
	for _, input := range []Object{{"lat": 91, "lon": 106.7}, {"lat": "10.7", "lon": 106.7}, {"lat": 10.7, "lon": 181}, {"lat": 10.7, "lon": 106.7, "bearing": 360}} {
		status, m := call(t, s, "POST", "/v1/driving/area/speedlimit", token, input)
		requireStatus(t, 400, status, m)
	}
	if requests != 0 {
		t.Fatal("invalid requests reached the provider")
	}
	input := Object{"lat": 10.7769, "lon": 106.7009, "bearing": 0}
	status, m := call(t, s, "POST", "/v1/driving/area/speedlimit", token, input)
	requireStatus(t, 200, status, m)
	data := m["data"].([]any)
	if len(data) != 1 || data[0].(map[string]any)["max_speed"] != float64(50) {
		t.Fatal("Original data was not preserved")
	}
	for _, invalid := range []string{`{"code":0,"data":{}}`, `{"code":0,"data":null}`, `{"data":[]}`, `{"code":-1,"data":[]}`, `{"code":0,"data":[null]}`} {
		reply = invalid
		status, m = call(t, s, "POST", "/v1/driving/area/speedlimit", token, input)
		requireStatus(t, 502, status, m)
	}
	status, m = call(t, s, "POST", "/v1/driving/area/not-an-area", token, input)
	requireStatus(t, 404, status, m)
}
