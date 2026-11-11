package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestDeepgramURLUsesVietnameseNovaAndPCM16Settings(t *testing.T) {
	got, err := deepgramURL(deepgramStreamEndpoint, "audio/pcm")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"model=nova-3", "language=vi", "encoding=linear16", "sample_rate=16000", "channels=1", "smart_format=true", "interim_results=true"} {
		if !strings.Contains(got, want) {
			t.Fatalf("URL %q lacks %q", got, want)
		}
	}
}

func TestDeepgramTranscriptExtractsFirstAlternative(t *testing.T) {
	text := deepgramTranscript(Object{"channel": map[string]any{"alternatives": []any{
		map[string]any{"transcript": "  mở bản đồ  "},
		map[string]any{"transcript": "other"},
	}}})
	if text != "mở bản đồ" {
		t.Fatalf("unexpected transcript %q", text)
	}
}

func TestDeepgramDecodeRequestUsesServerCredentialAndParsesTranscript(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Token test-key" {
			t.Errorf("unexpected auth header %q", r.Header.Get("Authorization"))
		}
		for key, want := range map[string]string{"model": "nova-3", "language": "vi", "encoding": "linear16", "sample_rate": "16000", "channels": "1"} {
			if got := r.URL.Query().Get(key); got != want {
				t.Errorf("query %s = %q, want %q", key, got, want)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"metadata":{"request_id":"dg-request"},"results":{"channels":[{"alternatives":[{"transcript":"mở bản đồ"}]}]}}`))
	}))
	defer server.Close()
	s := independentTestServer(t)
	result, err := s.deepgramDecodeRequest(context.Background(), server.URL, "audio/pcm", []byte{0, 0, 1, 0}, "test-key", server.Client(), nil)
	if err != nil {
		t.Fatal(err)
	}
	if result["text"] != "mở bản đồ" || result["request_id"] != "dg-request" {
		t.Fatalf("unexpected parsed response: %#v", result)
	}
}

func TestDeepgramPreflightRejectDoesNotInvalidateRecentProviderSuccess(t *testing.T) {
	s := independentTestServer(t)
	t.Setenv("TN_DEEPGRAM_ENABLED", "true")
	t.Setenv("TN_DEEPGRAM_API_KEY", "test-key")
	s.markSpeechProviderSuccess("asr", "deepgram")

	if _, err := s.deepgramDecode(context.Background(), "audio/wav", []byte("invalid wav")); err == nil {
		t.Fatal("expected invalid audio to be rejected before provider dispatch")
	}
	if health := s.cloudSpeechHealth("asr", "deepgram", true); health["healthy"] != true {
		t.Fatalf("client audio validation invalidated a recent provider success: %#v", health)
	}
}

func TestDeepgramUpstreamFailureInvalidatesRecentProviderSuccess(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()
	s := independentTestServer(t)
	t.Setenv("TN_DEEPGRAM_API_KEY", "test-key")
	s.markSpeechProviderSuccess("asr", "deepgram")

	_, err := s.deepgramDecodeRequest(context.Background(), server.URL, "audio/pcm", []byte{0, 0}, "test-key", server.Client(), func() {
		s.clearSpeechProviderSuccess("asr", "deepgram")
	})
	if err == nil {
		t.Fatal("expected upstream authentication failure")
	}
	if health := s.cloudSpeechHealth("asr", "deepgram", true); health["healthy"] == true {
		t.Fatalf("an upstream failure retained a previous verification: %#v", health)
	}
}

func withDeepgramBridge(t *testing.T, serveUpstream func(context.Context, *websocket.Conn), useClient func(context.Context, *websocket.Conn)) {
	t.Helper()
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Token test-key" {
			t.Error("unexpected upstream credential")
		}
		for key, want := range map[string]string{"model": "nova-3", "language": "vi", "interim_results": "true"} {
			if r.URL.Query().Get(key) != want {
				t.Errorf("upstream %s = %q, want %q", key, r.URL.Query().Get(key), want)
			}
		}
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Errorf("upstream websocket accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		serveUpstream(ctx, conn)
	}))
	defer upstream.Close()

	s := independentTestServer(t)
	downstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Errorf("downstream websocket accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
		defer cancel()
		endpoint := "wss" + strings.TrimPrefix(upstream.URL, "https")
		_ = s.deepgramStreamAt(ctx, conn, func(v Object) error {
			return conn.Write(ctx, websocket.MessageText, []byte(jsonText(v)))
		}, endpoint, "test-key", upstream.Client())
	}))
	defer downstream.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(downstream.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()
	if err = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"start","sample_rate":16000,"channels":1,"encoding":"pcm_s16le"}`)); err != nil {
		t.Fatal(err)
	}
	if event := readSpeechTestEvent(t, ctx, conn); str(event, "type") != "ready" {
		t.Fatalf("expected ready event, got %#v", event)
	}
	useClient(ctx, conn)
}

func readSpeechTestEvent(t *testing.T, ctx context.Context, conn *websocket.Conn) Object {
	t.Helper()
	typ, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read speech event: %v", err)
	}
	var event Object
	if typ != websocket.MessageText || json.Unmarshal(data, &event) != nil {
		t.Fatalf("expected JSON speech event, got %s", data)
	}
	return event
}

func expectDeepgramTestAudio(t *testing.T, ctx context.Context, conn *websocket.Conn) {
	t.Helper()
	typ, data, err := conn.Read(ctx)
	if err != nil || typ != websocket.MessageBinary || len(data) != 3200 {
		t.Errorf("expected 3200 bytes of upstream audio, got type=%v bytes=%d err=%v", typ, len(data), err)
	}
}

func expectDeepgramTestClose(t *testing.T, ctx context.Context, conn *websocket.Conn) {
	t.Helper()
	event := readSpeechTestEvent(t, ctx, conn)
	if str(event, "type") != "CloseStream" {
		t.Errorf("expected CloseStream after audio, got %#v", event)
	}
}

func writeSpeechTestFrame(t *testing.T, ctx context.Context, conn *websocket.Conn, typ websocket.MessageType, data []byte) {
	t.Helper()
	if err := conn.Write(ctx, typ, data); err != nil {
		t.Fatalf("write speech frame: %v", err)
	}
}

func TestDeepgramStreamBridgePreservesAllFinalSegments(t *testing.T) {
	withDeepgramBridge(t, func(ctx context.Context, conn *websocket.Conn) {
		expectDeepgramTestAudio(t, ctx, conn)
		expectDeepgramTestClose(t, ctx, conn)
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"Results","is_final":true,"channel":{"alternatives":[{"transcript":"m\u1edf"}]}}`))
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"Results","is_final":true,"channel":{"alternatives":[{"transcript":"b\u1ea3n \u0111\u1ed3"}]}}`))
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"Metadata","request_id":"complete-stream"}`))
	}, func(ctx context.Context, conn *websocket.Conn) {
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageBinary, make([]byte, 3200))
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"end"}`))
		event := readSpeechTestEvent(t, ctx, conn)
		if str(event, "type") != "final" || str(event, "text") != "m\u1edf b\u1ea3n \u0111\u1ed3" {
			t.Fatalf("expected complete aggregate transcript, got %#v", event)
		}
	})
}

func TestDeepgramStreamBridgeRelaysActualInterimBeforeEnd(t *testing.T) {
	withDeepgramBridge(t, func(ctx context.Context, conn *websocket.Conn) {
		expectDeepgramTestAudio(t, ctx, conn)
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"Results","is_final":false,"channel":{"alternatives":[{"transcript":"m\u1edf b\u1ea3n"}]}}`))
		expectDeepgramTestClose(t, ctx, conn)
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"Results","is_final":true,"channel":{"alternatives":[{"transcript":"m\u1edf b\u1ea3n \u0111\u1ed3"}]}}`))
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"Metadata"}`))
	}, func(ctx context.Context, conn *websocket.Conn) {
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageBinary, make([]byte, 3200))
		partial := readSpeechTestEvent(t, ctx, conn)
		if str(partial, "type") != "partial" || str(partial, "text") != "m\u1edf b\u1ea3n" {
			t.Fatalf("expected upstream interim before client end, got %#v", partial)
		}
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"end"}`))
		final := readSpeechTestEvent(t, ctx, conn)
		if str(final, "type") != "final" || str(final, "text") != "m\u1edf b\u1ea3n \u0111\u1ed3" {
			t.Fatalf("expected actual final transcript, got %#v", final)
		}
	})
}

func TestDeepgramStreamBridgeRejectsTruncatedTranscriptOnAbruptClose(t *testing.T) {
	withDeepgramBridge(t, func(ctx context.Context, conn *websocket.Conn) {
		expectDeepgramTestAudio(t, ctx, conn)
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"Results","is_final":true,"channel":{"alternatives":[{"transcript":"m\u1edf"}]}}`))
		expectDeepgramTestClose(t, ctx, conn)
		_ = conn.CloseNow()
	}, func(ctx context.Context, conn *websocket.Conn) {
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageBinary, make([]byte, 3200))
		if event := readSpeechTestEvent(t, ctx, conn); str(event, "type") != "partial" {
			t.Fatalf("expected the stable prefix before upstream failure, got %#v", event)
		}
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"end"}`))
		if event := readSpeechTestEvent(t, ctx, conn); str(event, "type") != "error" {
			t.Fatalf("truncated transcript must not be reported as final, got %#v", event)
		}
	})
}

func pcmTestWAV(audio []byte) []byte {
	wav := make([]byte, 44+len(audio))
	copy(wav, "RIFF")
	binary.LittleEndian.PutUint32(wav[4:8], uint32(len(wav)-8))
	copy(wav[8:16], "WAVEfmt ")
	binary.LittleEndian.PutUint32(wav[16:20], 16)
	binary.LittleEndian.PutUint16(wav[20:22], 1)
	binary.LittleEndian.PutUint16(wav[22:24], 1)
	binary.LittleEndian.PutUint32(wav[24:28], 16000)
	binary.LittleEndian.PutUint32(wav[28:32], 32000)
	binary.LittleEndian.PutUint16(wav[32:34], 2)
	binary.LittleEndian.PutUint16(wav[34:36], 16)
	copy(wav[36:40], "data")
	binary.LittleEndian.PutUint32(wav[40:44], uint32(len(audio)))
	copy(wav[44:], audio)
	return wav
}

func TestDeepgramPCMValidatesWAVFormatAndDurationBeforeBilling(t *testing.T) {
	audio := []byte{0, 0, 1, 0, 2, 0}
	wav := pcmTestWAV(audio)
	got, err := deepgramPCM("audio/wav", wav)
	if err != nil || !bytes.Equal(got, audio) {
		t.Fatalf("valid WAV must unwrap into unchanged PCM, got %v err=%v", got, err)
	}
	withJunk := append([]byte{}, wav[:12]...)
	withJunk = append(withJunk, []byte{'J', 'U', 'N', 'K', 3, 0, 0, 0, 1, 2, 3, 0}...)
	withJunk = append(withJunk, wav[12:]...)
	binary.LittleEndian.PutUint32(withJunk[4:8], uint32(len(withJunk)-8))
	if got, err = deepgramPCM("audio/wav", withJunk); err != nil || !bytes.Equal(got, audio) {
		t.Fatalf("valid RIFF chunks/padding must not affect PCM, got %v err=%v", got, err)
	}
	cases := map[string]func([]byte){
		"8kHz would undercount billed duration": func(b []byte) {
			binary.LittleEndian.PutUint32(b[24:28], 8000)
			binary.LittleEndian.PutUint32(b[28:32], 16000)
		},
		"8-bit would undercount billed duration": func(b []byte) {
			binary.LittleEndian.PutUint16(b[34:36], 8)
			binary.LittleEndian.PutUint16(b[32:34], 1)
			binary.LittleEndian.PutUint32(b[28:32], 16000)
		},
		"stereo":                  func(b []byte) { binary.LittleEndian.PutUint16(b[22:24], 2) },
		"float compression":       func(b []byte) { binary.LittleEndian.PutUint16(b[20:22], 3) },
		"oversized chunk":         func(b []byte) { binary.LittleEndian.PutUint32(b[16:20], 999999) },
		"missing data":            func(b []byte) { copy(b[36:40], "JUNK") },
		"invalid byte rate":       func(b []byte) { binary.LittleEndian.PutUint32(b[28:32], 1) },
		"invalid block alignment": func(b []byte) { binary.LittleEndian.PutUint16(b[32:34], 1) },
		"RIFF length mismatch":    func(b []byte) { binary.LittleEndian.PutUint32(b[4:8], 9999) },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			invalid := append([]byte{}, wav...)
			mutate(invalid)
			if _, err := deepgramPCM("audio/wav", invalid); err == nil {
				t.Fatal("invalid WAV reached the provider")
			}
		})
	}
	for name, input := range map[string][]byte{"truncated WAV": wav[:len(wav)-1], "fake WAV": make([]byte, 44), "overlong WAV": pcmTestWAV(make([]byte, maxDeepgramAudioBytes+2))} {
		t.Run(name, func(t *testing.T) {
			if _, err := deepgramPCM("audio/wav", input); err == nil {
				t.Fatal("invalid WAV reached the provider")
			}
		})
	}
	for _, input := range [][]byte{nil, {0}, make([]byte, maxDeepgramAudioBytes+2)} {
		if _, err := deepgramPCM("audio/pcm", input); err == nil {
			t.Fatalf("invalid raw PCM of %d bytes reached the provider", len(input))
		}
	}
}

func TestDeepgramQuotaReservesAndRefundsUnusedStreamWindow(t *testing.T) {
	t.Setenv("TN_DEEPGRAM_MONTHLY_AUDIO_MINUTES", "1")
	s := independentTestServer(t)
	settle, err := reserveDeepgramAudio(s, deepgramReserveMS)
	if err != nil {
		t.Fatal(err)
	}
	settle(5000)
	settle(0) // A second settlement must not refund the same reservation twice.

	for i := 0; i < 3; i++ {
		settle, err = reserveDeepgramAudio(s, deepgramReserveMS)
		if err != nil {
			t.Fatalf("15-second reservation %d should fit: %v", i, err)
		}
		settle(deepgramReserveMS)
	}
	settle, err = reserveDeepgramAudio(s, 10000)
	if err != nil {
		t.Fatalf("the final 10 seconds should fit: %v", err)
	}
	settle(10000)
	if _, err = reserveDeepgramAudio(s, 1); err == nil {
		t.Fatal("monthly quota admitted usage beyond its configured limit")
	}
}

func TestAzureSSMLUsesFixedVietnameseVoiceAndEscapesText(t *testing.T) {
	ssml := azureSSML(`bật nhạc & nói <xin chào>`)
	if !strings.Contains(ssml, `xml:lang="vi-VN"`) || !strings.Contains(ssml, azureVoice) {
		t.Fatalf("SSML omitted Vietnamese voice selection: %s", ssml)
	}
	if !strings.Contains(ssml, `bật nhạc &amp; nói &lt;xin chào&gt;`) {
		t.Fatalf("SSML did not escape user text: %s", ssml)
	}
}

func TestAzureTTSRequiresSafeRegionAndCredentials(t *testing.T) {
	t.Setenv("TN_AZURE_SPEECH_KEY", "test-only")
	t.Setenv("TN_AZURE_SPEECH_REGION", "southeastasia")
	if _, _, err := azureTTSReady(); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TN_AZURE_SPEECH_REGION", "https://attacker.invalid")
	if _, _, err := azureTTSReady(); err == nil {
		t.Fatal("Azure region accepted an endpoint-injection value")
	}
}

func TestAzureFreeTierUsageCapIsPersistent(t *testing.T) {
	s := independentTestServer(t)
	if err := reserveAzureChars(s, 450000); err != nil {
		t.Fatal(err)
	}
	if err := reserveAzureChars(s, 1); err == nil {
		t.Fatal("Azure TTS usage exceeded the application-side free-tier cap")
	}
}

func TestDeepgramBudgetIsAtomicAcrossConcurrentReservations(t *testing.T) {
	t.Setenv("TN_DEEPGRAM_MONTHLY_AUDIO_MINUTES", "1")
	s := independentTestServer(t)
	results := make(chan error, 20)
	for i := 0; i < cap(results); i++ {
		go func() {
			settle, err := reserveDeepgramAudio(s, deepgramReserveMS)
			if err == nil {
				settle(deepgramReserveMS)
			}
			results <- err
		}()
	}
	accepted := 0
	for i := 0; i < cap(results); i++ {
		if err := <-results; err == nil {
			accepted++
		}
	}
	if accepted != 4 {
		t.Fatalf("one-minute budget must atomically admit four 15-second requests, admitted %d", accepted)
	}
	for _, period := range []string{time.Now().UTC().Format("2006-01"), "lifetime"} {
		var used int64
		if err := s.db.QueryRow(`SELECT audio_ms FROM deepgram_usage WHERE month_utc=?`, period).Scan(&used); err != nil || used != 60000 {
			t.Fatalf("failed reservations must roll back both counters, %s used=%d err=%v", period, used, err)
		}
	}
}

func TestDeepgramBudgetSurvivesProcessRestartAndRetainsUnsettledReservation(t *testing.T) {
	t.Setenv("TN_DEEPGRAM_MONTHLY_AUDIO_MINUTES", "1")
	s := independentTestServer(t)
	if _, err := reserveDeepgramAudio(s, deepgramReserveMS); err != nil {
		t.Fatal(err)
	}
	// Simulate losing the settlement callback in a crash: the full reservation
	// must remain charged when another process opens the same database.
	if err := s.db.Close(); err != nil {
		t.Fatal(err)
	}
	restarted, err := openServer(s.cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.db.Close()
	for i := 0; i < 3; i++ {
		settle, err := reserveDeepgramAudio(restarted, deepgramReserveMS)
		if err != nil {
			t.Fatalf("expected remaining 15-second reservation %d: %v", i, err)
		}
		settle(deepgramReserveMS)
	}
	if _, err := reserveDeepgramAudio(restarted, 1); err == nil {
		t.Fatal("restarting reset usage or refunded an unsettled reservation")
	}
}

func TestDeepgramLifetimeBudgetCannotResetWithMonthlyUsage(t *testing.T) {
	s := independentTestServer(t)
	settle, err := reserveDeepgramAudio(s, 1)
	if err != nil {
		t.Fatal(err)
	}
	settle(1)
	if _, err := s.db.Exec(`UPDATE deepgram_usage SET audio_ms=59999999 WHERE month_utc='lifetime'`); err != nil {
		t.Fatal(err)
	}
	if _, err := reserveDeepgramAudio(s, 2); err == nil {
		t.Fatal("monthly availability bypassed the nonresetting lifetime budget")
	}
	var used int64
	if err := s.db.QueryRow(`SELECT audio_ms FROM deepgram_usage WHERE month_utc=?`, time.Now().UTC().Format("2006-01")).Scan(&used); err != nil || used != 1 {
		t.Fatalf("lifetime-budget failure changed monthly usage: used=%d err=%v", used, err)
	}
	settle, err = reserveDeepgramAudio(s, 1)
	if err != nil {
		t.Fatalf("final lifetime-budget millisecond must fit: %v", err)
	}
	settle(1)
	if _, err := reserveDeepgramAudio(s, 1); err == nil {
		t.Fatal("lifetime budget admitted usage above 1000 minutes")
	}
}

func TestCloudSpeechVerificationBindsToCurrentCredentialsAndCanBeInvalidated(t *testing.T) {
	s := independentTestServer(t)
	t.Setenv("TN_DEEPGRAM_API_KEY", "test-first-key")
	if health := s.cloudSpeechHealth("asr", "deepgram", true); health["healthy"] == true {
		t.Fatalf("unverified credential was healthy: %#v", health)
	}
	s.markSpeechProviderSuccess("asr", "deepgram")
	if health := s.cloudSpeechHealth("asr", "deepgram", true); health["healthy"] != true {
		t.Fatalf("successful credential was not verified: %#v", health)
	}
	t.Setenv("TN_DEEPGRAM_API_KEY", "test-rotated-key")
	if health := s.cloudSpeechHealth("asr", "deepgram", true); health["healthy"] == true {
		t.Fatalf("rotated credential inherited a previous credential's success: %#v", health)
	}
	s.markSpeechProviderSuccess("asr", "deepgram")
	s.clearSpeechProviderSuccess("asr", "deepgram")
	if health := s.cloudSpeechHealth("asr", "deepgram", true); health["healthy"] == true {
		t.Fatalf("failed credential retained a previous success: %#v", health)
	}
	if health := s.cloudSpeechHealth("asr", "deepgram", false); health["healthy"] == true {
		t.Fatalf("disabled provider was healthy: %#v", health)
	}

	t.Setenv("TN_AZURE_SPEECH_KEY", "test-azure-key")
	t.Setenv("TN_AZURE_SPEECH_REGION", "southeastasia")
	s.markSpeechProviderSuccess("tts", "azure")
	t.Setenv("TN_AZURE_SPEECH_REGION", "eastus")
	if health := s.cloudSpeechHealth("tts", "azure", true); health["healthy"] == true {
		t.Fatalf("different Azure region inherited a previous region's success: %#v", health)
	}
}

func TestDeepgramStreamCancelAfterEndStopsPendingFinalization(t *testing.T) {
	endReceived := make(chan struct{})
	upstreamClosed := make(chan struct{})
	withDeepgramBridge(t, func(ctx context.Context, conn *websocket.Conn) {
		expectDeepgramTestAudio(t, ctx, conn)
		expectDeepgramTestClose(t, ctx, conn)
		close(endReceived)
		// Hold finalization open until the user cancels. Cancellation must
		// close this connection without waiting for the eight-second timeout.
		_, _, err := conn.Read(ctx)
		if err == nil {
			t.Error("cancelled upstream unexpectedly received another frame")
		}
		close(upstreamClosed)
	}, func(ctx context.Context, conn *websocket.Conn) {
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageBinary, make([]byte, 3200))
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"end"}`))
		select {
		case <-endReceived:
		case <-time.After(2 * time.Second):
			t.Fatal("upstream never entered finalization")
		}
		writeSpeechTestFrame(t, ctx, conn, websocket.MessageText, []byte(`{"type":"cancel"}`))
		cancelCtx, stop := context.WithTimeout(ctx, 2*time.Second)
		defer stop()
		_, data, err := conn.Read(cancelCtx)
		if err == nil {
			t.Fatalf("cancel after end must close without final/error event, got %s", data)
		}
		if cancelCtx.Err() != nil {
			t.Fatal("cancel did not promptly close downstream")
		}
		select {
		case <-upstreamClosed:
		case <-time.After(2 * time.Second):
			t.Fatal("cancel did not promptly close pending upstream")
		}
	})
}
