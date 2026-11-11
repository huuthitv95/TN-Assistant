package main

import (
	"net/http"
	"strings"
	"testing"
)

type noHealthNetworkTransport struct{ t *testing.T }

func (transport noHealthNetworkTransport) RoundTrip(*http.Request) (*http.Response, error) {
	transport.t.Error("process health must not make an outbound request")
	return nil, problem(503, "TEST_NETWORK_DISABLED", "network disabled")
}

func TestCloudHealthReportsConfigurationWithoutProbingOrExposingCredentials(t *testing.T) {
	s := independentTestServer(t)
	oldTransport := http.DefaultTransport
	http.DefaultTransport = noHealthNetworkTransport{t}
	t.Cleanup(func() { http.DefaultTransport = oldTransport })
	t.Setenv("TN_ASR_PROVIDER", "deepgram")
	t.Setenv("TN_TTS_PROVIDER", "azure")
	t.Setenv("TN_DEEPGRAM_API_KEY", "fixture-deepgram-key")
	t.Setenv("TN_AZURE_SPEECH_KEY", "fixture-azure-key")
	t.Setenv("TN_AZURE_SPEECH_REGION", "southeastasia")
	t.Setenv("TN_DEEPGRAM_ENABLED", "false")

	status, response := call(t, s, "GET", "/v1/health", "", nil)
	requireStatus(t, http.StatusOK, status, response)
	providers := response["providers"].(map[string]any)
	asr := providers["asr"].(map[string]any)
	if asr["configured"] != true || asr["provider"] != "deepgram" || asr["enabled"] != false {
		t.Fatalf("ASR configuration must be distinct from activation: %v", asr)
	}
	tts := providers["tts"].(map[string]any)
	if tts["configured"] != true || tts["provider"] != "azure" {
		t.Fatalf("Azure credentials should be configured without inference: %v", tts)
	}
	for _, secret := range []string{"fixture-deepgram-key", "fixture-azure-key", "southeastasia"} {
		if strings.Contains(jsonText(response), secret) {
			t.Fatal("public health exposed provider configuration")
		}
	}
	if response["status"] != "ok" {
		t.Fatalf("process health must remain independent of provider readiness: %v", response)
	}

	t.Setenv("TN_DEEPGRAM_ENABLED", "true")
	t.Setenv("TN_DEEPGRAM_API_KEY", " \t")
	t.Setenv("TN_AZURE_SPEECH_REGION", "invalid/region")
	status, response = call(t, s, "GET", "/v1/health", "", nil)
	requireStatus(t, http.StatusOK, status, response)
	providers = response["providers"].(map[string]any)
	asr = providers["asr"].(map[string]any)
	if asr["configured"] != false || asr["enabled"] != true {
		t.Fatalf("blank key must not be configured even when enabled: %v", asr)
	}
	if providers["tts"].(map[string]any)["configured"] != false {
		t.Fatal("invalid Azure region must not be configured")
	}

	t.Setenv("TN_TTS_PROVIDER", "disabled")
	status, response = call(t, s, "GET", "/v1/health", "", nil)
	requireStatus(t, http.StatusOK, status, response)
	tts = response["providers"].(map[string]any)["tts"].(map[string]any)
	if tts["configured"] != false || tts["provider"] != "disabled" {
		t.Fatalf("disabled TTS must not advertise configured credentials: %v", tts)
	}
}

func TestCloudHealthPreservesOtherProvidersAndLegacyRuntime(t *testing.T) {
	s := independentTestServer(t)
	t.Setenv("TN_ASR_PROVIDER", "deepgram")
	t.Setenv("TN_DEEPGRAM_API_KEY", "fixture-deepgram-key")
	t.Setenv("TN_TTS_PROVIDER", "disabled")
	t.Setenv("TN_TEST_ZALO_SECRET", "fixture-zalo-secret")
	if err := s.put("config", "providers", "", Object{
		"asr":  Object{"mode": "generic", "url": "https://provider.invalid/asr"},
		"tts":  Object{"mode": "generic", "url": "https://provider.invalid/tts"},
		"nlu":  Object{"mode": "generic", "url": "https://provider.invalid/nlu"},
		"zalo": Object{"mode": "generic", "secret_env": "TN_TEST_ZALO_SECRET", "options": Object{"app_id": "fixture-app", "redirect_uri": "https://tn.invalid/callback"}},
	}); err != nil {
		t.Fatal(err)
	}
	status, response := call(t, s, "GET", "/v1/health", "", nil)
	requireStatus(t, http.StatusOK, status, response)
	providers := response["providers"].(map[string]any)
	for _, kind := range []string{"nlu", "zalo"} {
		if jsonText(providers[kind]) != `{"configured":true}` {
			t.Fatalf("unrelated %s provider changed: %v", kind, providers[kind])
		}
	}
	for _, kind := range []string{"driving", "action_log", "welcome"} {
		if jsonText(providers[kind]) != `{"configured":false}` {
			t.Fatalf("unrelated %s provider changed: %v", kind, providers[kind])
		}
	}

	s.cfg.RuntimeMode = ""
	if _, err := s.db.Exec("DELETE FROM records WHERE kind='config' AND id='runtime'"); err != nil {
		t.Fatal(err)
	}
	status, response = call(t, s, "GET", "/v1/health", "", nil)
	requireStatus(t, http.StatusOK, status, response)
	providers = response["providers"].(map[string]any)
	for _, kind := range []string{"asr", "tts"} {
		if jsonText(providers[kind]) != `{"configured":true}` {
			t.Fatalf("legacy %s provider must keep its prior state: %v", kind, providers[kind])
		}
	}
}
