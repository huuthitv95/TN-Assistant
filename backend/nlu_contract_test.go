package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"
)

func TestLegacyNLUPreservesProviderSessionAndClientVoiceWithAccountDeviceIsolation(t *testing.T) {
	s := testServer(t)
	t.Setenv("TEST_NLU_AUTH", "fixture-token")
	t.Setenv("TEST_NLU_DEVICE", "fixture-device-token")
	t.Setenv("TEST_NLU_DEVICE_ID", "fixture-provider-device")
	var mu sync.Mutex
	forms := []url.Values{}
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		mu.Lock()
		forms = append(forms, r.PostForm)
		mu.Unlock()
		send(w, 200, Object{"code": 0, "session_key": "fixture-server-session", "directives": []Object{{"action_id": "speech", "action_code": "SpeechSynthesizer", "required": []any{}, "payload": Object{"utter_text": "fixture speech"}}}})
	}))
	defer provider.Close()
	if err := s.saveProviders(Object{"nlu": Object{"mode": "legacy_tn", "url": provider.URL, "token_env": "TEST_NLU_AUTH", "device_token_env": "TEST_NLU_DEVICE", "uid": "42", "options": Object{"device_id_env": "TEST_NLU_DEVICE_ID", "asr_type": "KIKI", "nlu_type": "hybrid_nlu", "micro_source": "unknown", "form_fields": Object{"speaker_id": "5"}}}}); err != nil {
		t.Fatal(err)
	}
	first, second := guest(t, s), guest(t, s)
	firstToken, secondToken := str(first, "access_token"), str(second, "access_token")
	request := func(token, id string) {
		t.Helper()
		code, response := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "fixture command", "mode": "commands", "request_id": id, "conversation_id": "shared-label", "speaker_id": "11", "locations": []Object{{"lat": 10.1, "lon": 106.1}}})
		requireStatus(t, 200, code, response)
	}
	request(firstToken, "first-command")
	request(firstToken, "second-command")
	request(secondToken, "first-command")
	// A second authenticated device linked to the first user still starts its
	// own provider conversation despite using the same conversation label.
	uid := str(first["user"].(map[string]any), "id")
	did := str(second["device"].(map[string]any), "id")
	s.db.Exec("UPDATE devices SET user_id=? WHERE id=?", uid, did)
	s.db.Exec("UPDATE sessions SET user_id=? WHERE device_id=?", uid, did)
	request(secondToken, "second-device-command")
	code, response := call(t, s, "PUT", "/v1/settings", firstToken, Object{"private_mode": true})
	requireStatus(t, 200, code, response)
	request(firstToken, "private-command")
	mu.Lock()
	defer mu.Unlock()
	for index, form := range forms {
		expectedSession := ""
		if index == 1 {
			expectedSession = "fixture-server-session"
		}
		if form.Get("session_key") != expectedSession {
			t.Fatalf("request %d provider session mismatch %q", index, form.Get("session_key"))
		}
		if form.Get("speaker_id") != "11" || form.Get("device_id") != "fixture-provider-device" || form.Get("asr_request_id") == "" {
			t.Fatalf("request %d lost voice, bound device, or request ID", index)
		}
		var locations []Object
		if json.Unmarshal([]byte(form.Get("locations")), &locations) != nil || len(locations) != 1 {
			t.Fatal("locations was not a JSON array")
		}
	}
}

func TestNLUConditionalFailureSpeechIsNotGlobalUtterance(t *testing.T) {
	var raw Object
	json.Unmarshal([]byte(`{"code":0,"directives":[{"action_code":"SpeechSynthesizerOffline","action_id":"announce","required":[],"payload":{"type":"OPEN_APP_GOOGLE_MAPS"}},{"action_code":"OpenApp","action_id":"open-map","required":[],"payload":{"package_name":"com.google.android.apps.maps"}},{"action_code":"SpeechSynthesizer","action_id":"failed","required":["open-map|fail"],"payload":{"utter_text":"Opening Maps failed"}},{"action_code":"SpeedLimitControl","action_id":"traffic","required":["open-map|success"],"payload":{"package_name":"com.google.android.apps.maps","data":{"enabled":true}}}]}`), &raw)
	result, err := normalizeNLU(raw, "legacy_tn")
	if err != nil {
		t.Fatal(err)
	}
	if str(result, "utterance") != "" {
		t.Fatal("conditional failure was shown as unconditional answer")
	}
	directives := result["directives"].([]Object)
	if len(directives) != 4 || str(directives[2]["payload"].(map[string]any), "utter_text") != "Opening Maps failed" || jsonText(directives[2]["payload"].(map[string]any)["required"]) != `["open-map|fail"]` {
		t.Fatal("conditional speech/order/required metadata was removed")
	}
}

func TestLegacyEmptyNLUIsProviderErrorAndCannotBecomeSuccessfulHistory(t *testing.T) {
	s := testServer(t)
	t.Setenv("TEST_NLU_AUTH", "fixture-token")
	t.Setenv("TEST_NLU_DEVICE", "fixture-device-token")
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		send(w, 200, Object{"code": 0, "directives": []Object{}})
	}))
	defer provider.Close()
	s.saveProviders(Object{"nlu": Object{"mode": "legacy_tn", "url": provider.URL, "token_env": "TEST_NLU_AUTH", "device_token_env": "TEST_NLU_DEVICE", "uid": "42"}})
	account := guest(t, s)
	code, response := call(t, s, "POST", "/v1/assistant/text", str(account, "access_token"), Object{"text": "fixture empty reply", "request_id": "empty-response", "mode": "commands"})
	requireStatus(t, 502, code, response)
	if str(response["error"].(map[string]any), "code") != "PROVIDER_EMPTY_RESPONSE" {
		t.Fatal("empty provider response was not identified")
	}
	var count int
	s.db.QueryRow("SELECT count(*) FROM records WHERE kind='history'").Scan(&count)
	if count != 0 {
		t.Fatal("empty provider response was retained as successful history")
	}
}
