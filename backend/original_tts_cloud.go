package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const originalTTSEndpoint = "https://partner-speech.lab.zalo.ai/tts/stream"

var originalTTSSlots = make(chan struct{}, 2)

// Original TTS is an explicit exception selected by the operator. It never
// enables the former ASR, NLU, action or account providers.
func originalTTSSelected() bool { return strings.EqualFold(os.Getenv("TN_TTS_PROVIDER"), "original") }

func (s *Server) originalTTSProvider() (Provider, error) {
	providers, err := s.providers()
	if err != nil {
		return Provider{}, err
	}
	p := providers["tts"]
	if p.Mode != "legacy_tn" || !p.configured("tts") {
		return Provider{}, problem(503, "TTS_NOT_CONFIGURED", "Original TTS credentials are not configured")
	}
	// Signed requests and app credentials must never be sent to arbitrary hosts,
	// redirects, or endpoints configured for a different former provider.
	if p.URL != originalTTSEndpoint || p.StreamURL != "" || len(p.HeaderEnv) != 0 || p.TokenEnv != "" || p.DeviceTokenEnv != "" || p.UIDEnv != "" || p.UID != "" {
		return Provider{}, problem(503, "TTS_CONFIG_INVALID", "Original TTS requires its approved HTTPS synthesis endpoint")
	}
	speaker, err := strconv.Atoi(str(p.Options, "speaker_id"))
	if err != nil || speaker < 1 || speaker > 32 || str(p.Options, "app_src") == "" || (str(p.Options, "encode_type") != "" && str(p.Options, "encode_type") != "2") {
		return Provider{}, problem(503, "TTS_CONFIG_INVALID", "Original TTS requires a configured speaker and MP3 encoding")
	}
	return p, nil
}

func (s *Server) originalTTSIdentity() string {
	providers, err := s.providers()
	if err != nil {
		return "original_tts:unconfigured"
	}
	p := providers["tts"]
	data, _ := json.Marshal(p)
	fingerprint := sha256.Sum256(append(data, []byte("\x00"+os.Getenv(p.APIKeyEnv)+"\x00"+os.Getenv(p.SecretEnv))...))
	return fmt.Sprintf("original_tts:%x", fingerprint[:])
}

func (s *Server) speechProviderID(provider string) string {
	if provider == "original_tts" {
		return s.originalTTSIdentity()
	}
	return speechProviderIdentity(provider)
}

func (s *Server) originalTTSSpeech(ctx context.Context, text string) (audio []byte, mime string, err error) {
	return s.originalTTSSpeechWithVoice(ctx, text, "")
}

func (s *Server) originalTTSSpeechWithVoice(ctx context.Context, text, voice string) (audio []byte, mime string, err error) {
	if count := len([]rune(strings.TrimSpace(text))); count < 1 || count > 500 {
		return nil, "", problem(400, "INVALID_TEXT", "text must contain 1..500 characters")
	}
	p, err := s.originalTTSProvider()
	if err != nil {
		return nil, "", err
	}
	select {
	case originalTTSSlots <- struct{}{}:
		defer func() { <-originalTTSSlots }()
	default:
		return nil, "", problem(429, "TTS_BUSY", "Speech synthesis is busy; retry shortly")
	}
	callerCtx := ctx
	defer func() {
		if err != nil && callerCtx.Err() == nil {
			s.clearSpeechProviderSuccess("tts", "original_tts")
		}
	}()
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	// Only saved, recognized TN choices can override the operator's speaker.
	// Keep the provider configuration immutable and sign the effective speaker.
	speaker := str(p.Options, "speaker_id")
	switch voice {
	case "south":
		speaker = "5"
	case "north":
		speaker = "11"
	}
	form := url.Values{"input": {text}, "apikey": {os.Getenv(p.APIKeyEnv)}, "encodeType": {"2"}, "speakerId": {speaker}, "appSrc": {str(p.Options, "app_src")}, "isAndroidBox": {"false"}}
	form.Set("sig", legacyTTSSign(form, os.Getenv(p.SecretEnv)))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.URL, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, "", problem(503, "TTS_CONFIG_INVALID", "Original TTS request could not be constructed")
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := client().Do(req)
	if err != nil {
		return nil, "", problem(502, "TTS_UPSTREAM_UNAVAILABLE", "Original TTS did not complete the request")
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, "", problem(502, "TTS_UPSTREAM_REJECTED", fmt.Sprintf("Original TTS returned HTTP %d", res.StatusCode))
	}
	raw, err := io.ReadAll(io.LimitReader(res.Body, (128<<10)+1))
	if err != nil || len(raw) > 128<<10 {
		return nil, "", problem(502, "TTS_RESPONSE_INVALID", "Original TTS response exceeded its limit")
	}
	var reply Object
	if json.Unmarshal(raw, &reply) != nil {
		return nil, "", problem(502, "TTS_RESPONSE_INVALID", "Original TTS returned invalid JSON")
	}
	if integer(reply, "error_code", -1) != 0 {
		return nil, "", problem(502, "TTS_PROVIDER_REJECTED", "Original TTS rejected synthesis")
	}
	data, _ := reply["data"].(map[string]any)
	audio, err = legacyTTSAudio(ctx, str(data, "url"))
	if err != nil {
		return nil, "", err
	}
	if !originalTTSValidMP3(audio) {
		return nil, "", problem(502, "TTS_AUDIO_INVALID", "Original TTS did not return valid MP3 frames")
	}
	s.markSpeechProviderSuccess("tts", "original_tts")
	return audio, "audio/mpeg", nil
}

// Check consecutive Layer III frame boundaries rather than accepting an audio
// content type or a leading sync byte as proof of a valid provider response.
// Playback/decoding is verified separately in live deployment checks.
func originalTTSValidMP3(data []byte) bool {
	start := 0
	if len(data) >= 10 && bytes.Equal(data[:3], []byte("ID3")) {
		for _, b := range data[6:10] {
			if b&0x80 != 0 {
				return false
			}
		}
		start = 10 + int(data[6])<<21 + int(data[7])<<14 + int(data[8])<<7 + int(data[9])
		if data[5]&0x10 != 0 {
			start += 10
		}
	}
	frame := func(offset int) (int, byte) {
		if offset < 0 || offset+4 > len(data) {
			return 0, 0
		}
		h := data[offset : offset+4]
		version := (h[1] >> 3) & 3
		if h[0] != 0xff || h[1]&0xe0 != 0xe0 || version == 1 || (h[1]>>1)&3 != 1 || h[2]>>4 == 0 || h[2]>>4 == 15 || (h[2]>>2)&3 == 3 {
			return 0, 0
		}
		bitrates := []int{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}
		rate := []int{44100, 48000, 32000}[(h[2]>>2)&3]
		factor := 144000
		if version != 3 {
			bitrates = []int{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}
			rate /= 2
			factor = 72000
			if version == 0 {
				rate /= 2
			}
		}
		size := factor*bitrates[h[2]>>4]/rate + int((h[2]>>1)&1)
		if offset+size > len(data) {
			return 0, 0
		}
		return size, version<<4 | h[2]&0x0c
	}
	n, format := frame(start)
	next, nextFormat := frame(start + n)
	return n > 0 && next > 0 && format == nextFormat
}
