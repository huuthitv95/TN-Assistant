package main

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/coder/websocket"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"
)

type Provider struct {
	Mode           string            `json:"mode"`
	URL            string            `json:"url"`
	StreamURL      string            `json:"stream_url,omitempty"`
	TokenEnv       string            `json:"token_env,omitempty"`
	DeviceTokenEnv string            `json:"device_token_env,omitempty"`
	APIKeyEnv      string            `json:"api_key_env,omitempty"`
	SecretEnv      string            `json:"secret_env,omitempty"`
	UID            string            `json:"uid,omitempty"`
	UIDEnv         string            `json:"uid_env,omitempty"`
	ClientAppType  string            `json:"client_app_type,omitempty"`
	Version        string            `json:"version,omitempty"`
	HeaderEnv      map[string]string `json:"header_env,omitempty"`
	Options        Object            `json:"options,omitempty"`
}
type Providers map[string]Provider

func (p Provider) userID() string {
	if p.UIDEnv != "" {
		return os.Getenv(p.UIDEnv)
	}
	return p.UID
}

func (p Provider) configured(kind string) bool {
	if kind == "zalo" {
		return p.Mode == "generic" && str(p.Options, "app_id") != "" && str(p.Options, "redirect_uri") != "" && p.SecretEnv != "" && os.Getenv(p.SecretEnv) != ""
	}
	if (kind == "driving" || kind == "action_log" || kind == "welcome") && p.Mode != "legacy_tn" {
		return false
	}
	if p.Mode != "generic" && p.Mode != "legacy_tn" {
		return false
	}
	if p.URL == "" && p.StreamURL == "" {
		return false
	}
	for _, ref := range p.HeaderEnv {
		if os.Getenv(ref) == "" {
			return false
		}
	}
	for _, ref := range []string{p.TokenEnv, p.DeviceTokenEnv, p.APIKeyEnv, p.SecretEnv, p.UIDEnv} {
		if ref != "" && os.Getenv(ref) == "" {
			return false
		}
	}
	if p.Mode == "legacy_tn" {
		switch kind {
		case "asr":
			if p.userID() == "" || p.TokenEnv == "" || os.Getenv(p.TokenEnv) == "" {
				return false
			}
			if p.StreamURL != "" && (p.SecretEnv == "" || os.Getenv(p.SecretEnv) == "") {
				return false
			}
		case "tts":
			if p.APIKeyEnv == "" || p.SecretEnv == "" || os.Getenv(p.APIKeyEnv) == "" || os.Getenv(p.SecretEnv) == "" {
				return false
			}
		case "nlu":
			if p.TokenEnv == "" || p.DeviceTokenEnv == "" || p.userID() == "" {
				return false
			}
		case "driving":
			if p.TokenEnv == "" || p.DeviceTokenEnv == "" || p.SecretEnv == "" || p.userID() == "" || p.ClientAppType == "" || p.Version == "" || os.Getenv(str(p.Options, "device_id_env")) == "" {
				return false
			}
		case "action_log", "welcome":
			if p.TokenEnv == "" || p.DeviceTokenEnv == "" || p.SecretEnv == "" || p.ClientAppType == "" || p.Version == "" || p.userID() == "" || str(p.Options, "owner_user_id") == "" || os.Getenv(str(p.Options, "device_id_env")) == "" {
				return false
			}
		case "zalo":
			return false
		}
	}
	return true
}
func (s *Server) providers() (Providers, error) {
	p := Providers{}
	if s.cfg.ProviderFile != "" {
		b, e := os.ReadFile(s.cfg.ProviderFile)
		if e != nil {
			return nil, problem(503, "PROVIDER_CONFIG_INVALID", "Cannot read provider configuration file")
		}
		var rawConfig Object
		if json.Unmarshal(b, &rawConfig) != nil || rejectSecrets(rawConfig) {
			return nil, problem(503, "PROVIDER_CONFIG_INVALID", "Provider file must use credential environment references, never literal secrets")
		}
		if e = json.Unmarshal(b, &p); e != nil {
			return nil, problem(503, "PROVIDER_CONFIG_INVALID", "Invalid provider configuration JSON")
		}
	} else {
		m, e := s.record("config", "providers")
		if e == nil {
			delete(m, "id")
			b, _ := json.Marshal(m)
			json.Unmarshal(b, &p)
		}
	}
	// Existing persisted provider files/databases are upgraded in memory before use.
	// New writes accept only the canonical mode.
	for kind, provider := range p {
		if provider.Mode == "legacy_kiki" {
			provider.Mode = "legacy_tn"
			p[kind] = provider
		}
	}
	for _, k := range []string{"asr", "nlu", "tts", "zalo", "traffic", "wake_word", "driving", "action_log", "welcome"} {
		if _, ok := p[k]; !ok {
			p[k] = Provider{Mode: "legacy_tn", URL: "", HeaderEnv: map[string]string{}, Options: Object{}}
		}
	}
	return p, nil
}
func (p Providers) redacted() Object {
	out := Object{}
	for k, v := range p {
		b, _ := json.Marshal(v)
		var m Object
		json.Unmarshal(b, &m)
		m["configured"] = v.configured(k)
		out[k] = m
	}
	return out
}
func rejectSecrets(v any) bool {
	switch m := v.(type) {
	case map[string]any:
		for k, x := range m {
			lower := strings.ToLower(k)
			if lower == "secret" || lower == "secretkey" || lower == "secret_key" || lower == "password" || lower == "token" || lower == "device_token" || lower == "access_token" || lower == "apikey" || lower == "api_key" || lower == "authorization" || lower == "authheader" || lower == "headers" {
				return true
			}
			if rejectSecrets(x) {
				return true
			}
		}
	case []any:
		for _, x := range m {
			if rejectSecrets(x) {
				return true
			}
		}
	}
	return false
}
func (s *Server) saveProviders(m Object) error {
	if s.cfg.ProviderFile != "" {
		return problem(409, "PROVIDER_FILE_MANAGED", "Providers are managed by TN_PROVIDER_FILE; edit that file locally")
	}
	if rejectSecrets(m) {
		return problem(400, "SECRET_LITERAL_REJECTED", "Use *_env or header_env references; never send literal credentials")
	}
	delete(m, "id")
	allowed := map[string]bool{"asr": true, "nlu": true, "tts": true, "zalo": true, "traffic": true, "wake_word": true, "driving": true, "action_log": true, "welcome": true}
	for k, v := range m {
		if !allowed[k] {
			return problem(400, "INVALID_PROVIDER", "Unknown provider "+k)
		}
		item, ok := v.(map[string]any)
		if !ok {
			return problem(400, "INVALID_PROVIDER", "Provider must be object")
		}
		delete(item, "configured")
		mode := str(item, "mode")
		if mode != "legacy_tn" && mode != "generic" {
			return problem(400, "INVALID_PROVIDER_MODE", "mode must be legacy_tn or generic")
		}
		for _, key := range []string{"url", "stream_url"} {
			endpoint := str(item, key)
			if endpoint != "" {
				u, e := url.Parse(endpoint)
				if e != nil || u.Host == "" || u.User != nil || (u.Scheme != "https" && u.Scheme != "wss" && !(u.Hostname() == "127.0.0.1" || u.Hostname() == "localhost")) {
					return problem(400, "INVALID_ENDPOINT", "Use HTTPS/WSS provider endpoints; localhost HTTP/WS allowed for fixtures")
				}
				if u.RawQuery != "" {
					return problem(400, "ENDPOINT_QUERY_REJECTED", "Configure parameters separately to avoid persisting credential-bearing URLs")
				}
			}
		}
	}
	b, _ := json.Marshal(m)
	var p Providers
	if json.Unmarshal(b, &p) != nil {
		return problem(400, "INVALID_PROVIDER", "Invalid provider shape")
	}
	return s.put("config", "providers", "", m)
}
func (s *Server) requireProvider(kind string) (Provider, error) {
	if s.independentRuntime() && kind != "zalo" {
		return Provider{}, problem(503, "TN_CAPABILITY_UNAVAILABLE", kind+" has no enabled TN-owned implementation")
	}
	p, e := s.providers()
	if e != nil {
		return Provider{}, e
	}
	v := p[kind]
	if !v.configured(kind) {
		return v, problem(503, "PROVIDER_NOT_CONFIGURED", "Configure your own "+kind+" endpoint, protocol, and credential environment references")
	}
	return v, nil
}
func (p Provider) headers() http.Header {
	h := http.Header{}
	for k, ref := range p.HeaderEnv {
		h.Set(k, os.Getenv(ref))
	}
	if p.Mode == "generic" && p.TokenEnv != "" {
		h.Set("Authorization", "Bearer "+os.Getenv(p.TokenEnv))
	}
	return h
}
func client() *http.Client {
	return &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(r *http.Request, via []*http.Request) error { return http.ErrUseLastResponse }}
}
func providerRequest(ctx context.Context, p Provider, contentType string, data []byte) ([]byte, string, error) {
	req, e := http.NewRequestWithContext(ctx, "POST", p.URL, bytes.NewReader(data))
	if e != nil {
		return nil, "", problem(503, "PROVIDER_CONFIG_INVALID", "Invalid provider endpoint")
	}
	req.Header = p.headers()
	if p.Mode == "legacy_tn" && p.DeviceTokenEnv != "" {
		req.Header.Set("Authorization", "Token "+os.Getenv(p.TokenEnv)+" UID "+p.userID())
	}
	req.Header.Set("Content-Type", contentType)
	res, e := client().Do(req)
	if e != nil {
		return nil, "", problem(502, "PROVIDER_UNAVAILABLE", "Provider connection failed")
	}
	defer res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return nil, "", problem(502, "PROVIDER_HTTP_ERROR", fmt.Sprintf("Provider returned HTTP %d", res.StatusCode))
	}
	raw, e := io.ReadAll(io.LimitReader(res.Body, 8<<20))
	if e != nil {
		return nil, "", problem(502, "PROVIDER_INVALID_RESPONSE", "Cannot read provider response")
	}
	return raw, res.Header.Get("Content-Type"), nil
}
func (s *Server) providerJSON(ctx context.Context, kind string, m Object) (Object, error) {
	p, e := s.requireProvider(kind)
	if e != nil {
		return nil, e
	}
	raw, _, e := providerRequest(ctx, p, "application/json", []byte(jsonText(m)))
	if e != nil {
		return nil, e
	}
	var out Object
	if json.Unmarshal(raw, &out) != nil {
		return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Provider must return JSON object")
	}
	return out, nil
}

// Shared by the NLU writer and action-log resolver; the storage binding is
// unchanged and never substitutes a local conversation label for an upstream key.
func legacyNLUSessionRecordID(principal *Principal, conversation string, p Provider, device string) string {
	return hash(str(principal.User, "id") + "\x00" + str(principal.Device, "id") + "\x00" + conversation + "\x00" + p.URL + "\x00" + p.userID() + "\x00" + device)
}

func (s *Server) commandAssistant(r *http.Request, principal *Principal, m Object) (Object, error) {
	if s.independentRuntime() {
		return s.tnCommandAssistant(str(m, "text"))
	}
	text := str(m, "text")
	if text == "" || len(text) > 16000 {
		return nil, problem(400, "INVALID_TEXT", "text must contain 1..16000 bytes")
	}
	session := str(m, "session_id")
	if session == "" {
		session = ident()
	}
	m["session_id"] = session
	p, e := s.requireProvider("nlu")
	if e != nil {
		return nil, e
	}
	data := []byte(jsonText(m))
	ct := "application/json"
	nluSessionKey := ""
	private := s.aiSettings(str(principal.User, "id"))["private_mode"] == true
	if p.Mode == "legacy_tn" {
		form := url.Values{"event": {text}, "device_token": {os.Getenv(p.DeviceTokenEnv)}, "asr": {str(p.Options, "asr_type")}, "nlu_type": {str(p.Options, "nlu_type")}, "micro_source": {str(p.Options, "micro_source")}, "lat": {"-1"}, "lon": {"-1"}, "session_key": {session}, "session_state": {"session.chat"}, "asr_request_id": {str(m, "asr_request_id")}, "device_id": {str(m, "device_id")}, "car_model_code": {str(p.Options, "car_model_code")}, "locations": {"[]"}}
		form.Set("event", jsonText(Object{"event": "MessageInput", "payload": Object{"message": text}}))
		if extra, ok := p.Options["form_fields"].(map[string]any); ok {
			for key, value := range extra {
				form.Set(key, fmt.Sprint(value))
			}
		}
		// Configured defaults must not override the authenticated client's
		// current voice or location. Arrays use JSON, matching JSONArray.toString
		// in the original NLPAnalyzerService rather than Go's diagnostic format.
		for _, key := range []string{"lat", "lon", "session_state", "locations", "speaker_id"} {
			if value, ok := m[key]; ok {
				if key == "locations" {
					if text, ok := value.(string); ok {
						form.Set(key, text)
					} else {
						form.Set(key, jsonText(value))
					}
				} else {
					form.Set(key, fmt.Sprint(value))
				}
			}
		}
		if ref := str(p.Options, "device_id_env"); ref != "" {
			form.Set("device_id", os.Getenv(ref))
		}
		// The original starts with an empty provider session and updates it
		// from each response. Android's conversation ID belongs to our API;
		// passing it as an upstream session loses the provider's follow-up state.
		nluSessionKey = legacyNLUSessionRecordID(principal, session, p, form.Get("device_id"))
		form.Set("session_key", "")
		if !private {
			if saved, err := s.record("nlu_session", nluSessionKey); err == nil && str(saved, "expires_at") > now() {
				form.Set("session_key", str(saved, "session_key"))
			}
		}
		if form.Get("asr_request_id") == "" {
			form.Set("asr_request_id", str(m, "request_id"))
		}
		if p.SecretEnv != "" {
			timestamp := strconv.FormatInt(time.Now().UnixMilli(), 10)
			sum := md5.Sum([]byte(p.ClientAppType + timestamp))
			signed := md5.Sum([]byte(os.Getenv(p.SecretEnv) + hex.EncodeToString(sum[:])))
			form.Set("client_app_type", p.ClientAppType)
			form.Set("version_code", p.Version)
			form.Set("timestamp", timestamp)
			form.Set("hash", hex.EncodeToString(signed[:]))
		}
		if p.HeaderEnv == nil {
			p.HeaderEnv = map[string]string{}
		}
		data = []byte(form.Encode())
		ct = "application/x-www-form-urlencoded"
	}
	start := time.Now()
	raw, _, e := providerRequest(r.Context(), p, ct, data)
	if e != nil {
		return nil, e
	}
	var response Object
	if json.Unmarshal(raw, &response) != nil {
		return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "NLU response is not JSON")
	}
	out, e := normalizeNLU(response, p.Mode)
	if e != nil {
		return nil, e
	}
	if nluSessionKey != "" && !private && str(response, "session_key") != "" {
		uid := str(principal.User, "id")
		if e = s.put("nlu_session", nluSessionKey, uid, Object{"session_key": str(response, "session_key"), "expires_at": time.Now().UTC().Add(30 * time.Minute).Format(time.RFC3339Nano)}); e != nil {
			return nil, e
		}
		s.db.Exec(`DELETE FROM records WHERE kind='nlu_session' AND (json_extract(data,'$.expires_at')<? OR (owner=? AND id NOT IN (SELECT id FROM records WHERE kind='nlu_session' AND owner=? ORDER BY created_at DESC LIMIT 20)))`, now(), uid, uid)
	}
	out["session_id"] = session
	out["text"] = text
	out["provider_latency_ms"] = time.Since(start).Milliseconds()
	if p.Mode == "legacy_tn" {
		out["_nlu_provider_binding"] = locationProviderBinding(p)
	}
	return out, nil
}
func normalizeNLU(m Object, mode string) (Object, error) {
	if mode == "legacy_tn" {
		if _, ok := m["code"]; !ok || integer(m, "code", -1) != 0 {
			return nil, problem(502, "PROVIDER_REJECTED", "Provider NLU response code is not zero")
		}
	}
	values, ok := m["directives"].([]any)
	if !ok {
		return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "NLU response lacks ordered directives array")
	}
	out := []Object{}
	utterance := str(m, "utterance")
	for _, value := range values {
		d, ok := value.(map[string]any)
		if !ok {
			return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Directive must be object")
		}
		typ := str(d, "type")
		if mode == "legacy_tn" && str(d, "action_code") != "" {
			typ = str(d, "action_code")
		}
		if typ == "" {
			return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Directive type required")
		}
		payload := d
		if mode == "generic" || (mode == "legacy_tn" && str(d, "action_code") != "") {
			if nested, ok := d["payload"].(map[string]any); ok {
				payload = Object{}
				for key, item := range nested {
					payload[key] = item
				}
				if mode == "legacy_tn" {
					for _, key := range []string{"action_id", "required", "mode"} {
						if item, present := d[key]; present {
							payload[key] = item
						}
					}
				}
			}
		}
		out = append(out, Object{"type": typ, "payload": payload})
		if utterance == "" && !hasDirectiveRequirements(payload) && !hasDirectiveRequirements(d) && (typ == "Speech" || typ == "TextToSpeech" || typ == "SpeechSynthesizer" || typ == "SpeechSynthesizerOffline") {
			utterance = str(payload, "utter_text")
			if utterance == "" {
				utterance = str(payload, "text")
			}
			if utterance == "" {
				utterance = str(payload, "speech")
			}
		}
	}
	if mode == "legacy_tn" && len(out) == 0 && strings.TrimSpace(utterance) == "" {
		return nil, problem(502, "PROVIDER_EMPTY_RESPONSE", "NLU returned no speech or actions; retry later")
	}
	return Object{"utterance": utterance, "directives": out, "provider_raw": m}, nil
}

func hasDirectiveRequirements(payload Object) bool {
	switch required := payload["required"].(type) {
	case []any:
		return len(required) > 0
	case []string:
		return len(required) > 0
	}
	return false
}
func legacyTTSSign(form url.Values, secret string) string {
	keys := []string{}
	for k := range form {
		if k != "sig" {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	var b strings.Builder
	for _, k := range keys {
		b.WriteString(k)
		b.WriteByte('=')
		b.WriteString(form.Get(k))
	}
	b.WriteString(secret)
	sum := md5.Sum([]byte(b.String()))
	return hex.EncodeToString(sum[:])
}

// Original TTS playlists contain protocol-relative, extensionless MP3 chunks.
// Fetch only the observed HTTPS service hosts, without any app/session headers.
func legacyTTSChunks(endpoint string, playlist []byte) ([]string, error) {
	base, e := url.Parse(endpoint)
	if e != nil || base.Scheme != "https" || base.Host != "audiostream-ailab.tts.zalo.ai" || base.User != nil {
		return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Unexpected Original TTS playlist host")
	}
	if len(playlist) > 128<<10 || !bytes.HasPrefix(playlist, []byte("#EXTM3U")) || bytes.Contains(playlist, []byte("#EXT-X-KEY")) {
		return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Unsupported Original TTS playlist")
	}
	chunks := []string{}
	for _, line := range strings.Split(string(playlist), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		ref, e := url.Parse(line)
		if e != nil {
			return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Invalid Original TTS chunk")
		}
		chunk := base.ResolveReference(ref)
		if chunk.Scheme != "https" || chunk.Host != "chunk-ailab.tts.zalo.ai" || chunk.User != nil {
			return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Unexpected Original TTS chunk host")
		}
		chunks = append(chunks, chunk.String())
	}
	if len(chunks) == 0 || len(chunks) > 32 {
		return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Invalid Original TTS chunk count")
	}
	return chunks, nil
}
func legacyTTSAudio(ctx context.Context, endpoint string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	get := func(endpoint string, limit int64) ([]byte, error) {
		for attempt := 0; attempt < 5; attempt++ {
			req, e := http.NewRequestWithContext(ctx, "GET", endpoint, nil)
			if e != nil {
				return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Invalid Original TTS audio endpoint")
			}
			res, e := client().Do(req)
			if e != nil {
				return nil, problem(502, "PROVIDER_UNAVAILABLE", "Original TTS audio connection failed")
			}
			defer res.Body.Close()
			if res.StatusCode != 200 {
				res.Body.Close()
				if (res.StatusCode == 404 || res.StatusCode == 503) && attempt < 4 {
					select {
					case <-ctx.Done():
						return nil, problem(502, "PROVIDER_UNAVAILABLE", "Original TTS audio timed out")
					case <-time.After(250 * time.Millisecond):
						continue
					}
				}
				return nil, problem(502, "PROVIDER_HTTP_ERROR", fmt.Sprintf("Original TTS audio unavailable (HTTP %d)", res.StatusCode))
			}
			data, e := io.ReadAll(io.LimitReader(res.Body, limit+1))
			if e != nil || len(data) == 0 || int64(len(data)) > limit {
				return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Invalid Original TTS audio size")
			}
			return data, nil
		}
		return nil, problem(502, "PROVIDER_UNAVAILABLE", "Original TTS audio timed out")
	}
	// Validate the playlist URL before the first outbound fetch.
	if _, e := legacyTTSChunks(endpoint, []byte("#EXTM3U\nhttps://chunk-ailab.tts.zalo.ai/validation")); e != nil {
		return nil, e
	}
	playlist, e := get(endpoint, 128<<10)
	if e != nil {
		return nil, e
	}
	chunks, e := legacyTTSChunks(endpoint, playlist)
	if e != nil {
		return nil, e
	}
	var audio []byte
	for _, chunk := range chunks {
		data, e := get(chunk, int64(8<<20-len(audio)))
		if e != nil {
			return nil, e
		}
		audio = append(audio, data...)
	}
	return audio, nil
}
func (s *Server) storeTTSAudio(w http.ResponseWriter, principal *Principal, data []byte, mime string, start time.Time) error {
	id := ident()
	if e := s.put("tts_audio", id, str(principal.User, "id"), Object{"owner": str(principal.User, "id"), "mime_type": mime, "data": hex.EncodeToString(data), "created_at": now(), "expires_at": time.Now().Add(10 * time.Minute).UTC().Format(time.RFC3339Nano)}); e != nil {
		return e
	}
	// Keep authenticated media on the client's API origin, including adb reverse.
	send(w, 200, Object{"url": "/v1/tts/audio/" + id, "mime_type": mime, "provider_latency_ms": time.Since(start).Milliseconds()})
	return nil
}
func (s *Server) tts(w http.ResponseWriter, r *http.Request, principal *Principal) error {
	m, e := body(r)
	if e != nil {
		return e
	}
	if str(m, "text") == "" {
		return problem(400, "INVALID_TEXT", "text required")
	}
	if s.independentRuntime() {
		if len([]rune(str(m, "text"))) > 500 {
			return problem(400, "INVALID_TEXT", "text must contain at most 500 characters")
		}
		cfg, err := s.runtimeConfig()
		if err != nil {
			return err
		}
		start := time.Now()
		var raw []byte
		var mime string
		if originalTTSSelected() {
			voice := str(s.aiSettings(str(principal.User, "id")), "agent_voice")
			raw, mime, err = s.originalTTSSpeechWithVoice(r.Context(), str(m, "text"), voice)
		} else {
			raw, mime, err = s.ttsSpeech(r.Context(), str(m, "text"), cfg.TTSURL, cfg.TTSVoice)
		}
		if err != nil {
			return err
		}
		if mime == "audio/mpeg" && originalTTSSelected() && originalTTSValidMP3(raw) {
			return s.storeTTSAudio(w, principal, raw, mime, start)
		}
		if !strings.HasPrefix(mime, "audio/wav") || len(raw) < 44 {
			return problem(502, "SPEECH_RESPONSE_INVALID", "TTS provider did not return supported audio")
		}
		return s.storeTTSAudio(w, principal, raw, "audio/wav", start)
	}
	p, e := s.requireProvider("tts")
	if e != nil {
		return e
	}
	data := []byte(jsonText(m))
	ct := "application/json"
	if p.Mode == "legacy_tn" {
		speaker := str(m, "speaker_id")
		if speaker == "" {
			speaker = str(p.Options, "speaker_id")
		}
		form := url.Values{"input": {str(m, "text")}, "apikey": {os.Getenv(p.APIKeyEnv)}, "encodeType": {str(p.Options, "encode_type")}, "speakerId": {speaker}, "appSrc": {str(p.Options, "app_src")}, "isAndroidBox": {str(p.Options, "is_android_box")}}
		if form.Get("encodeType") == "" {
			form.Set("encodeType", "2")
		}
		if form.Get("isAndroidBox") == "" {
			form.Set("isAndroidBox", "false")
		}
		form.Set("sig", legacyTTSSign(form, os.Getenv(p.SecretEnv)))
		data = []byte(form.Encode())
		ct = "application/x-www-form-urlencoded"
	}
	start := time.Now()
	raw, mime, e := providerRequest(r.Context(), p, ct, data)
	if e != nil {
		return e
	}
	if strings.HasPrefix(mime, "audio/") {
		return s.storeTTSAudio(w, principal, raw, mime, start)
	}
	var out Object
	if json.Unmarshal(raw, &out) != nil {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "TTS response is not JSON or audio")
	}
	if p.Mode == "legacy_tn" {
		if integer(out, "error_code", -1) != 0 {
			return problem(502, "PROVIDER_REJECTED", "Provider TTS reported a non-zero error code")
		}
		nested, _ := out["data"].(map[string]any)
		if p.Options["proxy_hls_audio"] == true {
			audio, e := legacyTTSAudio(r.Context(), str(nested, "url"))
			if e != nil {
				return e
			}
			return s.storeTTSAudio(w, principal, audio, "audio/mpeg", start)
		}
		out = Object{"url": str(nested, "url"), "mime_type": "application/vnd.apple.mpegurl", "provider_raw": out}
	}
	if str(out, "url") == "" {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "TTS response lacks url")
	}
	out["provider_latency_ms"] = time.Since(start).Milliseconds()
	send(w, 200, out)
	return nil
}
func (s *Server) decode(w http.ResponseWriter, r *http.Request) error {
	if s.independentRuntime() {
		if deepgramSelected() {
			ct := strings.Split(r.Header.Get("Content-Type"), ";")[0]
			if ct != "audio/wav" && ct != "audio/pcm" && ct != "application/octet-stream" {
				return problem(415, "INVALID_AUDIO", "audio/wav or audio/pcm required")
			}
			if v := r.URL.Query().Get("sample_rate"); v != "" && v != "16000" {
				return problem(400, "INVALID_AUDIO_FORMAT", "Only 16000 Hz mono supported")
			}
			if v := r.URL.Query().Get("channels"); v != "" && v != "1" {
				return problem(400, "INVALID_AUDIO_FORMAT", "Only mono supported")
			}
			audio, err := io.ReadAll(io.LimitReader(r.Body, maxDeepgramAudioBytes+1025))
			if err != nil {
				return err
			}
			if len(audio) == 0 || len(audio) > maxDeepgramAudioBytes+1024 {
				return problem(413, "INVALID_AUDIO_SIZE", "Audio must contain at most 15 seconds of PCM16 mono")
			}
			result, err := s.deepgramDecode(r.Context(), ct, audio)
			if err != nil {
				return err
			}
			result["request_id"] = str(result, "request_id")
			if result["request_id"] == "" {
				result["request_id"] = ident()
			}
			send(w, 200, result)
			return nil
		}
		ct := strings.Split(r.Header.Get("Content-Type"), ";")[0]
		if ct != "audio/wav" && ct != "audio/pcm" && ct != "application/octet-stream" {
			return problem(415, "INVALID_AUDIO", "audio/wav or audio/pcm required")
		}
		if v := r.URL.Query().Get("sample_rate"); v != "" && v != "16000" {
			return problem(400, "INVALID_AUDIO_FORMAT", "Only 16000 Hz mono supported")
		}
		if v := r.URL.Query().Get("channels"); v != "" && v != "1" {
			return problem(400, "INVALID_AUDIO_FORMAT", "Only mono supported")
		}
		audio, err := io.ReadAll(io.LimitReader(r.Body, 481025))
		if err != nil {
			return err
		}
		if len(audio) == 0 || len(audio) > 481024 {
			return problem(413, "INVALID_AUDIO_SIZE", "Audio must contain at most 15 seconds of PCM16 mono")
		}
		cfg, err := s.runtimeConfig()
		if err != nil {
			return err
		}
		result, err := localSpeechDecode(r.Context(), cfg.ASRURL, ct, audio)
		if err != nil {
			return err
		}
		if strings.TrimSpace(str(result, "text")) == "" {
			return problem(422, "NO_SPEECH", "No speech was recognized")
		}
		result["request_id"] = ident()
		result["source"] = "tn_asr"
		send(w, 200, result)
		return nil
	}
	p, e := s.requireProvider("asr")
	if e != nil {
		return e
	}
	if p.URL == "" {
		return problem(503, "PROVIDER_NOT_CONFIGURED", "Non-streaming ASR URL not configured")
	}
	ct := strings.Split(r.Header.Get("Content-Type"), ";")[0]
	if ct != "audio/wav" && ct != "audio/pcm" && ct != "application/octet-stream" {
		return problem(415, "INVALID_AUDIO", "audio/wav or audio/pcm required")
	}
	if v := r.URL.Query().Get("sample_rate"); v != "" && v != "16000" {
		return problem(400, "INVALID_AUDIO_FORMAT", "Only 16000 Hz mono supported")
	}
	if v := r.URL.Query().Get("channels"); v != "" && v != "1" {
		return problem(400, "INVALID_AUDIO_FORMAT", "Only mono supported")
	}
	audio, e := io.ReadAll(io.LimitReader(r.Body, (8<<20)+1))
	if e != nil {
		return e
	}
	if len(audio) == 0 || len(audio) > 8<<20 {
		return problem(413, "INVALID_AUDIO_SIZE", "Audio must contain 1..8388608 bytes")
	}
	data := audio
	if p.Mode == "legacy_tn" {
		if ct == "audio/pcm" && p.Options["pcm_as_wav"] == true {
			if len(audio)%2 != 0 {
				return problem(400, "INVALID_AUDIO_FORMAT", "PCM16 requires complete samples")
			}
			header := make([]byte, 44)
			copy(header, "RIFF")
			binary.LittleEndian.PutUint32(header[4:8], uint32(len(audio)+36))
			copy(header[8:16], "WAVEfmt ")
			binary.LittleEndian.PutUint32(header[16:20], 16)
			binary.LittleEndian.PutUint16(header[20:22], 1)
			binary.LittleEndian.PutUint16(header[22:24], 1)
			binary.LittleEndian.PutUint32(header[24:28], 16000)
			binary.LittleEndian.PutUint32(header[28:32], 32000)
			binary.LittleEndian.PutUint16(header[32:34], 2)
			binary.LittleEndian.PutUint16(header[34:36], 16)
			copy(header[36:40], "data")
			binary.LittleEndian.PutUint32(header[40:44], uint32(len(audio)))
			audio = append(header, audio...)
			ct = "audio/wav"
		}
		digest := md5.Sum(audio)
		signature := hex.EncodeToString(digest[:])
		var b bytes.Buffer
		mp := multipart.NewWriter(&b)
		ext := "pcm"
		if ct == "audio/wav" {
			ext = "wav"
		}
		fields := map[string]string{"uid": p.userID(), "sampleRate": "16000", "channels": "1", "ext": ext, "contentType": ct, "dataSignature": signature, "clientType": p.ClientAppType, "clientVersion": p.Version, "lat": r.URL.Query().Get("lat"), "long": r.URL.Query().Get("lon"), "layout": "ignored"}
		if ref := str(p.Options, "http_uid_env"); ref != "" {
			fields["uid"] = os.Getenv(ref)
		}
		if value := str(p.Options, "http_client_type"); value != "" {
			fields["clientType"] = value
		}
		if ct == "audio/pcm" && str(p.Options, "raw_extension") != "" {
			ext = str(p.Options, "raw_extension")
			fields["ext"] = ext
			fields["contentType"] = str(p.Options, "raw_content_type")
		}
		for k, v := range fields {
			if (k == "lat" || k == "long") && v == "" {
				v = "0"
			}
			mp.WriteField(k, v)
		}
		part, e := mp.CreateFormFile("file", "file."+ext)
		if e != nil {
			return e
		}
		part.Write(audio)
		mp.Close()
		data = b.Bytes()
		ct = mp.FormDataContentType()
		if p.HeaderEnv == nil {
			p.HeaderEnv = map[string]string{}
		}
		p.HeaderEnv["token"] = p.TokenEnv
		if ref := str(p.Options, "http_token_env"); ref != "" {
			p.HeaderEnv["token"] = ref
		}
	}
	start := time.Now()
	raw, _, e := providerRequest(r.Context(), p, ct, data)
	if e != nil {
		return e
	}
	var m Object
	if json.Unmarshal(raw, &m) != nil {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "ASR response is not JSON")
	}
	text := str(m, "text")
	if p.Mode == "legacy_tn" {
		if _, ok := m["status"]; ok && integer(m, "status", -1) != 0 {
			return problem(502, "PROVIDER_REJECTED", "ASR status is not zero")
		}
		if code, ok := m["code"]; ok && code != float64(0) {
			return problem(502, "PROVIDER_REJECTED", "ASR code is not zero")
		}
		if text == "" {
			text = str(m, "asr")
		}
		if text == "" {
			if result, ok := m["data"].(map[string]any); ok {
				if hypotheses, ok := result["hypothesis"].([]any); ok && len(hypotheses) > 0 {
					if first, ok := hypotheses[0].(map[string]any); ok {
						text = str(first, "transcriptNorm")
						if text == "" {
							text = str(first, "transcript")
						}
					}
				}
			}
		}
		if text == "" {
			if result, ok := m["result"].(map[string]any); ok {
				text = transcript(result)
			}
		}
	}
	if text == "" {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "ASR response lacks recognized text")
	}
	send(w, 200, Object{"text": text, "request_id": str(m, "request_id"), "provider_latency_ms": time.Since(start).Milliseconds()})
	return nil
}
func transcript(m Object) string {
	if text := str(m, "transcript"); text != "" {
		return text
	}
	if text := str(m, "text"); text != "" {
		return text
	}
	if h, ok := m["hypotheses"].([]any); ok && len(h) > 0 {
		if first, ok := h[0].(map[string]any); ok {
			return str(first, "transcript")
		}
	}
	return ""
}
func (s *Server) remoteWakeWord(w http.ResponseWriter, r *http.Request) error {
	if s.independentRuntime() {
		return problem(503, "WAKE_DETECTION_NOT_READY", "On-device TN wake-word model has not passed validation")
	}
	p, e := s.requireProvider("wake_word")
	if e != nil {
		return e
	}
	audio, e := io.ReadAll(io.LimitReader(r.Body, (2<<20)+1))
	if e != nil {
		return e
	}
	if len(audio) == 0 || len(audio) > 2<<20 {
		return problem(413, "INVALID_AUDIO_SIZE", "Wake-word audio must contain 1..2097152 bytes")
	}
	raw, _, e := providerRequest(r.Context(), p, r.Header.Get("Content-Type"), audio)
	if e != nil {
		return e
	}
	var out Object
	if json.Unmarshal(raw, &out) != nil {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "Wake-word provider response must be JSON")
	}
	send(w, 200, out)
	return nil
}
func (s *Server) zaloStart(w http.ResponseWriter, r *http.Request, p *Principal) error {
	return s.zaloAuth(w, r)
}
func (s *Server) stream(w http.ResponseWriter, r *http.Request) error {
	down, e := websocket.Accept(w, r, &websocket.AcceptOptions{})
	if e != nil {
		return nil
	}
	defer down.CloseNow()
	down.SetReadLimit(1 << 20)
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Minute)
	defer cancel()
	write := func(v Object) error { return down.Write(ctx, websocket.MessageText, []byte(jsonText(v))) }
	if s.independentRuntime() {
		if deepgramSelected() {
			return s.deepgramStream(ctx, down, write)
		}
		cfg, err := s.runtimeConfig()
		if err != nil {
			localSpeechError(write, "RUNTIME_CONFIG_INVALID", "TN speech configuration is unavailable")
			return nil
		}
		health := speechWorkerHealth(ctx, cfg.ASRURL)
		if health["healthy"] != true {
			localSpeechError(write, "ASR_NOT_READY", "TN speech recognition is not ready")
			return nil
		}
		return s.localSpeechStream(ctx, down, write, cfg.ASRURL)
	}
	p, e := s.requireProvider("asr")
	if e != nil || p.StreamURL == "" {
		code := "PROVIDER_NOT_CONFIGURED"
		message := "Configure your own ASR stream URL and credential references"
		if e == nil && p.URL != "" {
			code = "STREAMING_UNSUPPORTED"
			message = "Configured ASR supports HTTP decoding; use non-streaming fallback"
		}
		write(Object{"type": "error", "error": Object{"code": code, "message": message}})
		down.Close(websocket.StatusPolicyViolation, "Provider not configured")
		return nil
	}
	typ, raw, e := down.Read(ctx)
	if e != nil {
		return nil
	}
	var start Object
	if typ != websocket.MessageText || json.Unmarshal(raw, &start) != nil || str(start, "type") != "start" || integer(start, "sample_rate", 0) != 16000 || integer(start, "channels", 0) != 1 || str(start, "encoding") != "pcm_s16le" {
		write(Object{"type": "error", "error": Object{"code": "INVALID_START", "message": "16000 Hz mono PCM16 start frame required"}})
		return nil
	}
	endpoint := p.StreamURL
	headers := p.headers()
	if p.Mode == "legacy_tn" {
		u, _ := url.Parse(endpoint)
		q := u.Query()
		timestamp := strconv.FormatInt(time.Now().UnixMilli(), 10)
		q.Set("content-type", "audio/x-raw,layout=interleaved,rate=16000,format=S16LE,channels=1")
		q.Set("lat", fmt.Sprint(start["lat"]))
		if start["lat"] == nil {
			q.Set("lat", "0")
		}
		q.Set("long", fmt.Sprint(start["lon"]))
		if start["lon"] == nil {
			q.Set("long", "0")
		}
		q.Set("token", os.Getenv(p.TokenEnv))
		q.Set("user_id", p.userID())
		q.Set("lbk-client-id", p.userID())
		q.Set("client_app_type", p.ClientAppType)
		q.Set("version", p.Version)
		q.Set("timestamp", timestamp)
		u.RawQuery = q.Encode()
		endpoint = u.String()
		headers.Set("Authorization", hash(timestamp+hash(p.userID()+os.Getenv(p.SecretEnv))))
	}
	up, _, e := websocket.Dial(ctx, endpoint, &websocket.DialOptions{HTTPHeader: headers, HTTPClient: client()})
	if e != nil {
		write(Object{"type": "error", "error": Object{"code": "PROVIDER_UNAVAILABLE", "message": "ASR stream connection failed"}})
		return nil
	}
	defer up.CloseNow()
	up.SetReadLimit(1 << 20)
	if p.Mode == "generic" {
		if e = up.Write(ctx, websocket.MessageText, raw); e != nil {
			return nil
		}
	}
	if e = write(Object{"type": "ready"}); e != nil {
		return nil
	}
	errCh := make(chan error, 2)
	go func() {
		for {
			t, b, e := down.Read(ctx)
			if e != nil {
				errCh <- e
				return
			}
			if t == websocket.MessageBinary {
				if len(b)%2 != 0 {
					errCh <- fmt.Errorf("unaligned PCM")
					return
				}
				e = up.Write(ctx, t, b)
			} else {
				var command Object
				if json.Unmarshal(b, &command) != nil {
					errCh <- fmt.Errorf("invalid control")
					return
				}
				switch str(command, "type") {
				case "cancel":
					if p.Mode == "generic" {
						up.Write(ctx, websocket.MessageText, b)
					}
					up.Close(websocket.StatusNormalClosure, "cancelled")
					errCh <- nil
					return
				case "end":
					if p.Mode == "legacy_tn" {
						end := str(p.Options, "end_message")
						if end == "" {
							end = "EOS"
						}
						e = up.Write(ctx, websocket.MessageText, []byte(end))
					} else {
						e = up.Write(ctx, t, b)
					}
				default:
					errCh <- fmt.Errorf("invalid control")
					return
				}
			}
			if e != nil {
				errCh <- e
				return
			}
		}
	}()
	go func() {
		for {
			t, b, e := up.Read(ctx)
			if e != nil {
				errCh <- e
				return
			}
			if t != websocket.MessageText {
				continue
			}
			var event Object
			if json.Unmarshal(b, &event) != nil {
				errCh <- fmt.Errorf("invalid provider JSON")
				return
			}
			if p.Mode == "legacy_tn" {
				if integer(event, "status", 0) != 0 {
					write(Object{"type": "error", "error": Object{"code": "PROVIDER_REJECTED", "message": "ASR stream provider rejected audio"}})
					errCh <- fmt.Errorf("rejected")
					return
				}
				result, _ := event["result"].(map[string]any)
				kind := "partial"
				if result["final"] == true {
					kind = "final"
				}
				text := transcript(result)
				if text == "" {
					continue
				}
				event = Object{"type": kind, "text": text, "request_id": str(event, "id"), "provider_raw": event}
			}
			kind := str(event, "type")
			if kind != "partial" && kind != "final" && kind != "error" {
				continue
			}
			if e = write(event); e != nil {
				errCh <- e
				return
			}
			if kind == "final" || kind == "error" {
				errCh <- nil
				return
			}
		}
	}()
	streamErr := <-errCh
	if streamErr != nil && ctx.Err() == nil {
		write(Object{"type": "error", "error": Object{"code": "PROVIDER_STREAM_ERROR", "message": "Audio stream ended unexpectedly"}})
	}
	cancel()
	down.Close(websocket.StatusNormalClosure, "Session ended")
	return nil
}
