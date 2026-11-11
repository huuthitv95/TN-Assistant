package main

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type originalTTSFixtureTransport func(*http.Request) (*http.Response, error)

func (f originalTTSFixtureTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

func originalTTSFixtureNetwork(t *testing.T, f originalTTSFixtureTransport) {
	t.Helper()
	previous := http.DefaultTransport
	http.DefaultTransport = f
	t.Cleanup(func() { http.DefaultTransport = previous })
}

func originalTTSFixtureResponse(status int, mime string, data []byte) *http.Response {
	return &http.Response{StatusCode: status, Header: http.Header{"Content-Type": {mime}}, Body: io.NopCloser(bytes.NewReader(data))}
}

func originalTTSFixtureConfig(t *testing.T, s *Server) Provider {
	t.Helper()
	t.Setenv("TN_TTS_PROVIDER", "original")
	t.Setenv("TN_TEST_ORIGINAL_TTS_KEY", "fixture-original-key")
	t.Setenv("TN_TEST_ORIGINAL_TTS_SECRET", "fixture-original-secret")
	p := Provider{Mode: "legacy_tn", URL: "https://partner-speech.lab.zalo.ai/tts/stream", APIKeyEnv: "TN_TEST_ORIGINAL_TTS_KEY", SecretEnv: "TN_TEST_ORIGINAL_TTS_SECRET", Options: Object{"speaker_id": "1", "app_src": "fixture", "encode_type": "2", "is_android_box": "false", "proxy_hls_audio": true}}
	originalTTSFixtureSaveProvider(t, s, p)
	return p
}

func originalTTSFixtureSaveProvider(t *testing.T, s *Server, p Provider) {
	t.Helper()
	if err := s.put("config", "providers", "", Object{"tts": p}); err != nil {
		t.Fatal(err)
	}
}

// Two MPEG-1 Layer III frames at 128 kbit/s, 44.1 kHz. These bytes exercise
// bounded format handling; they do not establish decoder or acoustic quality.
func originalTTSFixtureMP3() []byte {
	frame := make([]byte, 417)
	copy(frame, []byte{0xff, 0xfb, 0x90, 0x64})
	return append(append([]byte{}, frame...), frame...)
}

func originalTTSFixtureHLS(t *testing.T, onSynthesis func(*http.Request)) {
	t.Helper()
	originalTTSFixtureNetwork(t, func(r *http.Request) (*http.Response, error) {
		switch r.URL.String() {
		case "https://partner-speech.lab.zalo.ai/tts/stream":
			if onSynthesis != nil {
				onSynthesis(r)
			}
			return originalTTSFixtureResponse(200, "application/json", []byte(`{"error_code":0,"data":{"url":"https://audiostream-ailab.tts.zalo.ai/fixture"}}`)), nil
		case "https://audiostream-ailab.tts.zalo.ai/fixture":
			if r.Method != http.MethodGet || r.Header.Get("Authorization") != "" {
				t.Error("playlist fetch must use an unauthenticated GET")
			}
			return originalTTSFixtureResponse(200, "application/vnd.apple.mpegurl", []byte("#EXTM3U\n#EXTINF:1\n//chunk-ailab.tts.zalo.ai/fixture\n#EXT-X-ENDLIST\n")), nil
		case "https://chunk-ailab.tts.zalo.ai/fixture":
			if r.Method != http.MethodGet || r.Header.Get("Authorization") != "" {
				t.Error("chunk fetch must use an unauthenticated GET")
			}
			return originalTTSFixtureResponse(200, "audio/mpeg", originalTTSFixtureMP3()), nil
		default:
			t.Errorf("unexpected outbound URL: %s", r.URL.Redacted())
			return originalTTSFixtureResponse(500, "text/plain", nil), nil
		}
	})
}

func TestOriginalCloudTTSSelectionAndStrictConfiguration(t *testing.T) {
	s := independentTestServer(t)
	p := originalTTSFixtureConfig(t, s)
	if !originalTTSSelected() {
		t.Fatal("original selector was not selected")
	}
	t.Setenv("TN_TTS_PROVIDER", "ORIGINAL")
	if !originalTTSSelected() {
		t.Fatal("original selector must be case insensitive")
	}
	if _, err := s.originalTTSProvider(); err != nil {
		t.Fatalf("valid original TTS configuration rejected: %v", err)
	}
	for _, endpoint := range []string{
		"http://partner-speech.lab.zalo.ai/tts/stream",
		"https://partner-speech.lab.zalo.ai:443/tts/stream",
		"https://partner-speech.lab.zalo.ai/tts/stream?key=fixture",
		"https://partner-speech.lab.zalo.ai/tts/stream#fragment",
		"https://fixture@partner-speech.lab.zalo.ai/tts/stream",
		"https://unrelated.invalid/tts/stream",
		"https://partner-speech.lab.zalo.ai/tts/other",
	} {
		candidate := p
		candidate.URL = endpoint
		originalTTSFixtureSaveProvider(t, s, candidate)
		if _, err := s.originalTTSProvider(); err == nil {
			t.Fatalf("unsafe endpoint accepted: %s", endpoint)
		}
	}
	p.Mode = "generic"
	originalTTSFixtureSaveProvider(t, s, p)
	if _, err := s.originalTTSProvider(); err == nil {
		t.Fatal("generic mode must not be accepted as original TTS")
	}
	p.Mode = "legacy_tn"
	originalTTSFixtureSaveProvider(t, s, p)
	for _, speaker := range []string{"", "0", "33", "not-a-speaker"} {
		candidate := p
		candidate.Options = Object{"speaker_id": speaker, "app_src": "fixture", "encode_type": "2"}
		originalTTSFixtureSaveProvider(t, s, candidate)
		if _, err := s.originalTTSProvider(); err == nil {
			t.Fatalf("invalid original speaker accepted: %q", speaker)
		}
	}
	originalTTSFixtureSaveProvider(t, s, p)
	t.Setenv("TN_TEST_ORIGINAL_TTS_SECRET", "")
	if _, err := s.originalTTSProvider(); err == nil {
		t.Fatal("missing referenced signing secret accepted")
	}
	t.Setenv("TN_TEST_ORIGINAL_TTS_SECRET", "fixture-original-secret")
	t.Setenv("TN_TEST_ORIGINAL_TTS_KEY", "")
	if _, err := s.originalTTSProvider(); err == nil {
		t.Fatal("missing referenced API key accepted")
	}
}

func TestOriginalCloudTTSSignedRequestProxiesMP3AndDoesNotUnlockOtherLegacyProviders(t *testing.T) {
	s := independentTestServer(t)
	originalTTSFixtureConfig(t, s)
	originalTTSFixtureHLS(t, func(r *http.Request) {
		if r.Method != http.MethodPost || r.Header.Get("Content-Type") != "application/x-www-form-urlencoded" {
			t.Error("original synthesis must use a form POST")
		}
		if err := r.ParseForm(); err != nil {
			t.Fatal(err)
		}
		for name, want := range map[string]string{"input": "Xin chào TN", "apikey": "fixture-original-key", "speakerId": "1", "appSrc": "fixture", "encodeType": "2", "isAndroidBox": "false"} {
			if got := r.Form.Get(name); got != want {
				t.Errorf("signed field %s = %q, want %q", name, got, want)
			}
		}
		canonical := "apikey=fixture-original-keyappSrc=fixtureencodeType=2input=Xin chào TNisAndroidBox=falsespeakerId=1fixture-original-secret"
		signature := md5.Sum([]byte(canonical))
		if r.Form.Get("sig") != hex.EncodeToString(signature[:]) {
			t.Error("original TTS signature differs from canonical sorted form")
		}
	})
	audio, mime, err := s.originalTTSSpeech(context.Background(), "Xin chào TN")
	if err != nil || mime != "audio/mpeg" || !bytes.Equal(audio, originalTTSFixtureMP3()) {
		t.Fatalf("original HLS was not proxied as MP3: mime=%q bytes=%d err=%v", mime, len(audio), err)
	}
	for _, kind := range []string{"asr", "nlu", "driving", "action_log", "welcome"} {
		if _, err := s.requireProvider(kind); err == nil {
			t.Errorf("original TTS selection unlocked legacy %s", kind)
		}
	}
	if health := s.speechProviderHealth(context.Background(), "tts", defaultRuntimeConfig()); health["provider"] != "original_tts" || health["healthy"] != true {
		t.Fatalf("successful original audio did not establish readiness: %#v", health)
	}
}

func TestOriginalCloudTTSRejectsMalformedMP3AndText(t *testing.T) {
	if !originalTTSValidMP3(originalTTSFixtureMP3()) {
		t.Fatal("valid MPEG frame fixture rejected")
	}
	for _, bad := range [][]byte{nil, []byte("<html>not audio</html>"), []byte("RIFFnot-an-mp3"), {0xff}, {0xff, 0x00, 0x00, 0x00}, originalTTSFixtureMP3()[:420]} {
		if originalTTSValidMP3(bad) {
			t.Error("malformed MP3 accepted")
		}
	}
	s := independentTestServer(t)
	originalTTSFixtureConfig(t, s)
	originalTTSFixtureNetwork(t, func(*http.Request) (*http.Response, error) {
		t.Error("invalid text must be rejected before network access")
		return originalTTSFixtureResponse(500, "text/plain", nil), nil
	})
	for _, text := range []string{"", " \t\r\n", strings.Repeat("a", 501)} {
		if _, _, err := s.originalTTSSpeech(context.Background(), text); err == nil {
			t.Error("invalid synthesis text accepted")
		}
	}
}

func TestOriginalCloudTTSHealthIsNetworkFreeAndBoundToConfiguration(t *testing.T) {
	s := independentTestServer(t)
	p := originalTTSFixtureConfig(t, s)
	originalTTSFixtureNetwork(t, func(*http.Request) (*http.Response, error) {
		t.Error("configuration and health must not probe the original service")
		return originalTTSFixtureResponse(500, "text/plain", nil), nil
	})
	status, response := call(t, s, "GET", "/v1/health", "", nil)
	requireStatus(t, 200, status, response)
	tts := response["providers"].(map[string]any)["tts"].(map[string]any)
	if tts["configured"] != true || tts["provider"] != "original_tts" {
		t.Fatalf("original configuration missing from public health: %#v", tts)
	}
	for _, secret := range []string{"fixture-original-key", "fixture-original-secret"} {
		if strings.Contains(jsonText(response), secret) {
			t.Fatal("public health exposed an original TTS credential")
		}
	}
	if health := s.speechProviderHealth(context.Background(), "tts", defaultRuntimeConfig()); health["healthy"] == true {
		t.Fatal("unverified original provider advertised healthy")
	}
	first := s.originalTTSIdentity()
	s.markSpeechProviderSuccess("tts", "original_tts")
	if health := s.speechProviderHealth(context.Background(), "tts", defaultRuntimeConfig()); health["healthy"] != true {
		t.Fatal("current original verification was not readable")
	}
	t.Setenv("TN_TEST_ORIGINAL_TTS_KEY", "fixture-changed-key")
	if s.originalTTSIdentity() == first {
		t.Fatal("original TTS fingerprint ignored changed API key")
	}
	if health := s.speechProviderHealth(context.Background(), "tts", defaultRuntimeConfig()); health["healthy"] == true {
		t.Fatal("original readiness was inherited after changing the API key")
	}
	t.Setenv("TN_TEST_ORIGINAL_TTS_KEY", "fixture-original-key")
	t.Setenv("TN_TEST_ORIGINAL_TTS_SECRET", "fixture-changed-secret")
	if s.originalTTSIdentity() == first {
		t.Fatal("original TTS fingerprint ignored changed signing secret")
	}
	t.Setenv("TN_TEST_ORIGINAL_TTS_SECRET", "fixture-original-secret")
	p.Options["speaker_id"] = "2"
	originalTTSFixtureSaveProvider(t, s, p)
	if s.originalTTSIdentity() == first {
		t.Fatal("original TTS fingerprint ignored changed speaker")
	}
}

func TestOriginalCloudTTSErrorsAreSanitizedAndInvalidateReadiness(t *testing.T) {
	s := independentTestServer(t)
	originalTTSFixtureConfig(t, s)
	originalTTSFixtureHLS(t, nil)
	if _, _, err := s.originalTTSSpeech(context.Background(), "Xin chào TN"); err != nil {
		t.Fatal(err)
	}
	for _, response := range []struct {
		status int
		body   string
	}{
		{401, "fixture-original-key fixture-original-secret"},
		{200, "malformed JSON fixture-original-secret"},
		{200, `{"error_code":101,"message":"fixture-original-secret"}`},
		{200, `{"error_code":0,"data":{"url":"https://unrelated.invalid/fixture-original-secret"}}`},
	} {
		originalTTSFixtureNetwork(t, func(*http.Request) (*http.Response, error) {
			return originalTTSFixtureResponse(response.status, "application/json", []byte(response.body)), nil
		})
		if _, _, err := s.originalTTSSpeech(context.Background(), "Xin chào TN"); err == nil {
			t.Fatal("invalid original provider response accepted")
		} else if strings.Contains(err.Error(), "fixture-original-secret") || strings.Contains(err.Error(), "fixture-original-key") {
			t.Fatal("original provider error exposed secret response content")
		}
		if health := s.speechProviderHealth(context.Background(), "tts", defaultRuntimeConfig()); health["healthy"] == true {
			t.Fatalf("failed original request retained healthy readiness: %#v", health)
		}
	}
}

func TestOriginalCloudTTSHTTPKeepsAudioOnAuthenticatedOrigin(t *testing.T) {
	s := independentTestServer(t)
	originalTTSFixtureConfig(t, s)
	originalTTSFixtureHLS(t, nil)
	owner, other := guest(t, s), guest(t, s)
	status, response := call(t, s, "POST", "/v1/tts", str(owner, "access_token"), Object{"text": "Xin chào TN"})
	requireStatus(t, 200, status, response)
	path := str(response, "url")
	if !strings.HasPrefix(path, "/v1/tts/audio/") || str(response, "mime_type") != "audio/mpeg" || strings.Contains(jsonText(response), "zalo.ai") {
		t.Fatalf("TTS response exposed upstream instead of TN audio: %#v", response)
	}
	for _, auth := range []struct {
		token string
		want  int
	}{{str(owner, "access_token"), 200}, {str(other, "access_token"), 403}, {"", 401}} {
		r := httptest.NewRequest(http.MethodGet, path, nil)
		if auth.token != "" {
			r.Header.Set("Authorization", "Bearer "+auth.token)
		}
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		if w.Code != auth.want {
			t.Fatalf("audio ownership returned %d, want %d", w.Code, auth.want)
		}
		if auth.want == 200 && (w.Header().Get("Content-Type") != "audio/mpeg" || !bytes.Equal(w.Body.Bytes(), originalTTSFixtureMP3())) {
			t.Fatal("owned TTS response lost MIME or audio bytes")
		}
	}
}

func TestOriginalCloudTTSDoesNotFollowRedirectOrFetchUnapprovedURLs(t *testing.T) {
	s := independentTestServer(t)
	originalTTSFixtureConfig(t, s)
	calls := 0
	originalTTSFixtureNetwork(t, func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.String() != "https://partner-speech.lab.zalo.ai/tts/stream" {
			t.Error("original TTS followed a redirect to an unapproved URL")
		}
		response := originalTTSFixtureResponse(302, "text/plain", nil)
		response.Header.Set("Location", "https://unrelated.invalid/credential-sink")
		return response, nil
	})
	if _, _, err := s.originalTTSSpeech(context.Background(), "Xin chào TN"); err == nil || calls != 1 {
		t.Fatalf("redirect was not rejected before another request: calls=%d err=%v", calls, err)
	}
	originalTTSFixtureNetwork(t, func(r *http.Request) (*http.Response, error) {
		if r.URL.String() == "https://partner-speech.lab.zalo.ai/tts/stream" {
			return originalTTSFixtureResponse(200, "application/json", []byte(`{"error_code":0,"data":{"url":"https://audiostream-ailab.tts.zalo.ai/fixture"}}`)), nil
		}
		if r.URL.String() == "https://audiostream-ailab.tts.zalo.ai/fixture" {
			return originalTTSFixtureResponse(200, "application/vnd.apple.mpegurl", []byte("#EXTM3U\nhttps://unrelated.invalid/credential-sink")), nil
		}
		t.Error("original TTS fetched an unapproved HLS chunk")
		return originalTTSFixtureResponse(500, "text/plain", nil), nil
	})
	if _, _, err := s.originalTTSSpeech(context.Background(), "Xin chào TN"); err == nil {
		t.Fatal("unapproved HLS chunk accepted")
	}
}

func TestOriginalCloudTTSInvalidInputAndBusyKeepExistingVerification(t *testing.T) {
	s := independentTestServer(t)
	originalTTSFixtureConfig(t, s)
	s.markSpeechProviderSuccess("tts", "original_tts")
	originalTTSFixtureNetwork(t, func(*http.Request) (*http.Response, error) {
		t.Error("invalid or busy request must not reach the original service")
		return originalTTSFixtureResponse(500, "text/plain", nil), nil
	})
	assertHealthy := func() {
		t.Helper()
		if health := s.speechProviderHealth(context.Background(), "tts", defaultRuntimeConfig()); health["healthy"] != true {
			t.Fatalf("local request rejection erased valid provider verification: %#v", health)
		}
	}
	for _, text := range []string{"", " \r\n\t", strings.Repeat("a", 501)} {
		if _, _, err := s.originalTTSSpeech(context.Background(), text); err == nil || tnAIErrorCode(err) != "INVALID_TEXT" {
			t.Fatalf("expected invalid text error, got %v", err)
		}
		assertHealthy()
	}
	previousSlots := originalTTSSlots
	originalTTSSlots = make(chan struct{}, 2)
	t.Cleanup(func() { originalTTSSlots = previousSlots })
	originalTTSSlots <- struct{}{}
	originalTTSSlots <- struct{}{}
	if _, _, err := s.originalTTSSpeech(context.Background(), "Xin chào TN"); err == nil || tnAIErrorCode(err) != "TTS_BUSY" {
		t.Fatalf("expected busy error for saturated synthesis slots, got %v", err)
	}
	assertHealthy()
}

func TestOriginalCloudManagedNewsAudioTypePreservesWAVAndSupportsMP3(t *testing.T) {
	for _, test := range []struct {
		input, mime, extension string
	}{
		{"", "audio/wav", ".wav"},
		{"audio/wav", "audio/wav", ".wav"},
		{"audio/mpeg", "audio/mpeg", ".mp3"},
		{"audio/ogg", "", ""},
		{"text/html", "", ""},
		{"../../escaped", "", ""},
	} {
		mime, extension := managedAudioType(test.input)
		if mime != test.mime || extension != test.extension {
			t.Errorf("managed MIME %q = (%q,%q), want (%q,%q)", test.input, mime, extension, test.mime, test.extension)
		}
	}
	wav := managedFixtureWAV()
	mergedWAV, err := managedMergeAudio([][]byte{wav, wav}, "audio/wav")
	if err != nil || len(mergedWAV) <= len(wav) || !bytes.Equal(mergedWAV[:4], []byte("RIFF")) {
		t.Fatalf("existing managed WAV merge was not preserved: bytes=%d err=%v", len(mergedWAV), err)
	}
	mp3 := originalTTSFixtureMP3()
	mergedMP3, err := managedMergeAudio([][]byte{mp3, mp3}, "audio/mpeg")
	if err != nil || !bytes.Equal(mergedMP3, append(append([]byte{}, mp3...), mp3...)) {
		t.Fatalf("managed MP3 frames were not joined intact: bytes=%d err=%v", len(mergedMP3), err)
	}
	for _, test := range []struct {
		mime  string
		parts [][]byte
	}{
		{"audio/ogg", [][]byte{mp3}},
		{"text/html", [][]byte{mp3}},
		{"audio/mpeg", nil},
		{"audio/mpeg", [][]byte{mp3, []byte("not MP3")}},
		{"audio/mpeg", [][]byte{mp3, wav}},
		{"audio/mpeg", [][]byte{mp3, mp3[:420]}},
		{"audio/wav", [][]byte{wav, mp3}},
	} {
		if _, err := managedMergeAudio(test.parts, test.mime); err == nil {
			t.Errorf("invalid managed audio part or MIME accepted: %s", test.mime)
		}
	}
}
