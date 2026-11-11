package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"sync"
	"testing"
	"time"
)

func actionLogNLUFixture(t *testing.T, s *Server, g Object, endpoint string) Object {
	t.Helper()
	actionLogFixture(t, s, g, endpoint+"/log/action/v2")
	config, err := s.record("config", "providers")
	if err != nil {
		t.Fatal(err)
	}
	config["nlu"] = Object{"mode": "legacy_tn", "url": endpoint + "/nlu", "token_env": "LOG_TOKEN", "device_token_env": "LOG_DEVICE_TOKEN", "uid_env": "LOG_UID", "options": Object{"device_id_env": "LOG_DEVICE", "asr_type": "KIKI", "nlu_type": "hybrid_nlu", "micro_source": "unknown"}}
	if err = s.put("config", "providers", "", config); err != nil {
		t.Fatal(err)
	}
	return config
}

func TestMediaActionLogResolvesLatestProviderSessionAtEverySend(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	var mu sync.Mutex
	nluCalls := 0
	logged := []string{}
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		mu.Lock()
		defer mu.Unlock()
		if r.URL.Path == "/nlu" {
			nluCalls++
			key := "issued-session-one"
			if nluCalls > 1 {
				key = "issued-session-two"
			}
			send(w, 200, Object{"code": 0, "session_key": key, "directives": []Object{{"action_id": "volume", "action_code": "Volume", "required": []any{}, "payload": Object{"value": 3}}}})
			return
		}
		if r.URL.Path != "/log/action/v2" {
			t.Error("unexpected fixture path", r.URL.Path)
		}
		logged = append(logged, r.Form.Get("session_key"))
		if _, exists := r.Form["asr_request_id"]; exists {
			t.Error("action log must omit the Original null ASR request ID")
		}
		send(w, 200, Object{"status": 0})
	}))
	defer provider.Close()
	actionLogNLUFixture(t, s, g, provider.URL)
	const conversation = "owned-tn-conversation"
	log := func(input Object) {
		t.Helper()
		status, response := call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), input)
		requireStatus(t, 200, status, response)
	}
	media := func(label string) Object { input := fixtureMediaLogInput(); input["session_key"] = label; return input }
	log(media(conversation)) // Before NLU, the local label is never sent upstream.
	for _, request := range []string{"first-nlu", "later-non-media-nlu"} {
		status, response := call(t, s, "POST", "/v1/assistant/text", str(g, "access_token"), Object{"text": "fixture command", "mode": "commands", "request_id": request, "session_id": conversation, "conversation_id": conversation, "device_id": str(g["device"].(Object), "id")})
		requireStatus(t, 200, status, response)
		log(media(conversation))
	}
	grantDrivingFixture(t, s, g)
	road := fixtureLogInput()
	road["session_key"] = conversation
	log(road)                        // The shared road path must also resolve the issued key.
	log(media("issued-session-two")) // A client cannot pass a raw provider key.
	mu.Lock()
	defer mu.Unlock()
	if nluCalls != 2 || len(logged) != 5 {
		t.Fatal("wrong fixture request counts", nluCalls, len(logged))
	}
	for index, expected := range []string{"", "issued-session-one", "issued-session-two", "issued-session-two", ""} {
		if logged[index] != expected {
			t.Errorf("log %d used an unissued or stale provider session", index)
		}
	}
}

func TestMediaActionLogSessionUsesTheNLUDeviceFormPrecedence(t *testing.T) {
	for _, source := range []string{"environment", "configured-form", "authenticated-client"} {
		t.Run(source, func(t *testing.T) {
			s := testServer(t)
			g := guest(t, s)
			var received string
			provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				r.ParseForm()
				if r.URL.Path == "/nlu" {
					send(w, 200, Object{"code": 0, "session_key": "device-bound-issued-session", "directives": []Object{{"action_id": "speech", "action_code": "SpeechSynthesizer", "required": []any{}, "payload": Object{"utter_text": "fixture speech"}}}})
					return
				}
				received = r.Form.Get("session_key")
				send(w, 200, Object{"status": 0})
			}))
			defer provider.Close()
			config := actionLogNLUFixture(t, s, g, provider.URL)
			options := config["nlu"].(Object)["options"].(Object)
			switch source {
			case "environment":
				options["form_fields"] = Object{"device_id": "ignored-form-device"}
			case "configured-form":
				delete(options, "device_id_env")
				options["form_fields"] = Object{"device_id": os.Getenv("LOG_DEVICE")}
			case "authenticated-client":
				delete(options, "device_id_env")
				t.Setenv("LOG_DEVICE", str(g["device"].(Object), "id"))
			}
			s.put("config", "providers", "", config)
			const conversation = "device-form-precedence-conversation"
			status, response := call(t, s, "POST", "/v1/assistant/text", str(g, "access_token"), Object{"text": "fixture command", "mode": "commands", "request_id": "fixture-nlu", "session_id": conversation, "conversation_id": conversation, "device_id": str(g["device"].(Object), "id")})
			requireStatus(t, 200, status, response)
			input := fixtureMediaLogInput()
			input["session_key"] = conversation
			status, response = call(t, s, "POST", "/v1/driving/log", str(g, "access_token"), input)
			requireStatus(t, 200, status, response)
			if received != "device-bound-issued-session" {
				t.Fatal("logger did not resolve the NLU writer's effective device binding")
			}
		})
	}
}

func TestMediaActionLogProviderSessionRejectsStaleForeignAndPrivateBindings(t *testing.T) {
	for _, scenario := range []string{"missing-record", "expired", "malformed-expiry", "foreign-record-owner", "different-auth-device", "different-conversation", "nlu-url-change", "nlu-uid-mismatch", "nlu-device-mismatch", "action-uid-mismatch", "action-device-mismatch", "explicit-nlu-owner-mismatch", "generic-nlu", "unconfigured-nlu", "missing-nlu", "private-mode", "malformed-record", "nonstring-issued-key"} {
		t.Run(scenario, func(t *testing.T) {
			s := testServer(t)
			g := guest(t, s)
			var logged url.Values
			provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				r.ParseForm()
				logged = r.PostForm
				send(w, 200, Object{"status": 0})
			}))
			defer provider.Close()
			config := actionLogNLUFixture(t, s, g, provider.URL)
			principal, err := s.authenticate(str(g, "access_token"))
			if err != nil {
				t.Fatal(err)
			}
			providers, _ := s.providers()
			const conversation = "owned-isolated-conversation"
			id := legacyNLUSessionRecordID(principal, conversation, providers["nlu"], os.Getenv("LOG_DEVICE"))
			uid := str(principal.User, "id")
			if err = s.put("nlu_session", id, uid, Object{"session_key": "never-leak-this-issued-key", "expires_at": time.Now().UTC().Add(time.Hour).Format(time.RFC3339Nano)}); err != nil {
				t.Fatal(err)
			}
			input := fixtureMediaLogInput()
			input["session_key"] = conversation
			token := str(g, "access_token")
			nlu := config["nlu"].(Object)
			action := config["action_log"].(Object)
			switch scenario {
			case "missing-record":
				s.db.Exec("DELETE FROM records WHERE kind='nlu_session' AND id=?", id)
			case "expired", "malformed-expiry":
				expires := time.Now().UTC().Add(-time.Hour).Format(time.RFC3339Nano)
				if scenario == "malformed-expiry" {
					expires = "zzzz-not-a-date"
				}
				s.put("nlu_session", id, uid, Object{"session_key": "never-leak-this-issued-key", "expires_at": expires})
			case "foreign-record-owner":
				s.db.Exec("UPDATE records SET owner='unrelated-owner' WHERE kind='nlu_session' AND id=?", id)
			case "different-auth-device":
				other := guest(t, s)
				did := str(other["device"].(Object), "id")
				s.db.Exec("UPDATE devices SET user_id=? WHERE id=?", uid, did)
				s.db.Exec("UPDATE sessions SET user_id=? WHERE device_id=?", uid, did)
				token = str(other, "access_token")
			case "different-conversation":
				input["session_key"] = "other-conversation"
			case "nlu-url-change":
				nlu["url"] = provider.URL + "/other-nlu"
			case "nlu-uid-mismatch":
				nlu["uid_env"], nlu["uid"] = "", "another-upstream-user"
			case "nlu-device-mismatch":
				nlu["options"].(Object)["device_id_env"] = "UNSET_MEDIA_FIXTURE_DEVICE"
			case "action-uid-mismatch":
				action["uid_env"], action["uid"] = "", "another-upstream-user"
			case "action-device-mismatch":
				t.Setenv("ANOTHER_MEDIA_FIXTURE_DEVICE", "another-upstream-device")
				action["options"].(Object)["device_id_env"] = "ANOTHER_MEDIA_FIXTURE_DEVICE"
			case "explicit-nlu-owner-mismatch":
				nlu["options"].(Object)["owner_user_id"] = "another-owner"
			case "generic-nlu":
				nlu["mode"] = "generic"
			case "unconfigured-nlu":
				nlu["token_env"] = "UNSET_MEDIA_FIXTURE_TOKEN"
			case "missing-nlu":
				delete(config, "nlu")
			case "private-mode":
				status, response := call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
				requireStatus(t, 200, status, response)
			case "malformed-record":
				s.db.Exec("UPDATE records SET data='not-json' WHERE kind='nlu_session' AND id=?", id)
			case "nonstring-issued-key":
				s.put("nlu_session", id, uid, Object{"session_key": 7, "expires_at": time.Now().UTC().Add(time.Hour).Format(time.RFC3339Nano)})
			}
			s.put("config", "providers", "", config)
			status, response := call(t, s, "POST", "/v1/driving/log", token, input)
			requireStatus(t, 200, status, response)
			if logged == nil || logged.Get("session_key") != "" {
				t.Fatal("a stale, unowned or unrelated provider session escaped")
			}
		})
	}
}
