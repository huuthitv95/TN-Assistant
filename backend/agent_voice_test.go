package main

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"net/http"
	"testing"
)

func TestAgentVoiceSettingsPersistValidateAtomicallyAndKeepUsersSeparate(t *testing.T) {
	s := testServer(t)
	owner, other := guest(t, s), guest(t, s)
	token := str(owner, "access_token")
	uid := str(owner["user"].(map[string]any), "id")
	status, settings := call(t, s, "GET", "/v1/settings", token, nil)
	requireStatus(t, 200, status, settings)
	if settings["agent_voice"] != "south" {
		t.Fatal("settings must present the Southern default")
	}
	// Reading or saving another setting must not silently select a speaker for
	// an existing user who still relies on the operator's TTS configuration.
	status, settings = call(t, s, "PUT", "/v1/settings", token, Object{"volume": 37})
	requireStatus(t, 200, status, settings)
	if _, saved := s.aiSettings(uid)["agent_voice"]; saved {
		t.Fatal("unrelated update persisted an unselected voice")
	}
	for _, voice := range []string{"south", "north"} {
		status, settings = call(t, s, "PUT", "/v1/settings", token, Object{"agent_voice": voice})
		requireStatus(t, 200, status, settings)
		status, settings = call(t, s, "GET", "/v1/settings", token, nil)
		requireStatus(t, 200, status, settings)
		if settings["agent_voice"] != voice || s.aiSettings(uid)["agent_voice"] != voice {
			t.Fatalf("voice %q was not persisted", voice)
		}
	}
	before := jsonText(s.aiSettings(uid))
	for _, invalid := range []any{nil, "", "SOUTH", "north ", "5", "other", true, 11, []any{"south"}, Object{"voice": "south"}} {
		status, rejected := call(t, s, "PUT", "/v1/settings", token, Object{"agent_voice": invalid, "volume": 55})
		requireStatus(t, 400, status, rejected)
		if str(rejected["error"].(map[string]any), "code") != "INVALID_SETTINGS" {
			t.Fatalf("invalid voice returned unexpected error: %#v", rejected)
		}
		if jsonText(s.aiSettings(uid)) != before {
			t.Fatalf("invalid voice %#v changed saved settings", invalid)
		}
	}
	status, settings = call(t, s, "PUT", "/v1/settings", token, Object{"volume": 38})
	requireStatus(t, 200, status, settings)
	if settings["agent_voice"] != "north" || s.aiSettings(uid)["agent_voice"] != "north" {
		t.Fatal("unrelated update lost an explicitly selected voice")
	}
	status, settings = call(t, s, "GET", "/v1/settings", str(other, "access_token"), nil)
	requireStatus(t, 200, status, settings)
	if settings["agent_voice"] != "south" {
		t.Fatal("another user's selected voice leaked into settings")
	}
	before = jsonText(s.aiSettings(uid))
	status, rejected := call(t, s, "PUT", "/v1/settings", "", Object{"agent_voice": "south"})
	requireStatus(t, 401, status, rejected)
	if jsonText(s.aiSettings(uid)) != before {
		t.Fatal("unauthenticated update changed the selected voice")
	}
}

func TestOriginalCloudTTSHTTPUsesSavedVoiceAndSignsEffectiveSpeaker(t *testing.T) {
	s := independentTestServer(t)
	originalTTSFixtureConfig(t, s)
	owner, other := guest(t, s), guest(t, s)
	token := str(owner, "access_token")
	uid := str(owner["user"].(map[string]any), "id")
	before, err := s.record("config", "providers")
	if err != nil {
		t.Fatal(err)
	}
	wantSpeaker := "1"
	synthesisCalls := 0
	originalTTSFixtureHLS(t, func(r *http.Request) {
		synthesisCalls++
		if err := r.ParseForm(); err != nil {
			t.Fatal(err)
		}
		if got := r.Form.Get("speakerId"); got != wantSpeaker {
			t.Errorf("effective speaker = %q, want %q", got, wantSpeaker)
		}
		canonical := fmt.Sprintf("apikey=fixture-original-keyappSrc=fixtureencodeType=2input=Xin chào TNisAndroidBox=falsespeakerId=%sfixture-original-secret", wantSpeaker)
		signature := md5.Sum([]byte(canonical))
		if r.Form.Get("sig") != hex.EncodeToString(signature[:]) {
			t.Error("signature did not cover the effective speaker")
		}
	})
	synthesize := func(token string) {
		t.Helper()
		status, response := call(t, s, "POST", "/v1/tts", token, Object{"text": "Xin chào TN", "speaker_id": "32", "agent_voice": "north"})
		requireStatus(t, 200, status, response)
		if response["mime_type"] != "audio/mpeg" {
			t.Fatalf("original TTS lost MP3 handling: %#v", response)
		}
	}
	synthesize(token) // No selection: configured speaker; request fields cannot override it.
	status, settings := call(t, s, "PUT", "/v1/settings", token, Object{"volume": 37})
	requireStatus(t, 200, status, settings)
	synthesize(token) // An unrelated settings save must retain the provider default.
	for _, choice := range []struct{ voice, speaker string }{{"south", "5"}, {"north", "11"}, {"south", "5"}} {
		status, settings = call(t, s, "PUT", "/v1/settings", token, Object{"agent_voice": choice.voice})
		requireStatus(t, 200, status, settings)
		wantSpeaker = choice.speaker
		synthesize(token)
	}
	wantSpeaker = "1"
	synthesize(str(other, "access_token")) // Authenticated owner's saved choice only.
	// Corrupt or old non-string settings must never become a raw provider ID.
	for _, invalid := range []any{"32", "other", 11, nil, []any{"north"}} {
		if _, err := s.db.Exec("UPDATE settings SET data=? WHERE user_id=?", jsonText(Object{"agent_voice": invalid}), uid); err != nil {
			t.Fatal(err)
		}
		synthesize(token)
	}
	status, rejected := call(t, s, "POST", "/v1/tts", "", Object{"text": "Xin chào TN"})
	requireStatus(t, 401, status, rejected)
	if synthesisCalls != 11 {
		t.Fatalf("unexpected synthesis count %d, want 11", synthesisCalls)
	}
	after, err := s.record("config", "providers")
	if err != nil {
		t.Fatal(err)
	}
	if jsonText(after) != jsonText(before) {
		t.Fatal("per-user speech changed the shared provider configuration")
	}
}
