package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const (
	deepgramListenEndpoint = "https://api.deepgram.com/v1/listen"
	deepgramStreamEndpoint = "wss://api.deepgram.com/v1/listen"
	maxDeepgramAudioBytes  = 480000 // 15 seconds of 16 kHz mono PCM16.
	deepgramReserveMS      = 15000
	azureVoice             = "vi-VN-HoaiMyNeural"
)

var deepgramSlots = make(chan struct{}, 2)

func deepgramSelected() bool { return strings.EqualFold(os.Getenv("TN_ASR_PROVIDER"), "deepgram") }

func deepgramReady() error {
	if os.Getenv("TN_DEEPGRAM_ENABLED") != "true" {
		return problem(503, "ASR_PROVIDER_DISABLED", "Deepgram is configured but not enabled")
	}
	if strings.TrimSpace(os.Getenv("TN_DEEPGRAM_API_KEY")) == "" {
		return problem(503, "ASR_NOT_CONFIGURED", "Deepgram credentials are not configured")
	}
	return nil
}

func azureTTSSelected() bool { return strings.EqualFold(os.Getenv("TN_TTS_PROVIDER"), "azure") }

func (s *Server) speechProviderHealth(ctx context.Context, kind string, cfg RuntimeConfig) Object {
	if kind == "asr" && deepgramSelected() {
		return s.cloudSpeechHealth("asr", "deepgram", deepgramReady() == nil)
	}
	if kind == "tts" && azureTTSSelected() {
		_, _, err := azureTTSReady()
		return s.cloudSpeechHealth("tts", "azure", err == nil)
	}
	if kind == "tts" && originalTTSSelected() {
		p, err := s.originalTTSProvider()
		check := s.cloudSpeechHealth("tts", "original_tts", err == nil)
		check["external_dependency"] = true
		if err == nil {
			check["speaker_id"] = str(p.Options, "speaker_id")
			check["endpoint"] = originalTTSEndpoint
		}
		return check
	}
	selector := strings.ToLower(os.Getenv("TN_ASR_PROVIDER"))
	endpoint := cfg.ASRURL
	if kind == "tts" {
		selector, endpoint = strings.ToLower(os.Getenv("TN_TTS_PROVIDER")), cfg.TTSURL
	}
	if selector != "" && selector != "local" {
		return Object{"healthy": false, "status": "not_configured", "provider": selector}
	}
	check := speechWorkerHealth(ctx, endpoint)
	check["provider"] = "local"
	return check
}

func azureTTSReady() (string, string, error) {
	key := strings.TrimSpace(os.Getenv("TN_AZURE_SPEECH_KEY"))
	region := strings.TrimSpace(os.Getenv("TN_AZURE_SPEECH_REGION"))
	if key == "" || region == "" {
		return "", "", problem(503, "TTS_NOT_CONFIGURED", "Azure Speech F0 credentials are not configured")
	}
	for _, r := range region {
		if !(r >= 'a' && r <= 'z') && !(r >= '0' && r <= '9') && r != '-' {
			return "", "", problem(503, "TTS_CONFIG_INVALID", "Azure Speech region is invalid")
		}
	}
	return key, region, nil
}

func (s *Server) markSpeechProviderSuccess(kind, provider string) {
	if _, err := s.db.Exec(`CREATE TABLE IF NOT EXISTS speech_provider_checks(kind TEXT NOT NULL, provider TEXT NOT NULL, last_success_utc TEXT NOT NULL, PRIMARY KEY(kind,provider))`); err != nil {
		return
	}
	_, _ = s.db.Exec(`INSERT INTO speech_provider_checks(kind,provider,last_success_utc) VALUES(?,?,?) ON CONFLICT(kind,provider) DO UPDATE SET last_success_utc=excluded.last_success_utc`, kind, s.speechProviderID(provider), time.Now().UTC().Format(time.RFC3339))
}

func speechProviderIdentity(provider string) string {
	value := os.Getenv("TN_DEEPGRAM_API_KEY") + "|nova-3|vi"
	if provider == "azure" {
		value = os.Getenv("TN_AZURE_SPEECH_KEY") + "|" + os.Getenv("TN_AZURE_SPEECH_REGION") + "|" + azureVoice
	}
	fingerprint := sha256.Sum256([]byte(value))
	return provider + ":" + fmt.Sprintf("%x", fingerprint[:])
}

func (s *Server) clearSpeechProviderSuccess(kind, provider string) {
	_, _ = s.db.Exec(`DELETE FROM speech_provider_checks WHERE kind=? AND provider=?`, kind, s.speechProviderID(provider))
}

func (s *Server) cloudSpeechHealth(kind, provider string, configured bool) Object {
	if !configured {
		return Object{"healthy": false, "status": "not_configured", "provider": provider}
	}
	var checked string
	if err := s.db.QueryRow(`SELECT last_success_utc FROM speech_provider_checks WHERE kind=? AND provider=?`, kind, s.speechProviderID(provider)).Scan(&checked); err != nil {
		return Object{"healthy": false, "status": "configured_not_verified", "provider": provider}
	}
	verifiedAt, err := time.Parse(time.RFC3339, checked)
	if err != nil || time.Since(verifiedAt) > 24*time.Hour || time.Until(verifiedAt) > time.Minute {
		return Object{"healthy": false, "status": "verification_stale", "provider": provider}
	}
	return Object{"healthy": true, "status": "verified_recently", "provider": provider, "last_success_at": checked}
}

func deepgramMonthLimitMS() int64 {
	minutes, err := strconv.ParseInt(strings.TrimSpace(os.Getenv("TN_DEEPGRAM_MONTHLY_AUDIO_MINUTES")), 10, 32)
	if err != nil || minutes < 1 || minutes > 10080 {
		minutes = 120
	}
	return minutes * 60 * 1000
}

// reserveDeepgramAudio reserves capacity before any audio is sent. The full
// 15-second stream window is reserved up front, then unused milliseconds are
// returned after completion. A process crash conservatively leaves the full
// reservation counted until the UTC month rolls over.
func reserveDeepgramAudio(s *Server, reserveMS int64) (func(int64), error) {
	if reserveMS < 1 || reserveMS > deepgramReserveMS {
		return nil, problem(400, "INVALID_AUDIO_SIZE", "Audio exceeds the 15-second Deepgram request limit")
	}
	if _, err := s.db.Exec(`CREATE TABLE IF NOT EXISTS deepgram_usage(month_utc TEXT PRIMARY KEY, audio_ms INTEGER NOT NULL CHECK(audio_ms >= 0))`); err != nil {
		return nil, problem(503, "ASR_QUOTA_UNAVAILABLE", "Deepgram usage budget is unavailable")
	}
	month := time.Now().UTC().Format("2006-01")
	tx, err := s.db.Begin()
	if err != nil {
		return nil, problem(503, "ASR_QUOTA_UNAVAILABLE", "Deepgram usage budget is unavailable")
	}
	defer tx.Rollback()
	if _, err = tx.Exec(`INSERT OR IGNORE INTO deepgram_usage(month_utc,audio_ms) VALUES(?,0)`, month); err != nil {
		return nil, problem(503, "ASR_QUOTA_UNAVAILABLE", "Deepgram usage budget is unavailable")
	}
	// New-account credit is finite. Keep a non-resetting 1,000-minute ceiling
	// as well as the monthly cap; this is shared by every app user and key.
	if _, err = tx.Exec(`INSERT OR IGNORE INTO deepgram_usage(month_utc,audio_ms) VALUES('lifetime',0)`); err != nil {
		return nil, problem(503, "ASR_QUOTA_UNAVAILABLE", "Deepgram usage budget is unavailable")
	}
	lifetimeResult, err := tx.Exec(`UPDATE deepgram_usage SET audio_ms=audio_ms+? WHERE month_utc='lifetime' AND audio_ms+?<=60000000`, reserveMS, reserveMS)
	if err != nil {
		return nil, problem(503, "ASR_QUOTA_UNAVAILABLE", "Deepgram usage budget is unavailable")
	}
	lifetimeChanged, err := lifetimeResult.RowsAffected()
	if err != nil || lifetimeChanged != 1 {
		return nil, problem(429, "ASR_CREDIT_BUDGET_LIMIT", "The lifetime Deepgram credit budget has been reached")
	}
	result, err := tx.Exec(`UPDATE deepgram_usage SET audio_ms=audio_ms+? WHERE month_utc=? AND audio_ms+?<=?`, reserveMS, month, reserveMS, deepgramMonthLimitMS())
	if err != nil {
		return nil, problem(503, "ASR_QUOTA_UNAVAILABLE", "Deepgram usage budget is unavailable")
	}
	changed, err := result.RowsAffected()
	if err != nil || changed != 1 {
		return nil, problem(429, "ASR_MONTHLY_LIMIT", "The monthly Deepgram audio limit has been reached")
	}
	if err = tx.Commit(); err != nil {
		return nil, problem(503, "ASR_QUOTA_UNAVAILABLE", "Deepgram usage budget is unavailable")
	}
	var once sync.Once
	settle := func(actualMS int64) {
		once.Do(func() {
			if actualMS < 0 {
				actualMS = 0
			}
			if actualMS > reserveMS {
				actualMS = reserveMS
			}
			_, _ = s.db.Exec(`UPDATE deepgram_usage SET audio_ms=max(0,audio_ms-?) WHERE month_utc IN (?, 'lifetime')`, reserveMS-actualMS, month)
		})
	}
	return settle, nil
}

func pcm16DurationMS(audioBytes int64) int64 {
	if audioBytes <= 0 {
		return 0
	}
	return (audioBytes*1000 + 31999) / 32000
}

func deepgramURL(endpoint, contentType string) (string, error) {
	u, err := url.Parse(endpoint)
	if err != nil || (u.Scheme != "https" && u.Scheme != "wss") || u.Host == "" || u.User != nil {
		return "", errors.New("invalid fixed Deepgram endpoint")
	}
	q := u.Query()
	q.Set("model", "nova-3")
	q.Set("language", "vi")
	q.Set("smart_format", "true")
	if u.Scheme == "wss" {
		q.Set("interim_results", "true")
	}
	if contentType == "audio/pcm" || contentType == "application/octet-stream" {
		q.Set("encoding", "linear16")
		q.Set("sample_rate", "16000")
		q.Set("channels", "1")
	}
	u.RawQuery = q.Encode()
	return u.String(), nil
}

func deepgramTranscript(result Object) string {
	channel, _ := result["channel"].(map[string]any)
	alternatives, _ := channel["alternatives"].([]any)
	if len(alternatives) == 0 {
		return ""
	}
	first, _ := alternatives[0].(map[string]any)
	return strings.TrimSpace(str(first, "transcript"))
}

func (s *Server) deepgramDecode(ctx context.Context, contentType string, audio []byte) (Object, error) {
	if err := deepgramReady(); err != nil {
		return nil, err
	}
	pcm, err := deepgramPCM(contentType, audio)
	if err != nil {
		return nil, err
	}
	select {
	case deepgramSlots <- struct{}{}:
		defer func() { <-deepgramSlots }()
	default:
		return nil, problem(503, "SPEECH_BUSY", "Speech service is busy; retry shortly")
	}
	reserveMS := pcm16DurationMS(int64(len(pcm)))
	settle, err := reserveDeepgramAudio(s, reserveMS)
	if err != nil {
		return nil, err
	}
	defer settle(reserveMS)
	return s.deepgramDecodeRequest(ctx, deepgramListenEndpoint, "audio/pcm", pcm, os.Getenv("TN_DEEPGRAM_API_KEY"), &http.Client{Timeout: 35 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, func() {
		s.clearSpeechProviderSuccess("asr", "deepgram")
	})
}

// Parse RIFF chunks rather than assuming a 44-byte header. The byte budget is
// meaningful only after enforcing the exact PCM format used to calculate it.
func deepgramPCM(contentType string, audio []byte) ([]byte, error) {
	invalid := func() ([]byte, error) {
		return nil, problem(400, "INVALID_AUDIO_FORMAT", "PCM16 mono 16000 Hz audio required")
	}
	if contentType == "audio/wav" {
		if len(audio) < 44 || len(audio) > maxDeepgramAudioBytes+1024 || string(audio[:4]) != "RIFF" || string(audio[8:12]) != "WAVE" || int64(binary.LittleEndian.Uint32(audio[4:8]))+8 != int64(len(audio)) {
			return invalid()
		}
		var pcm []byte
		formatOK, dataSeen := false, false
		for offset := 12; offset < len(audio); {
			if len(audio)-offset < 8 {
				return invalid()
			}
			size := int64(binary.LittleEndian.Uint32(audio[offset+4 : offset+8]))
			end := int64(offset) + 8 + size
			paddedEnd := end + size%2
			if end > int64(len(audio)) || paddedEnd > int64(len(audio)) {
				return invalid()
			}
			switch string(audio[offset : offset+4]) {
			case "fmt ":
				if formatOK || size < 16 {
					return invalid()
				}
				f := audio[offset+8 : int(end)]
				if binary.LittleEndian.Uint16(f[:2]) != 1 || binary.LittleEndian.Uint16(f[2:4]) != 1 || binary.LittleEndian.Uint32(f[4:8]) != 16000 || binary.LittleEndian.Uint32(f[8:12]) != 32000 || binary.LittleEndian.Uint16(f[12:14]) != 2 || binary.LittleEndian.Uint16(f[14:16]) != 16 {
					return invalid()
				}
				formatOK = true
			case "data":
				if dataSeen {
					return invalid()
				}
				pcm, dataSeen = audio[offset+8:int(end)], true
			}
			offset = int(paddedEnd)
		}
		if !formatOK || !dataSeen {
			return invalid()
		}
		audio = pcm
	} else if contentType != "audio/pcm" && contentType != "application/octet-stream" {
		return invalid()
	}
	if len(audio) == 0 || len(audio) > maxDeepgramAudioBytes {
		return nil, problem(413, "INVALID_AUDIO_SIZE", "Audio must contain at most 15 seconds of PCM16 mono")
	}
	if len(audio)%2 != 0 {
		return invalid()
	}
	return audio, nil
}

func (s *Server) deepgramDecodeRequest(ctx context.Context, baseEndpoint, contentType string, audio []byte, apiKey string, client *http.Client, onUpstreamFailure func()) (Object, error) {
	invalidate := func() {
		if onUpstreamFailure != nil {
			onUpstreamFailure()
		}
	}
	endpoint, err := deepgramURL(baseEndpoint, contentType)
	if err != nil {
		return nil, problem(503, "ASR_CONFIG_INVALID", "Deepgram endpoint is invalid")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(audio))
	if err != nil {
		return nil, problem(503, "ASR_CONFIG_INVALID", "Deepgram request could not be constructed")
	}
	request.Header.Set("Authorization", "Token "+apiKey)
	request.Header.Set("Content-Type", contentType)
	request.Header.Set("Accept", "application/json")
	response, err := client.Do(request)
	if err != nil {
		invalidate()
		return nil, problem(503, "ASR_UPSTREAM_UNAVAILABLE", "Deepgram transcription could not be completed")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		invalidate()
		return nil, deepgramHTTPError(response.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, 1<<20+1))
	if err != nil || len(raw) > 1<<20 {
		invalidate()
		return nil, problem(502, "ASR_RESPONSE_INVALID", "Deepgram returned an invalid response")
	}
	var responseBody Object
	if json.Unmarshal(raw, &responseBody) != nil {
		invalidate()
		return nil, problem(502, "ASR_RESPONSE_INVALID", "Deepgram returned an invalid response")
	}
	results, _ := responseBody["results"].(map[string]any)
	channels, _ := results["channels"].([]any)
	if len(channels) == 0 {
		invalidate()
		return nil, problem(502, "ASR_RESPONSE_INVALID", "Deepgram response omitted transcription results")
	}
	channel, _ := channels[0].(map[string]any)
	text := deepgramTranscript(Object{"channel": channel})
	if text == "" {
		invalidate()
		return nil, problem(422, "NO_SPEECH", "No speech was recognized")
	}
	metadata, _ := responseBody["metadata"].(map[string]any)
	s.markSpeechProviderSuccess("asr", "deepgram")
	return Object{"text": text, "request_id": str(metadata, "request_id"), "source": "deepgram", "language": "vi"}, nil
}

func deepgramHTTPError(status int) error {
	switch status {
	case http.StatusUnauthorized, http.StatusForbidden:
		return problem(503, "ASR_CREDENTIAL_INVALID", "Deepgram rejected the configured credential")
	case http.StatusPaymentRequired:
		return problem(503, "ASR_CREDIT_EXHAUSTED", "Deepgram credit is exhausted")
	case http.StatusTooManyRequests:
		return problem(503, "ASR_RATE_LIMITED", "Deepgram rate limit was reached")
	case http.StatusBadRequest, http.StatusUnsupportedMediaType, http.StatusRequestEntityTooLarge:
		return problem(400, "ASR_INVALID_AUDIO", "Deepgram rejected the audio format")
	default:
		return problem(503, "ASR_UPSTREAM_UNAVAILABLE", "Deepgram transcription could not be completed")
	}
}

func speechErrorEvent(err error, fallbackCode, fallbackMessage string) Object {
	var apiErr *apiError
	if errors.As(err, &apiErr) {
		return Object{"type": "error", "error": Object{"code": apiErr.Code, "message": apiErr.Message}}
	}
	return Object{"type": "error", "error": Object{"code": fallbackCode, "message": fallbackMessage}}
}

type speechSocketRead struct {
	typ  websocket.MessageType
	data []byte
	err  error
}

func (s *Server) deepgramStream(ctx context.Context, down *websocket.Conn, write func(Object) error) error {
	originalWrite := write
	write = func(event Object) error {
		if str(event, "type") == "error" {
			s.clearSpeechProviderSuccess("asr", "deepgram")
		}
		return originalWrite(event)
	}
	if err := deepgramReady(); err != nil {
		write(speechErrorEvent(err, "ASR_NOT_READY", "Deepgram speech recognition is not ready"))
		return nil
	}
	return s.deepgramStreamAt(ctx, down, write, deepgramStreamEndpoint, os.Getenv("TN_DEEPGRAM_API_KEY"), &http.Client{Timeout: 90 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }})
}

func (s *Server) deepgramStreamAt(ctx context.Context, down *websocket.Conn, write func(Object) error, baseEndpoint, apiKey string, httpClient *http.Client) error {
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	typ, raw, err := down.Read(ctx)
	if err != nil {
		return nil
	}
	var start Object
	if typ != websocket.MessageText || json.Unmarshal(raw, &start) != nil || str(start, "type") != "start" || integer(start, "sample_rate", 0) != 16000 || integer(start, "channels", 0) != 1 || str(start, "encoding") != "pcm_s16le" {
		write(Object{"type": "error", "error": Object{"code": "INVALID_START", "message": "16000 Hz mono PCM16 start frame required"}})
		return nil
	}
	select {
	case deepgramSlots <- struct{}{}:
		defer func() { <-deepgramSlots }()
	default:
		write(Object{"type": "error", "error": Object{"code": "SPEECH_BUSY", "message": "Speech service is busy; retry shortly"}})
		return nil
	}
	settle, err := reserveDeepgramAudio(s, deepgramReserveMS)
	if err != nil {
		write(speechErrorEvent(err, "ASR_QUOTA_UNAVAILABLE", "Deepgram usage budget is unavailable"))
		return nil
	}
	var sentBytes int64
	defer func() { settle(pcm16DurationMS(sentBytes)) }()
	endpoint, err := deepgramURL(baseEndpoint, "audio/pcm")
	if err != nil {
		write(Object{"type": "error", "error": Object{"code": "ASR_CONFIG_INVALID", "message": "Deepgram endpoint is invalid"}})
		return nil
	}
	headers := http.Header{}
	headers.Set("Authorization", "Token "+apiKey)
	up, response, err := websocket.Dial(ctx, endpoint, &websocket.DialOptions{HTTPHeader: headers, HTTPClient: httpClient})
	if err != nil {
		upErr := problem(503, "ASR_UPSTREAM_UNAVAILABLE", "Deepgram stream connection failed")
		if response != nil {
			upErr = deepgramHTTPError(response.StatusCode)
			if response.Body != nil {
				_ = response.Body.Close()
			}
		}
		write(speechErrorEvent(upErr, "ASR_UPSTREAM_UNAVAILABLE", "Deepgram stream connection failed"))
		return nil
	}
	defer up.CloseNow()
	up.SetReadLimit(1 << 20)
	bridgeCtx, stop := context.WithCancel(ctx)
	defer stop()
	if err = write(Object{"type": "ready"}); err != nil {
		return nil
	}
	downReads := make(chan speechSocketRead, 1)
	upReads := make(chan speechSocketRead, 1)
	go readSpeechSocket(bridgeCtx, down, downReads)
	go readSpeechSocket(bridgeCtx, up, upReads)
	requestID := ident()
	stable := []string{}
	lastPartial := ""
	ending := false
	idleTimer := time.NewTimer(8 * time.Second)
	defer idleTimer.Stop()
	var endTimer <-chan time.Time
	var timer *time.Timer
	closeTimer := func() {
		if timer != nil {
			timer.Stop()
		}
	}
	defer closeTimer()
	for {
		select {
		case frame := <-downReads:
			if frame.err != nil {
				return nil
			}
			if ending {
				var control Object
				if frame.typ == websocket.MessageText && json.Unmarshal(frame.data, &control) == nil && str(control, "type") == "cancel" {
					return nil
				}
				write(Object{"type": "error", "error": Object{"code": "FRAME_AFTER_END", "message": "Audio frames cannot follow the end frame"}})
				return nil
			}
			if frame.typ == websocket.MessageBinary {
				if len(frame.data) == 0 || len(frame.data)%2 != 0 || sentBytes+int64(len(frame.data)) > maxDeepgramAudioBytes {
					write(Object{"type": "error", "error": Object{"code": "AUDIO_LIMIT", "message": "PCM audio must be aligned and no longer than 15 seconds"}})
					return nil
				}
				sentBytes += int64(len(frame.data)) // Count attempted writes conservatively.
				if err = up.Write(bridgeCtx, websocket.MessageBinary, frame.data); err != nil {
					write(Object{"type": "error", "error": Object{"code": "ASR_UPSTREAM_UNAVAILABLE", "message": "Deepgram stream could not accept audio"}})
					return nil
				}
				idleTimer.Reset(8 * time.Second)
				continue
			}
			var command Object
			if frame.typ != websocket.MessageText || json.Unmarshal(frame.data, &command) != nil {
				write(Object{"type": "error", "error": Object{"code": "INVALID_CONTROL", "message": "Expected end or cancel control frame"}})
				return nil
			}
			switch str(command, "type") {
			case "cancel":
				return nil
			case "end":
				if sentBytes == 0 {
					write(Object{"type": "error", "error": Object{"code": "EMPTY_AUDIO", "message": "No audio was received"}})
					return nil
				}
				ending = true
				idleTimer.Stop()
				err = up.Write(bridgeCtx, websocket.MessageText, []byte(`{"type":"CloseStream"}`))
				if err != nil {
					write(Object{"type": "error", "error": Object{"code": "ASR_UPSTREAM_UNAVAILABLE", "message": "Deepgram stream could not be finalized"}})
					return nil
				}
				timer = time.NewTimer(8 * time.Second)
				endTimer = timer.C
			default:
				write(Object{"type": "error", "error": Object{"code": "INVALID_CONTROL", "message": "Expected end or cancel control frame"}})
				return nil
			}
		case message := <-upReads:
			if message.err != nil {
				if ending && websocket.CloseStatus(message.err) == websocket.StatusNormalClosure {
					return s.emitDeepgramFinal(write, stable, requestID)
				} else if ctx.Err() == nil {
					write(Object{"type": "error", "error": Object{"code": "ASR_UPSTREAM_UNAVAILABLE", "message": "Deepgram stream ended unexpectedly"}})
				}
				return nil
			}
			if message.typ != websocket.MessageText {
				continue
			}
			var event Object
			if json.Unmarshal(message.data, &event) != nil {
				continue
			}
			switch str(event, "type") {
			case "Error":
				write(Object{"type": "error", "error": Object{"code": "ASR_UPSTREAM_ERROR", "message": "Deepgram could not process this audio"}})
				return nil
			case "Metadata":
				if ending {
					return s.emitDeepgramFinal(write, stable, requestID)
				}
			}
			if str(event, "type") != "Results" {
				continue
			}
			text := deepgramTranscript(event)
			if text == "" {
				continue
			}
			if event["is_final"] == true {
				stable = append(stable, text)
			}
			combined := strings.TrimSpace(strings.Join(stable, " "))
			if event["is_final"] != true {
				combined = strings.TrimSpace(combined + " " + text)
			}
			if combined != "" && combined != lastPartial && !ending {
				lastPartial = combined
				if err = write(Object{"type": "partial", "text": combined, "request_id": requestID, "source": "deepgram"}); err != nil {
					return nil
				}
			}
		case <-endTimer:
			write(Object{"type": "error", "error": Object{"code": "ASR_TIMEOUT", "message": "Deepgram did not return a final transcript in time"}})
			return nil
		case <-idleTimer.C:
			write(Object{"type": "error", "error": Object{"code": "ASR_TIMEOUT", "message": "No audio was received within the speech session deadline"}})
			return nil
		case <-ctx.Done():
			if ctx.Err() == context.DeadlineExceeded {
				write(Object{"type": "error", "error": Object{"code": "ASR_TIMEOUT", "message": "Speech session exceeded its time limit"}})
			}
			return nil
		}
	}
}

func readSpeechSocket(ctx context.Context, conn *websocket.Conn, output chan<- speechSocketRead) {
	for {
		typ, data, err := conn.Read(ctx)
		select {
		case output <- speechSocketRead{typ: typ, data: data, err: err}:
		case <-ctx.Done():
			return
		}
		if err != nil {
			return
		}
	}
}

func (s *Server) emitDeepgramFinal(write func(Object) error, stable []string, requestID string) error {
	text := strings.TrimSpace(strings.Join(stable, " "))
	if text == "" {
		return write(Object{"type": "error", "error": Object{"code": "NO_SPEECH", "message": "No speech was recognized"}})
	}
	s.markSpeechProviderSuccess("asr", "deepgram")
	return write(Object{"type": "final", "text": text, "request_id": requestID, "source": "deepgram"})
}

func (s *Server) ttsSpeech(ctx context.Context, text string, localEndpoint, voice string) ([]byte, string, error) {
	if originalTTSSelected() {
		return s.originalTTSSpeech(ctx, text)
	}
	if selector := strings.ToLower(os.Getenv("TN_TTS_PROVIDER")); selector != "" && selector != "local" && selector != "azure" {
		return nil, "", problem(503, "TTS_NOT_CONFIGURED", "Vietnamese cloud speech synthesis is not configured")
	}
	if !azureTTSSelected() {
		return speechWorkerRequest(ctx, localEndpoint, "/synthesize", "application/json", []byte(jsonText(Object{"text": text, "voice": voice})), 32<<20)
	}
	data, mime, err := azureTTS(s, ctx, text)
	if err != nil {
		s.clearSpeechProviderSuccess("tts", "azure")
	}
	return data, mime, err
}

func azureTTSEndpoint(region string) string {
	return "https://" + region + ".tts.speech.microsoft.com/cognitiveservices/v1"
}

func azureSSML(text string) string {
	var escaped bytes.Buffer
	_ = xml.EscapeText(&escaped, []byte(text))
	return `<speak version="1.0" xml:lang="vi-VN"><voice name="` + azureVoice + `">` + escaped.String() + `</voice></speak>`
}

func azureTTS(s *Server, ctx context.Context, text string) ([]byte, string, error) {
	key, region, err := azureTTSReady()
	if err != nil {
		return nil, "", err
	}
	if count := len([]rune(text)); count < 1 || count > 500 {
		return nil, "", problem(400, "INVALID_TEXT", "text must contain at most 500 characters")
	}
	if err = reserveAzureChars(s, uint64(len([]rune(text)))); err != nil {
		return nil, "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, azureTTSEndpoint(region), strings.NewReader(azureSSML(text)))
	if err != nil {
		return nil, "", problem(503, "TTS_CONFIG_INVALID", "Azure Speech request could not be constructed")
	}
	request.Header.Set("Ocp-Apim-Subscription-Key", key)
	request.Header.Set("Content-Type", "application/ssml+xml")
	request.Header.Set("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm")
	request.Header.Set("User-Agent", "TN-Assistant")
	client := &http.Client{Timeout: 45 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return nil, "", problem(503, "TTS_UPSTREAM_UNAVAILABLE", "Azure Speech did not complete the request")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		switch response.StatusCode {
		case http.StatusUnauthorized, http.StatusForbidden:
			return nil, "", problem(503, "TTS_CREDENTIAL_INVALID", "Azure Speech rejected the configured credential")
		case http.StatusTooManyRequests:
			return nil, "", problem(503, "TTS_QUOTA_LIMIT", "Azure Speech free-tier quota or rate limit was reached")
		default:
			return nil, "", problem(503, "TTS_UPSTREAM_UNAVAILABLE", "Azure Speech did not complete the request")
		}
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, 32<<20+1))
	if err != nil || len(raw) < 44 || len(raw) > 32<<20 || string(raw[:4]) != "RIFF" || string(raw[8:12]) != "WAVE" {
		return nil, "", problem(502, "TTS_RESPONSE_INVALID", "Azure Speech did not return WAV audio")
	}
	s.markSpeechProviderSuccess("tts", "azure")
	return raw, "audio/wav", nil
}

func reserveAzureChars(s *Server, chars uint64) error {
	// Azure Speech F0 includes 500,000 neural TTS characters each month. Keep
	// a 10% headroom so unrelated checks cannot consume the entire allowance.
	const limit = uint64(450000)
	if chars == 0 || chars > limit {
		return problem(429, "TTS_MONTHLY_LIMIT", "The Azure Speech monthly character limit has been reached")
	}
	if _, err := s.db.Exec(`CREATE TABLE IF NOT EXISTS azure_tts_usage(month_utc TEXT PRIMARY KEY, chars INTEGER NOT NULL CHECK(chars >= 0))`); err != nil {
		return problem(503, "TTS_QUOTA_UNAVAILABLE", "Azure Speech usage budget is unavailable")
	}
	month := time.Now().UTC().Format("2006-01")
	tx, err := s.db.Begin()
	if err != nil {
		return problem(503, "TTS_QUOTA_UNAVAILABLE", "Azure Speech usage budget is unavailable")
	}
	defer tx.Rollback()
	if _, err = tx.Exec(`INSERT OR IGNORE INTO azure_tts_usage(month_utc,chars) VALUES(?,0)`, month); err != nil {
		return problem(503, "TTS_QUOTA_UNAVAILABLE", "Azure Speech usage budget is unavailable")
	}
	result, err := tx.Exec(`UPDATE azure_tts_usage SET chars=chars+? WHERE month_utc=? AND chars+?<=?`, chars, month, chars, limit)
	if err != nil {
		return problem(503, "TTS_QUOTA_UNAVAILABLE", "Azure Speech usage budget is unavailable")
	}
	changed, err := result.RowsAffected()
	if err != nil || changed != 1 {
		return problem(429, "TTS_MONTHLY_LIMIT", "The Azure Speech monthly character limit has been reached")
	}
	if err = tx.Commit(); err != nil {
		return problem(503, "TTS_QUOTA_UNAVAILABLE", "Azure Speech usage budget is unavailable")
	}
	return nil
}
