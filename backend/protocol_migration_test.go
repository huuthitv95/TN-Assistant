package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestProviderLegacyModeLoadingAndCanonicalAdminWrites(t *testing.T) {
	s := testServer(t)
	old := Object{"nlu": Object{"mode": "legacy_kiki", "url": "https://provider.invalid/event", "client_app_type": "APP_KIKI"}}
	if err := s.put("config", "providers", "", old); err != nil {
		t.Fatal(err)
	}
	delete(old, "id")
	admin := adminToken(t, s)
	for _, source := range []string{"database", "file"} {
		t.Run(source, func(t *testing.T) {
			if source == "file" {
				file := filepath.Join(t.TempDir(), "providers.json")
				if err := os.WriteFile(file, []byte(jsonText(old)), 0600); err != nil {
					t.Fatal(err)
				}
				s.cfg.ProviderFile = file
				defer func() { s.cfg.ProviderFile = "" }()
			}
			status, response := call(t, s, "GET", "/v1/admin/providers", admin, nil)
			requireStatus(t, 200, status, response)
			for kind, value := range response {
				provider := value.(map[string]any)
				if provider["mode"] != "legacy_tn" {
					t.Fatalf("%s returned a noncanonical mode: %v", kind, provider["mode"])
				}
			}
			if response["nlu"].(map[string]any)["client_app_type"] != "APP_KIKI" {
				t.Fatal("loading the old mode changed upstream client identity")
			}
		})
	}
	status, response := call(t, s, "PUT", "/v1/admin/providers", admin, old)
	requireStatus(t, 400, status, response)
	if response["error"].(map[string]any)["code"] != "INVALID_PROVIDER_MODE" {
		t.Fatal("old mode did not receive the expected migration error")
	}
	for _, mode := range []string{"legacy_tn", "generic"} {
		status, response = call(t, s, "PUT", "/v1/admin/providers", admin, Object{"nlu": Object{"mode": mode, "url": "https://provider.invalid/event"}})
		requireStatus(t, 200, status, response)
		if response["nlu"].(map[string]any)["mode"] != mode {
			t.Fatal("canonical mode was not preserved")
		}
	}
}

func TestActionLogAdapterUsesConfiguredIdentityWithoutChangingCiphertext(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	const upstream = "APP_CUSTOM_UPSTREAM"
	requests := 0
	var expectedCiphertext, expectedMeta []byte
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if err := r.ParseForm(); err != nil {
			t.Fatal(err)
		}
		inner := md5.Sum([]byte(upstream + r.Form.Get("timestamp")))
		outer := md5.Sum([]byte("fixture-secret" + fmt.Sprintf("%x", inner)))
		if r.Form.Get("client_app_type") != upstream || r.Form.Get("hash") != fmt.Sprintf("%x", outer) {
			t.Error("request was not signed using its configured outbound identity")
		}
		var logs []Object
		if err := json.Unmarshal([]byte(r.Form.Get("log")), &logs); err != nil || len(logs) != 1 {
			t.Fatal("missing action log")
		}
		if logs[0]["app_type"] != upstream || logs[0]["user_id"] != "fixture-original-user" || logs[0]["device_id"] != "fixture-original-device" {
			t.Error("adapter failed to bind the outer provider identity")
		}
		ciphertext, _ := json.Marshal(logs[0]["log_content"])
		meta, _ := json.Marshal(logs[0]["meta"])
		if !bytes.Equal(ciphertext, expectedCiphertext) || !bytes.Equal(meta, expectedMeta) {
			t.Error("adapter modified encrypted content or key/IV metadata")
		}
		w.Write([]byte(`{"code":0}`))
	}))
	defer provider.Close()
	actionLogFixture(t, s, g, provider.URL+"/log/action/v2")
	configuration, err := s.record("config", "providers")
	if err != nil {
		t.Fatal(err)
	}
	delete(configuration, "id")
	configuration["action_log"].(map[string]any)["client_app_type"] = upstream
	if err := s.put("config", "providers", "", configuration); err != nil {
		t.Fatal(err)
	}
	for _, app := range []string{"APP_TN", "APP_KIKI"} {
		input := fixtureMediaLogInput()
		row := input["logs"].([]any)[0].(Object)
		row["app_type"] = app
		expectedCiphertext, _ = json.Marshal(row["log_content"])
		expectedMeta, _ = json.Marshal(row["meta"])
		status, response := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), input)
		requireStatus(t, 200, status, response)
	}
	if requests != 2 {
		t.Fatal("both old and new client metadata must reach the adapter")
	}
}

func TestWebhookAcceptsOnlyTNHeaderAndAuthenticatesRawBytes(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	order, err := s.createOrder(str(g["user"].(map[string]any), "id"), "premium-month")
	if err != nil {
		t.Fatal(err)
	}
	raw := []byte(jsonText(Object{"event_id": "tn-header-event", "order_id": str(order, "id"), "status": "paid"}))
	mac := hmac.New(sha256.New, []byte(s.cfg.WebhookSecret))
	mac.Write(raw)
	signature := hex.EncodeToString(mac.Sum(nil))
	for _, test := range []struct {
		name, header, signature string
		body                    []byte
		status                  int
	}{
		{"old header", "X-Kiki-Signature", signature, raw, 401},
		{"wrong signature", "X-TN-Signature", "00", raw, 401},
		{"changed raw bytes", "X-TN-Signature", signature, append(append([]byte{}, raw...), '\n'), 401},
		{"TN header", "X-TN-Signature", signature, raw, 200},
		{"idempotent replay", "X-TN-Signature", signature, raw, 200},
	} {
		t.Run(test.name, func(t *testing.T) {
			r := httptest.NewRequest("POST", "/v1/payments/webhook", bytes.NewReader(test.body))
			r.Header.Set(test.header, test.signature)
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, r)
			if w.Code != test.status {
				t.Fatalf("expected %d, got %d: %s", test.status, w.Code, w.Body.String())
			}
		})
	}
	var grants int
	if err := s.db.QueryRow("SELECT count(*) FROM entitlements WHERE source=?", "payment:"+str(order, "id")).Scan(&grants); err != nil || grants != 1 {
		t.Fatalf("webhook replay grant count: %d (%v)", grants, err)
	}
}
