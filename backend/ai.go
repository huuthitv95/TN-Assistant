package main

// TN's cloud adapters never receive microphone audio or authority to execute
// arbitrary directives. Keys are encrypted separately from the public config.
import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

type AIProvider struct {
	ID                string `json:"id"`
	Kind              string `json:"kind"`
	Model             string `json:"model"`
	Endpoint          string `json:"endpoint"`
	Enabled           bool   `json:"enabled"`
	Priority          int    `json:"priority"`
	MaxRequestsPerDay int    `json:"max_requests_per_day"`
	MaxOutputTokens   int    `json:"max_output_tokens"`
	APIKey            string `json:"api_key,omitempty"`
	EncryptedKey      string `json:"encrypted_key,omitempty"`
	KeyConfigured     bool   `json:"key_configured"`
	ProjectAlias      string `json:"project_alias,omitempty"`
	QuotaGroup        string `json:"quota_group,omitempty"`
	FreeTierVerified  bool   `json:"free_tier_verified"`
}
type aiMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

var aiIdentifier = regexp.MustCompile(`^[a-zA-Z0-9_.-]{1,100}$`)

func (s *Server) aiPremium(uid string) bool { return s.premiumFeatures(uid)["ai_assistant"] == true }
func (s *Server) defaultAIMode(uid string) string {
	if s.aiPremium(uid) {
		return "qa"
	}
	return "commands"
}
func (s *Server) aiSettings(uid string) Object {
	var raw string
	m := Object{}
	if s.db.QueryRow("SELECT data FROM settings WHERE user_id=?", uid).Scan(&raw) == nil {
		json.Unmarshal([]byte(raw), &m)
	}
	return m
}
func (s *Server) aiProviders() ([]AIProvider, error) {
	m, e := s.record("config", "ai-providers")
	if errors.Is(e, sql.ErrNoRows) {
		return []AIProvider{}, nil
	}
	if e != nil {
		return nil, e
	}
	var p []AIProvider
	e = json.Unmarshal([]byte(jsonText(m["providers"])), &p)
	return p, e
}
func publicAIProviders(p []AIProvider) Object {
	out := append([]AIProvider{}, p...)
	for i := range out {
		out[i].KeyConfigured = out[i].EncryptedKey != ""
		out[i].EncryptedKey = ""
		out[i].APIKey = ""
	}
	return Object{"providers": out}
}
func (s *Server) aiCipher(allowCreate bool) (cipher.AEAD, error) {
	path := os.Getenv("TN_AI_MASTER_KEY_FILE")
	if path == "" {
		path = filepath.Join(filepath.Dir(s.cfg.DBPath), ".runtime", "tn-ai-master.key")
	}
	raw, e := os.ReadFile(path)
	if errors.Is(e, os.ErrNotExist) && allowCreate {
		existing, err := s.aiProviders()
		if err != nil {
			return nil, err
		}
		for _, provider := range existing {
			if provider.EncryptedKey != "" {
				return nil, problem(503, "AI_KEY_STORE_UNAVAILABLE", "Restore the existing private AI master key before changing credentials")
			}
		}
		if e = os.MkdirAll(filepath.Dir(path), 0700); e != nil {
			return nil, e
		}
		raw = make([]byte, 32)
		if _, e = rand.Read(raw); e != nil {
			return nil, e
		}
		f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if errors.Is(err, os.ErrExist) {
			raw, e = os.ReadFile(path)
		} else if err != nil {
			return nil, err
		} else {
			_, e = f.Write(raw)
			if closeErr := f.Close(); e == nil {
				e = closeErr
			}
		}
	}
	if e != nil || len(raw) != 32 {
		return nil, problem(503, "AI_KEY_STORE_UNAVAILABLE", "AI master key unavailable; restore the matching private key file")
	}
	block, e := aes.NewCipher(raw)
	if e != nil {
		return nil, e
	}
	return cipher.NewGCM(block)
}
func (s *Server) encryptAIKey(id, key string) (string, error) {
	c, e := s.aiCipher(true)
	if e != nil {
		return "", e
	}
	n := make([]byte, c.NonceSize())
	if _, e = rand.Read(n); e != nil {
		return "", e
	}
	return base64.StdEncoding.EncodeToString(c.Seal(n, n, []byte(key), []byte(id))), nil
}
func (s *Server) decryptAIKey(p AIProvider) (string, error) {
	if p.EncryptedKey == "" {
		return "", nil
	}
	c, e := s.aiCipher(false)
	if e != nil {
		return "", e
	}
	b, e := base64.StdEncoding.DecodeString(p.EncryptedKey)
	if e != nil || len(b) < c.NonceSize() {
		return "", problem(503, "AI_KEY_STORE_UNAVAILABLE", "AI credential cannot be decrypted")
	}
	out, e := c.Open(nil, b[:c.NonceSize()], b[c.NonceSize():], []byte(p.ID))
	if e != nil {
		return "", problem(503, "AI_KEY_STORE_UNAVAILABLE", "AI credential cannot be decrypted")
	}
	return string(out), nil
}
func defaultAIEndpoint(kind, model string) string {
	switch kind {
	case "gemini":
		return "https://generativelanguage.googleapis.com/v1beta/models/" + url.PathEscape(model) + ":generateContent"
	case "claude":
		return "https://api.anthropic.com/v1/messages"
	case "openai":
		return "https://api.openai.com/v1/responses"
	case "perplexity":
		return "https://api.perplexity.ai/v1/agent"
	}
	return ""
}
func validAIEndpoint(endpoint string) bool {
	u, e := url.Parse(endpoint)
	if e != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return false
	}
	return u.Scheme == "https" || u.Scheme == "http" && (u.Hostname() == "127.0.0.1" || u.Hostname() == "localhost" || u.Hostname() == "::1")
}
func (s *Server) adminAI(w http.ResponseWriter, r *http.Request, p *Principal, pieces []string) error {
	if len(pieces) < 2 || pieces[1] != "providers" || len(pieces) > 3 {
		return problem(404, "NOT_FOUND", "Unknown AI route")
	}
	providers, e := s.aiProviders()
	if e != nil {
		return e
	}
	if len(pieces) == 3 {
		if pieces[2] != "test" || r.Method != "POST" {
			return problem(405, "METHOD_NOT_ALLOWED", "POST provider test required")
		}
		m, e := body(r)
		if e != nil {
			return e
		}
		for _, provider := range providers {
			if provider.ID == str(m, "id") {
				result, err := s.runAIProbe(r.Context(), provider)
				if err != nil {
					return err
				}
				send(w, 200, result)
				return nil
			}
		}
		return problem(404, "NOT_FOUND", "AI provider not found")
	}
	if r.Method == "GET" {
		s.aiMu.Lock()
		defer s.aiMu.Unlock()
		providers, e = s.aiProviders()
		if e != nil {
			return e
		}
		send(w, 200, s.publicAIProviderState(providers))
		return nil
	}
	if r.Method != "PUT" {
		return problem(405, "METHOD_NOT_ALLOWED", "GET/PUT required")
	}
	m, e := body(r)
	if e != nil {
		return e
	}
	// Serialize the complete read/validate/write operation. No outbound requests
	// occur while this lock is held; config and audit commit in one transaction.
	s.aiMu.Lock()
	defer s.aiMu.Unlock()
	providers, e = s.aiProviders()
	if e != nil {
		return e
	}
	revision := s.aiConfigRevision()
	if err := s.checkAIConfigRevision(m, revision); err != nil {
		return err
	}
	var next []AIProvider
	if _, ok := m["providers"].([]any); !ok {
		return problem(400, "INVALID_AI_PROVIDER", "providers array required")
	}
	if e = json.Unmarshal([]byte(jsonText(m["providers"])), &next); e != nil || len(next) > 20 {
		return problem(400, "INVALID_AI_PROVIDER", "At most 20 AI providers")
	}
	old := map[string]AIProvider{}
	for _, item := range providers {
		old[item.ID] = item
	}
	seen := map[string]bool{}
	for i := range next {
		item := &next[i]
		if !aiIdentifier.MatchString(item.ID) || seen[item.ID] {
			return problem(400, "INVALID_AI_PROVIDER", "Unique provider id required")
		}
		seen[item.ID] = true
		if item.Kind != "gemini" && item.Kind != "claude" && item.Kind != "openai" && item.Kind != "perplexity" && item.Kind != "custom" {
			return problem(400, "INVALID_AI_PROVIDER", "Unknown provider kind")
		}
		if len(item.Model) == 0 || len(item.Model) > 120 || strings.ContainsAny(item.Model, "\r\n/?#") {
			return problem(400, "INVALID_AI_PROVIDER", "Model or Perplexity Agent preset required")
		}
		if item.Endpoint == "" {
			item.Endpoint = defaultAIEndpoint(item.Kind, item.Model)
		}
		if !validAIEndpoint(item.Endpoint) {
			return problem(400, "INVALID_ENDPOINT", "HTTPS endpoint without credentials/query required; loopback HTTP allowed")
		}
		if err := s.validateAIConfigPolicy(item); err != nil {
			return err
		}
		if item.MaxOutputTokens == 0 {
			item.MaxOutputTokens = 1024
		}
		if item.MaxRequestsPerDay == 0 {
			item.MaxRequestsPerDay = 100
		}
		if item.MaxOutputTokens < 64 || item.MaxOutputTokens > 8192 || item.MaxRequestsPerDay < 1 || item.MaxRequestsPerDay > 100000 || item.Priority < 0 || item.Priority > 1000 {
			return problem(400, "INVALID_AI_PROVIDER", "Invalid provider limits")
		}
		// Never accept ciphertext supplied by a browser. Preserve an existing key
		// only while its identity and destination remain the same.
		item.EncryptedKey = ""
		previous := old[item.ID]
		if previous.Kind == item.Kind && previous.Endpoint == item.Endpoint {
			item.EncryptedKey = previous.EncryptedKey
		}
		if item.APIKey != "" {
			if len(item.APIKey) > 4096 || strings.ContainsAny(item.APIKey, "\r\n") {
				return problem(400, "INVALID_AI_PROVIDER", "Invalid API key")
			}
			item.EncryptedKey, e = s.encryptAIKey(item.ID, item.APIKey)
			if e != nil {
				return e
			}
		}
		item.APIKey = ""
		item.KeyConfigured = false
		if item.Enabled && item.EncryptedKey == "" && item.Kind != "custom" {
			return problem(400, "AI_KEY_REQUIRED", "Enabled cloud provider requires an API key")
		}
	}
	if e = s.commitAIProviders(next, revision+1, str(p.User, "id")); e != nil {
		return e
	}
	send(w, 200, s.publicAIProviderState(next))
	return nil
}
func (s *Server) reserveAIUsage(p AIProvider) error {
	s.aiMu.Lock()
	defer s.aiMu.Unlock()
	if err := s.guardAIInference(p); err != nil {
		return err
	}
	return s.reserveAIUsageAt(p, time.Now())
}
func (s *Server) reserveAIUsageAt(p AIProvider, clock time.Time) error {
	id := aiUsageID(p, clock)
	if blocked, e := s.record("ai_cooldown", aiQuotaIdentity(p)); e == nil {
		if until, err := time.Parse(time.RFC3339Nano, str(blocked, "until")); err == nil && clock.Before(until) {
			return problem(429, "AI_PROJECT_QUOTA", "Project quota is cooling down; do not cycle credentials")
		}
	} else if !errors.Is(e, sql.ErrNoRows) {
		return e
	}
	m, e := s.record("ai_usage", id)
	if e != nil && !errors.Is(e, sql.ErrNoRows) {
		return e
	}
	n := integer(m, "count", 0)
	if n >= s.aiGroupDailyLimit(p) {
		return problem(429, "AI_DAILY_LIMIT", "AI daily request limit reached")
	}
	return s.put("ai_usage", id, "", Object{"count": n + 1})
}
func aiSystem(tools bool) string {
	text := "Bạn là trợ lý TN, đọc là Ti En. Trả lời ngắn gọn bằng tiếng Việt. Không khẳng định đã thực hiện thao tác trên thiết bị. Thời gian thiết bị/backend hiện tại: " + time.Now().Format("Monday, 2006-01-02 15:04 MST") + ". Nếu người dùng hỏi thông tin đang thay đổi như thời tiết, tin tức, giá thị trường, xổ số hoặc lịch phát sóng mà không có nguồn trực tiếp trong ngữ cảnh, hãy nói rõ chưa có dữ liệu trực tiếp; không đoán số liệu hiện tại."
	if tools {
		text += ` Hôm nay là ` + time.Now().Format("Monday, 2006-01-02 15:04 MST") + `. Với câu hỏi tri thức thông thường, thời tiết, tin tức, giá thị trường, xổ số, lịch phát sóng hoặc dữ liệu đang thay đổi, không tự bịa số liệu hiện tại; hãy nói rõ khi không có nguồn dữ liệu trực tiếp.
Chỉ trả JSON: {"utterance":"...","tool":"none|open_app|navigate|call|play_music|play_radio|play_video|play_tv|set_alarm|create_reminder|show_alarms|dismiss_alarm","arguments":{}}.
open_app dùng package_name; navigate dùng query; call dùng phone_number và phải yêu cầu xác nhận.
play_music, play_radio, play_video, play_tv chỉ dùng khi người dùng yêu cầu mở/phát/tìm nội dung; truyền query ngắn gọn, không tự khẳng định đã phát thành công.
set_alarm dùng hour (0-23), minute (0-59), repeat_type (None|Daily|Weekly|Weekdays|Weekend), day, month, year. create_reminder dùng task, hour, minute, repeat_type và ngày tháng năm. Tính ngày tương đối dựa trên ngày giờ hiện tại đã nêu. Cả hai phải yêu cầu xác nhận trước khi trả tool; không tự chọn thời gian nếu người dùng chưa nói rõ.
show_alarms chỉ dùng khi người dùng muốn xem báo thức; mở trang báo thức trong ứng dụng Đồng hồ và không nói rằng TN đã đọc danh sách.
 dismiss_alarm dùng search_mode=next cho báo thức tiếp theo/đang reo, hoặc search_mode=time kèm hour (0-23), minute (0-59) khi người dùng nêu giờ. Luôn yêu cầu xác nhận. Android sẽ mở ứng dụng Đồng hồ để chọn/xác nhận; chỉ nói đang mở lựa chọn, không khẳng định đã tắt. Không dùng công cụ này để xóa hàng loạt.
Không thực hiện công cụ khác hoặc tạo directive tùy ý.`
	}
	return text
}
func (s *Server) callAI(ctx context.Context, p AIProvider, messages []aiMessage, tools bool) (string, error) {
	if err := s.guardAIInference(p); err != nil {
		return "", err
	}
	key, e := s.decryptAIKey(p)
	if e != nil {
		return "", e
	}
	system := aiSystem(tools)
	payload := Object{}
	h := http.Header{"Content-Type": []string{"application/json"}}
	switch p.Kind {
	case "gemini":
		contents := []Object{}
		for _, m := range messages {
			role := m.Role
			if role == "assistant" {
				role = "model"
			}
			contents = append(contents, Object{"role": role, "parts": []Object{{"text": m.Content}}})
		}
		payload = Object{"contents": contents, "systemInstruction": Object{"parts": []Object{{"text": system}}}, "generationConfig": Object{"maxOutputTokens": p.MaxOutputTokens}}
		if p.Model == independentGeminiModel {
			payload["generationConfig"].(Object)["thinkingConfig"] = Object{"thinkingLevel": "LOW"}
		}
		h.Set("x-goog-api-key", key)
	case "claude":
		payload = Object{"model": p.Model, "system": system, "messages": messages, "max_tokens": p.MaxOutputTokens}
		h.Set("x-api-key", key)
		h.Set("anthropic-version", "2023-06-01")
	case "openai":
		payload = Object{"model": p.Model, "instructions": system, "input": messages, "max_output_tokens": p.MaxOutputTokens, "store": false}
		h.Set("Authorization", "Bearer "+key)
	case "perplexity":
		// Current Agent API replaces the retired Sonar chat API. An explicit
		// /v1/sonar endpoint remains available for synchronous migration accounts.
		if strings.HasSuffix(p.Endpoint, "/sonar") {
			payload = Object{"model": p.Model, "messages": append([]aiMessage{{"system", system}}, messages...), "max_tokens": p.MaxOutputTokens}
		} else {
			payload = Object{"preset": p.Model, "instructions": system, "input": messages, "max_output_tokens": p.MaxOutputTokens}
		}
		h.Set("Authorization", "Bearer "+key)
	case "custom":
		payload = Object{"model": p.Model, "messages": append([]aiMessage{{"system", system}}, messages...), "max_tokens": p.MaxOutputTokens}
		if key != "" {
			h.Set("Authorization", "Bearer "+key)
		}
	default:
		return "", problem(503, "INVALID_AI_PROVIDER", "Unknown provider protocol")
	}
	req, e := http.NewRequestWithContext(ctx, "POST", p.Endpoint, bytes.NewBufferString(jsonText(payload)))
	if e != nil {
		return "", problem(503, "INVALID_ENDPOINT", "Invalid AI endpoint")
	}
	req.Header = h
	response, e := client().Do(req)
	if e != nil {
		if ctx.Err() != nil {
			return "", problem(504, "AI_TIMEOUT", "AI request ended before a result was confirmed")
		}
		return "", problem(502, "AI_UNAVAILABLE", "AI provider connection failed")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		if response.StatusCode == http.StatusTooManyRequests {
			if err := s.markAIQuotaCooldown(p, response.Header.Get("Retry-After")); err != nil {
				return "", err
			}
			return "", problem(429, "AI_PROJECT_QUOTA", "AI project quota exhausted; wait before retrying")
		}
		if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
			return "", problem(502, "AI_CREDENTIAL_REJECTED", "AI credentials or project access were rejected")
		}
		return "", problem(502, "AI_HTTP_ERROR", "AI provider rejected request; verify model, credentials and quota")
	}
	raw, e := io.ReadAll(io.LimitReader(response.Body, 2<<20))
	if e != nil {
		return "", problem(502, "AI_INVALID_RESPONSE", "Cannot read AI response")
	}
	var decoded Object
	if json.Unmarshal(raw, &decoded) != nil {
		return "", problem(502, "AI_INVALID_RESPONSE", "AI provider returned invalid JSON")
	}
	result := extractAIText(p.Kind, decoded)
	if result == "" || len(result) > 64000 {
		return "", problem(502, "AI_INVALID_RESPONSE", "AI response contains no bounded text")
	}
	return result, nil
}
func extractAIText(kind string, m Object) string {
	if kind == "gemini" {
		items, _ := m["candidates"].([]any)
		if len(items) > 0 {
			candidate, _ := items[0].(map[string]any)
			content, _ := candidate["content"].(map[string]any)
			return aiPartsText(content["parts"])
		}
	}
	if kind == "claude" {
		return aiPartsText(m["content"])
	}
	if kind == "openai" || kind == "perplexity" {
		items, _ := m["output"].([]any)
		text := ""
		for _, v := range items {
			item, _ := v.(map[string]any)
			if str(item, "type") == "message" {
				text += aiPartsText(item["content"])
			}
		}
		if text != "" {
			return text
		}
	}
	items, _ := m["choices"].([]any)
	if len(items) > 0 {
		item, _ := items[0].(map[string]any)
		message, _ := item["message"].(map[string]any)
		return str(message, "content")
	}
	return ""
}
func aiPartsText(v any) string {
	parts, _ := v.([]any)
	out := ""
	for _, part := range parts {
		m, _ := part.(map[string]any)
		if m["thought"] == true {
			continue
		}
		out += str(m, "text")
	}
	return out
}
func (s *Server) aiAnswer(ctx context.Context, messages []aiMessage, tools bool) (string, error) {
	text, _, err := s.aiAnswerWithSources(ctx, messages, tools)
	return text, err
}

func aiNeedsLiveSearch(text string) bool {
	value := strings.ToLower(strings.TrimSpace(text))
	for _, phrase := range []string{
		"thời tiết", "dự báo", "có mưa", "mưa không", "tin tức", "tin mới", "tin hôm nay", "hôm nay có tin gì mới", "thời sự hôm nay", "giá vàng", "giá đô", "giá usd", "giá xăng", "giá cổ phiếu", "giá thị trường", "tỷ giá", "tỉ giá", "đô la mỹ", "yên là bao nhiêu", "bao nhiêu tiền", "vnindex", "xổ số", "kết quả xổ số", "lịch phát sóng", "chương trình hôm nay", "truyền hình hôm nay", "vtv3 hôm nay", "tử vi", "covid", "corona", "quốc khánh năm nay", "trung thu năm nay", "tết năm nay", "lịch âm", "âm lịch", "ngày lễ", "ngày nghỉ lễ", "bao nhiêu ngày nữa", "bao giờ tới", "năm nay", "dân số", "gdp", "nhân viên", "mới nhất", "hiện tại", "đang diễn ra",
	} {
		if strings.Contains(value, phrase) {
			return true
		}
	}
	return false
}

func geminiInteractionsEndpoint(endpoint string) (string, error) {
	u, err := url.Parse(endpoint)
	official := u != nil && u.Scheme == "https" && strings.EqualFold(u.Hostname(), "generativelanguage.googleapis.com")
	local := u != nil && u.Scheme == "http" && (u.Hostname() == "127.0.0.1" || u.Hostname() == "localhost" || u.Hostname() == "::1")
	if err != nil || (!official && !local) || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return "", problem(503, "AI_SEARCH_CONFIG_REQUIRED", "Live search requires the official Gemini API endpoint")
	}
	path := u.EscapedPath()
	if strings.HasSuffix(path, ":generateContent") {
		marker := strings.Index(path, "/models/")
		if marker <= 0 {
			return "", problem(503, "AI_SEARCH_CONFIG_REQUIRED", "Live search requires a configured Gemini model endpoint")
		}
		u.Path = path[:marker] + "/interactions"
		u.RawPath = ""
	} else if !strings.HasSuffix(path, "/interactions") {
		return "", problem(503, "AI_SEARCH_CONFIG_REQUIRED", "Live search requires a configured Gemini model endpoint")
	}
	return u.String(), nil
}

func interactionInput(messages []aiMessage) string {
	var builder strings.Builder
	for _, message := range messages {
		role := "Người dùng"
		if message.Role == "assistant" {
			role = "TN"
		}
		builder.WriteString(role)
		builder.WriteString(": ")
		builder.WriteString(message.Content)
		builder.WriteByte('\n')
	}
	return strings.TrimSpace(builder.String())
}

func extractGeminiInteraction(m Object) (string, []Object) {
	steps, _ := m["steps"].([]any)
	text := strings.Builder{}
	sources := []Object{}
	seen := map[string]bool{}
	for _, stepValue := range steps {
		step, _ := stepValue.(map[string]any)
		if str(step, "type") != "model_output" {
			continue
		}
		contents, _ := step["content"].([]any)
		for _, contentValue := range contents {
			content, _ := contentValue.(map[string]any)
			if str(content, "type") != "text" {
				continue
			}
			text.WriteString(str(content, "text"))
			annotations, _ := content["annotations"].([]any)
			for _, annotationValue := range annotations {
				annotation, _ := annotationValue.(map[string]any)
				if str(annotation, "type") != "url_citation" {
					continue
				}
				citationURL := strings.TrimSpace(str(annotation, "url"))
				parsed, err := url.Parse(citationURL)
				if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil || seen[citationURL] {
					continue
				}
				seen[citationURL] = true
				title := strings.TrimSpace(str(annotation, "title"))
				if title == "" {
					title = parsed.Hostname()
				}
				sources = append(sources, Object{"title": title, "url": citationURL})
				if len(sources) >= 10 {
					return strings.TrimSpace(text.String()), sources
				}
			}
		}
	}
	return strings.TrimSpace(text.String()), sources
}

// geminiErrorLabel returns a short, allowlisted error label. Interactions API
// uses a URI in error.code, while other Gemini endpoints use a Google RPC
// status enum. Never return the URI or a provider-supplied free-form value.
func geminiErrorLabel(raw string) string {
	raw = strings.TrimSpace(raw)
	if i := strings.LastIndexAny(raw, "/#"); i >= 0 {
		raw = raw[i+1:]
	}
	if i := strings.LastIndexByte(raw, '.'); i >= 0 {
		raw = raw[i+1:]
	}
	label := strings.ToUpper(strings.NewReplacer("-", "_", " ", "_").Replace(raw))
	switch label {
	case "INVALID_ARGUMENT", "INVALIDARGUMENT":
		return "INVALID_ARGUMENT"
	case "UNAUTHENTICATED":
		return "UNAUTHENTICATED"
	case "PERMISSION_DENIED", "PERMISSIONDENIED":
		return "PERMISSION_DENIED"
	case "NOT_FOUND", "NOTFOUND":
		return "NOT_FOUND"
	case "RESOURCE_EXHAUSTED", "RESOURCEEXHAUSTED":
		return "RESOURCE_EXHAUSTED"
	case "FAILED_PRECONDITION", "FAILEDPRECONDITION":
		return "FAILED_PRECONDITION"
	case "UNAVAILABLE":
		return "UNAVAILABLE"
	case "INTERNAL":
		return "INTERNAL"
	case "DEADLINE_EXCEEDED", "DEADLINEEXCEEDED":
		return "DEADLINE_EXCEEDED"
	case "UNSUPPORTED":
		return "UNSUPPORTED"
	case "RATE_LIMIT_EXCEEDED", "RATELIMITEXCEEDED":
		return "RATE_LIMIT_EXCEEDED"
	case "QUOTA_EXCEEDED", "QUOTAEXCEEDED":
		return "QUOTA_EXCEEDED"
	case "SERVICE_DISABLED", "SERVICEDISABLED":
		return "SERVICE_DISABLED"
	case "BILLING_DISABLED", "BILLINGDISABLED":
		return "BILLING_DISABLED"
	case "USER_PROJECT_DENIED", "USERPROJECTDENIED":
		return "USER_PROJECT_DENIED"
	case "API_KEY_INVALID", "APIKEYINVALID":
		return "API_KEY_INVALID"
	case "API_KEY_SERVICE_BLOCKED", "APIKEYSERVICEBLOCKED":
		return "API_KEY_SERVICE_BLOCKED"
	default:
		return "UNKNOWN"
	}
}

// geminiMessageReason classifies a provider message without retaining or
// logging its free-form text. The returned categories are fixed labels only.
func geminiMessageReason(message string) string {
	message = strings.ToLower(message)
	switch {
	case strings.Contains(message, "rate limit"), strings.Contains(message, "rate-limit"),
		strings.Contains(message, "too many requests"), strings.Contains(message, "requests per minute"):
		return "RATE_LIMIT"
	case strings.Contains(message, "quota"), strings.Contains(message, "resource exhausted"),
		strings.Contains(message, "resource_exhausted"):
		return "QUOTA_OR_RATE_LIMIT"
	case strings.Contains(message, "billing"), strings.Contains(message, "spend cap"),
		strings.Contains(message, "prepay"), strings.Contains(message, "credit balance"):
		return "BILLING"
	case strings.Contains(message, "api key"), strings.Contains(message, "api_key"),
		strings.Contains(message, "key was blocked"):
		return "KEY_ACCESS"
	case strings.Contains(message, "permission denied"), strings.Contains(message, "access denied"),
		strings.Contains(message, "not authorized"):
		return "ACCESS_DENIED"
	case strings.Contains(message, "model") && (strings.Contains(message, "not found") ||
		strings.Contains(message, "not supported") || strings.Contains(message, "unsupported")):
		return "MODEL_ACCESS"
	default:
		return "UNKNOWN"
	}
}

// geminiUpstreamErrorDiagnostic extracts only stable allowlisted status, code,
// and reason labels. Upstream error messages may echo prompts, user data, or
// request details, so they must never be written to server logs.
func geminiUpstreamErrorDiagnostic(raw []byte) (status, code, reason string) {
	var payload struct {
		Error struct {
			Status  string          `json:"status"`
			Code    json.RawMessage `json:"code"`
			Message string          `json:"message"`
			Details []struct {
				Reason string `json:"reason"`
			} `json:"details"`
		} `json:"error"`
	}
	if json.Unmarshal(raw, &payload) != nil {
		return "UNKNOWN", "UNKNOWN", "UNKNOWN"
	}

	status = geminiErrorLabel(payload.Error.Status)
	var providerCode string
	if json.Unmarshal(payload.Error.Code, &providerCode) == nil {
		code = geminiErrorLabel(providerCode)
	} else {
		var numericCode int
		if json.Unmarshal(payload.Error.Code, &numericCode) == nil && numericCode >= 100 && numericCode <= 599 {
			code = "HTTP_" + strconv.Itoa(numericCode)
		} else {
			code = "UNKNOWN"
		}
	}
	if status == "UNKNOWN" && code != "UNKNOWN" && !strings.HasPrefix(code, "HTTP_") {
		status = code
	}
	for _, detail := range payload.Error.Details {
		if label := geminiErrorLabel(detail.Reason); label != "UNKNOWN" {
			reason = label
			break
		}
	}
	if reason == "" {
		reason = geminiMessageReason(payload.Error.Message)
		if reason == "UNKNOWN" && status == "UNKNOWN" && strings.HasPrefix(code, "HTTP_429") {
			reason = "RATE_OR_QUOTA"
		}
	}
	return status, code, reason
}

func geminiUpstreamErrorStatus(raw []byte) string {
	status, _, _ := geminiUpstreamErrorDiagnostic(raw)
	return status
}

func (s *Server) callGeminiGrounded(ctx context.Context, p AIProvider, messages []aiMessage) (string, []Object, error) {
	if s.independentRuntime() {
		return "", nil, problem(503, "AI_FREE_POLICY", "Search grounding is disabled in free-only mode; use a verified free data module")
	}
	if p.Kind != "gemini" {
		return "", nil, problem(503, "AI_SEARCH_CONFIG_REQUIRED", "A grounded Gemini provider is required for live data")
	}
	endpoint, err := geminiInteractionsEndpoint(p.Endpoint)
	if err != nil {
		return "", nil, err
	}
	key, err := s.decryptAIKey(p)
	if err != nil {
		return "", nil, err
	}
	model := strings.TrimPrefix(p.Model, "models/")
	if model == "" || len(model) > 120 {
		return "", nil, problem(503, "AI_SEARCH_CONFIG_REQUIRED", "A Gemini model is required for live data")
	}
	payload := Object{
		"model": model, "input": interactionInput(messages), "system_instruction": aiSystem(false),
		"tools": []Object{{"type": "google_search"}}, "store": false,
		"generation_config": Object{"max_output_tokens": p.MaxOutputTokens},
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(jsonText(payload)))
	if err != nil {
		return "", nil, problem(503, "AI_SEARCH_CONFIG_REQUIRED", "Invalid Gemini live-search endpoint")
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("x-goog-api-key", key)
	response, err := client().Do(request)
	if err != nil {
		log.Printf("Gemini live search transport failure: error_type=%T", err)
		return "", nil, problem(502, "AI_SEARCH_UNAVAILABLE", "Gemini live search could not connect")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		upstreamError, _ := io.ReadAll(io.LimitReader(response.Body, 16<<10))
		googleStatus, googleCode, googleReason := geminiUpstreamErrorDiagnostic(upstreamError)
		log.Printf("Gemini live search upstream rejected request: http_status=%d google_status=%s google_code=%s google_reason=%s", response.StatusCode, googleStatus, googleCode, googleReason)
		return "", nil, problem(502, "AI_SEARCH_UNAVAILABLE", "Gemini live search rejected the request; verify model, access, credentials, and quota")
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, 2<<20))
	if err != nil {
		return "", nil, problem(502, "AI_INVALID_RESPONSE", "Cannot read Gemini live-search response")
	}
	var decoded Object
	if json.Unmarshal(raw, &decoded) != nil {
		return "", nil, problem(502, "AI_INVALID_RESPONSE", "Gemini live search returned invalid JSON")
	}
	answer, sources := extractGeminiInteraction(decoded)
	if answer == "" || len(answer) > 64000 {
		return "", nil, problem(502, "AI_INVALID_RESPONSE", "Gemini live search returned no bounded answer")
	}
	if len(sources) == 0 {
		return "", nil, problem(502, "AI_UNGROUNDED_RESPONSE", "Gemini did not return verifiable web citations")
	}
	return answer, sources, nil
}

func (s *Server) aiAnswerWithSources(ctx context.Context, messages []aiMessage, tools bool) (string, []Object, error) {
	providers, e := s.aiProviders()
	if e != nil {
		return "", nil, e
	}
	sort.SliceStable(providers, func(i, j int) bool { return providers[i].Priority < providers[j].Priority })
	last := error(problem(503, "AI_NOT_CONFIGURED", "Admin must configure an AI provider"))
	liveSearch := false
	if !tools && len(messages) > 0 {
		liveSearch = aiNeedsLiveSearch(messages[len(messages)-1].Content)
		if liveSearch {
			if s.independentRuntime() {
				return "", nil, problem(503, "LIVE_DATA_PROVIDER_REQUIRED", "Current information requires a configured free data module")
			}
			last = problem(503, "LIVE_DATA_PROVIDER_REQUIRED", "A Gemini provider with live web grounding is required for current information")
		}
	}
	for _, p := range providers {
		if !p.Enabled {
			continue
		}
		if liveSearch && p.Kind != "gemini" {
			continue
		}
		if e = s.reserveAIUsage(p); e != nil {
			last = e
			var quotaError *apiError
			if errors.As(e, &quotaError) && quotaError.Status == 429 {
				return "", nil, e
			}
			continue
		}
		var text string
		var sources []Object
		if liveSearch {
			text, sources, e = s.callGeminiGrounded(ctx, p, messages)
		} else {
			text, e = s.callAI(ctx, p, messages, tools)
		}
		if e == nil {
			return text, sources, nil
		}
		last = e
		var policyError *apiError
		if errors.As(e, &policyError) && policyError.Status == 429 {
			return "", nil, e
		}
		// Tools mode uses one provider only. Never repeat a possibly actionable
		// request after transport ambiguity. No adapter executes remote tools.
		if tools || ctx.Err() != nil {
			return "", nil, last
		}
	}
	return "", nil, last
}
