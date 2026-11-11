package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func configureTestAI(t *testing.T, s *Server, endpoint string) string {
	t.Helper()
	admin := adminToken(t, s)
	code, m := call(t, s, "PUT", "/v1/admin/ai/providers", admin, Object{"providers": []Object{{"id": "fixture", "kind": "custom", "model": "fixture-model", "endpoint": endpoint, "enabled": true, "priority": 0, "api_key": "fixture-private-key", "max_requests_per_day": 100, "max_output_tokens": 1024}}})
	requireStatus(t, 200, code, m)
	return admin
}
func grantTestAI(t *testing.T, s *Server, g Object) {
	t.Helper()
	uid := str(g["user"].(map[string]any), "id")
	code, m := call(t, s, "PUT", "/v1/admin/premium-local/"+uid, adminToken(t, s), Object{"duration_days": 1})
	requireStatus(t, 200, code, m)
}
func TestAIProtocolAdapters(t *testing.T) {
	s := testServer(t)
	for _, kind := range []string{"gemini", "claude", "openai", "perplexity", "custom"} {
		t.Run(kind, func(t *testing.T) {
			upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var m Object
				if json.NewDecoder(r.Body).Decode(&m) != nil {
					t.Error("bad JSON")
				}
				if str(m, "model") == "" && kind != "gemini" && kind != "perplexity" {
					t.Error("missing model")
				}
				response := Object{}
				switch kind {
				case "gemini":
					if r.Header.Get("x-goog-api-key") != "test-key" || m["contents"] == nil || m["messages"] != nil {
						t.Error("Gemini protocol mismatch")
					}
					response = Object{"candidates": []Object{{"content": Object{"parts": []Object{{"text": "Xin chào"}}}}}}
				case "claude":
					if r.Header.Get("x-api-key") != "test-key" || r.Header.Get("anthropic-version") != "2023-06-01" || m["system"] == nil {
						t.Error("Claude protocol mismatch")
					}
					response = Object{"content": []Object{{"type": "text", "text": "Xin chào"}}}
				case "openai", "perplexity":
					if r.Header.Get("Authorization") != "Bearer test-key" || m["input"] == nil || m["messages"] != nil {
						t.Error("Responses protocol mismatch")
					}
					if kind == "perplexity" && str(m, "preset") != "fast" {
						t.Error("missing Agent preset")
					}
					response = Object{"output": []Object{{"type": "message", "content": []Object{{"type": "output_text", "text": "Xin chào"}}}}}
				case "custom":
					if r.Header.Get("Authorization") != "Bearer test-key" || m["messages"] == nil || m["input"] != nil {
						t.Error("Custom protocol mismatch")
					}
					response = Object{"choices": []Object{{"message": Object{"content": "Xin chào"}}}}
				}
				send(w, 200, response)
			}))
			defer upstream.Close()
			encrypted, e := s.encryptAIKey(kind, "test-key")
			if e != nil {
				t.Fatal(e)
			}
			out, e := s.callAI(context.Background(), AIProvider{ID: kind, Kind: kind, Model: "fast", Endpoint: upstream.URL, MaxOutputTokens: 1024, EncryptedKey: encrypted}, []aiMessage{{"user", "Xin chào"}}, false)
			if e != nil || out != "Xin chào" {
				t.Fatalf("adapter failed: %v", e)
			}
		})
	}
}
func TestGeminiGroundedSearchUsesInteractionsAndReturnsOnlyHttpsCitations(t *testing.T) {
	s := testServer(t)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1beta/interactions" || r.Header.Get("x-goog-api-key") != "test-key" {
			t.Errorf("wrong grounded Gemini request: path=%s", r.URL.Path)
		}
		var request Object
		if json.NewDecoder(r.Body).Decode(&request) != nil {
			t.Fatal("invalid grounded request JSON")
		}
		toolList, _ := request["tools"].([]any)
		if str(request, "model") != "gemini-2.5-flash" || str(request, "input") == "" || request["store"] != false || len(toolList) != 1 || str(toolList[0].(map[string]any), "type") != "google_search" {
			t.Errorf("grounding was not explicitly enabled: %v", request)
		}
		send(w, 200, Object{"steps": []Object{
			{"type": "google_search_call", "arguments": Object{"queries": []string{"weather Hanoi"}}},
			{"type": "model_output", "content": []Object{{"type": "text", "text": "Hà Nội hôm nay có mưa nhẹ.", "annotations": []Object{
				{"type": "url_citation", "title": "Báo thời tiết", "url": "https://weather.example.vn/hanoi"},
				{"type": "url_citation", "title": "Unsafe", "url": "javascript:alert(1)"},
			}}}},
		}})
	}))
	defer upstream.Close()
	encrypted, err := s.encryptAIKey("fixture", "test-key")
	if err != nil {
		t.Fatal(err)
	}
	answer, sources, err := s.callGeminiGrounded(context.Background(), AIProvider{ID: "fixture", Kind: "gemini", Model: "gemini-2.5-flash", Endpoint: upstream.URL + "/v1beta/models/gemini-2.5-flash:generateContent", MaxOutputTokens: 512, EncryptedKey: encrypted}, []aiMessage{{"user", "Thời tiết Hà Nội hôm nay thế nào?"}})
	if err != nil || answer != "Hà Nội hôm nay có mưa nhẹ." || len(sources) != 1 || str(sources[0], "url") != "https://weather.example.vn/hanoi" {
		t.Fatalf("grounded response/citations failed validation: answer=%q sources=%v err=%v", answer, sources, err)
	}
}

func TestGeminiUpstreamErrorStatusIsAllowlistedAndOmitsMessage(t *testing.T) {
	for _, test := range []struct {
		name string
		body string
		want string
	}{
		{"known rpc status", `{"error":{"status":"RESOURCE_EXHAUSTED","message":"private prompt text"}}`, "RESOURCE_EXHAUSTED"},
		{"unknown status", `{"error":{"status":"PRIVATE_KEY_VALUE","message":"must not escape"}}`, "UNKNOWN"},
		{"invalid json", `not-json`, "UNKNOWN"},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := geminiUpstreamErrorStatus([]byte(test.body)); got != test.want {
				t.Fatalf("status=%q, want %q", got, test.want)
			}
		})
	}
}

func TestGeminiUpstreamErrorDiagnosticParsesInteractionsCodesSafely(t *testing.T) {
	status, code, reason := geminiUpstreamErrorDiagnostic([]byte(`{"error":{"code":"https://generativelanguage.googleapis.com/errors/resource-exhausted","message":"private prompt and account data","details":[{"reason":"RATE_LIMIT_EXCEEDED","description":"private quota project details"}]}}`))
	if status != "RESOURCE_EXHAUSTED" || code != "RESOURCE_EXHAUSTED" || reason != "RATE_LIMIT_EXCEEDED" {
		t.Fatalf("sanitized diagnostics = (%q, %q, %q)", status, code, reason)
	}
	if strings.Contains(status+code+reason, "private") || strings.Contains(status+code+reason, "generativelanguage") {
		t.Fatal("provider text or URI escaped diagnostic allowlist")
	}

	status, code, reason = geminiUpstreamErrorDiagnostic([]byte(`{"error":{"code":429,"message":"Resource exhausted; quota may be exceeded. private"}}`))
	if status != "UNKNOWN" || code != "HTTP_429" || reason != "QUOTA_OR_RATE_LIMIT" {
		t.Fatalf("numeric Google RPC diagnostics = (%q, %q, %q)", status, code, reason)
	}

	status, code, reason = geminiUpstreamErrorDiagnostic([]byte(`{"error":{"code":"private-secret","message":"private"}}`))
	if status != "UNKNOWN" || code != "UNKNOWN" || reason != "UNKNOWN" {
		t.Fatalf("unknown diagnostics were not suppressed: (%q, %q, %q)", status, code, reason)
	}
}

func TestTNCurrentInformationSkillsRequestGrounding(t *testing.T) {
	for _, query := range []string{
		"Thời tiết ngày mai", "Giá vàng hôm nay", "Tỷ giá Euro", "Tin tức mới nhất", "VNIndex hiện tại",
		"Kết quả xổ số hôm nay", "Lịch phát sóng VTV3 hôm nay", "Tử vi Ma Kết hôm nay", "Hôm nay là ngày âm lịch nào?",
		"Ngày lễ tiếp theo ở Việt Nam là ngày nào?", "Phú Quốc ngày mốt có mưa không", "Truyền hình hôm nay xem gì",
		"Chương trình VTV3 hôm nay", "10.000 Yên là bao nhiêu Đô la Mỹ", "6.000 Đô là bao nhiêu tiền",
		"Sơn Tùng năm nay bao nhiêu tuổi", "Dân số Việt Nam là bao nhiêu", "Apple có bao nhiêu nhân viên",
		"Còn bao nhiêu ngày nữa là Tết", "Bao giờ tới Giáng sinh",
	} {
		if !aiNeedsLiveSearch(query) {
			t.Errorf("current-information request did not require grounded data: %q", query)
		}
	}
	for _, query := range []string{"Tại sao có cầu vồng?", "20 x 2 + 15 x 3 bằng bao nhiêu?", "Con gà tiếng Anh là gì?", "Đặt báo thức lúc 8 giờ sáng mai"} {
		if aiNeedsLiveSearch(query) {
			t.Errorf("static or device-action request incorrectly triggered web grounding: %q", query)
		}
	}
}

func TestAIProviderKeysRolesLimitsAndCancellation(t *testing.T) {
	s := testServer(t)
	var count atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		send(w, 200, Object{"choices": []Object{{"message": Object{"content": "ready"}}}})
	}))
	defer upstream.Close()
	admin := configureTestAI(t, s, upstream.URL)
	g := guest(t, s)
	code, m := call(t, s, "GET", "/v1/admin/ai/providers", str(g, "access_token"), nil)
	requireStatus(t, 403, code, m)
	code, m = call(t, s, "GET", "/v1/admin/ai/providers", admin, nil)
	requireStatus(t, 200, code, m)
	if strings.Contains(jsonText(m), "fixture-private-key") || strings.Contains(jsonText(m), "encrypted_key") {
		t.Fatal("credential leaked")
	}
	saved, _ := s.record("config", "ai-providers")
	if strings.Contains(jsonText(saved), "fixture-private-key") {
		t.Fatal("key not encrypted at rest")
	}
	providers, _ := s.aiProviders()
	provider := providers[0]
	provider.MaxRequestsPerDay = 1
	if e := s.reserveAIUsage(provider); e != nil {
		t.Fatal(e)
	}
	if e := s.reserveAIUsage(provider); e == nil {
		t.Fatal("quota not enforced")
	}
	if e := os.Rename(filepath.Join(filepath.Dir(s.cfg.DBPath), ".runtime", "tn-ai-master.key"), filepath.Join(filepath.Dir(s.cfg.DBPath), "master.saved")); e != nil {
		t.Fatal(e)
	}
	if _, e := s.decryptAIKey(provider); e == nil {
		t.Fatal("missing master silently replaced")
	}
	if _, e := os.Stat(filepath.Join(filepath.Dir(s.cfg.DBPath), ".runtime", "tn-ai-master.key")); !os.IsNotExist(e) {
		t.Fatal("decryption recreated master")
	}
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
		case <-time.After(100 * time.Millisecond):
		}
	}))
	defer slow.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	_, e := s.callAI(ctx, AIProvider{Kind: "custom", Model: "x", Endpoint: slow.URL}, []aiMessage{{"user", "hello"}}, false)
	if e == nil {
		t.Fatal("cancellation not honored")
	}
}
func TestAIPremiumContextIdempotencyAndTools(t *testing.T) {
	s := testServer(t)
	var requests atomic.Int32
	var sizes []int
	answer := "Câu trả lời TN"
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		var m Object
		json.NewDecoder(r.Body).Decode(&m)
		messages, _ := m["messages"].([]any)
		sizes = append(sizes, len(messages))
		send(w, 200, Object{"choices": []Object{{"message": Object{"content": answer}}}})
	}))
	defer upstream.Close()
	configureTestAI(t, s, upstream.URL)
	g := guest(t, s)
	token := str(g, "access_token")
	code, m := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "hỏi AI", "mode": "qa"})
	requireStatus(t, 403, code, m)
	if requests.Load() != 0 {
		t.Fatal("Basic reached AI")
	}
	grantTestAI(t, s, g)
	request := Object{"text": "Giải thích trời xanh", "mode": "qa", "conversation_id": "context-one", "request_id": "request-one"}
	code, m = call(t, s, "POST", "/v1/assistant/text", token, request)
	requireStatus(t, 200, code, m)
	code, m = call(t, s, "POST", "/v1/assistant/text", token, request)
	requireStatus(t, 200, code, m)
	if m["replayed"] != true || requests.Load() != 1 {
		t.Fatal("idempotency failed")
	}
	code, m = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "different", "mode": "qa", "conversation_id": "context-one", "request_id": "request-one"})
	requireStatus(t, 409, code, m)
	request["request_id"] = "request-two"
	request["text"] = "Giải thích thêm"
	code, m = call(t, s, "POST", "/v1/assistant/text", token, request)
	requireStatus(t, 200, code, m)
	if len(sizes) != 2 || sizes[1] != 4 {
		t.Fatalf("context missing %v", sizes)
	}
	other := guest(t, s)
	grantTestAI(t, s, other)
	request["request_id"] = "other-request"
	code, m = call(t, s, "POST", "/v1/assistant/text", str(other, "access_token"), request)
	requireStatus(t, 200, code, m)
	if sizes[2] != 2 {
		t.Fatal("cross-account context leaked")
	}
	// Attach that device to the first account, as account linking does. The
	// same conversation label still cannot expose the first device's context.
	uid := str(g["user"].(map[string]any), "id")
	did := str(other["device"].(map[string]any), "id")
	s.db.Exec("UPDATE devices SET user_id=? WHERE id=?", uid, did)
	s.db.Exec("UPDATE sessions SET user_id=? WHERE device_id=?", uid, did)
	request["request_id"] = "other-device-request"
	code, m = call(t, s, "POST", "/v1/assistant/text", str(other, "access_token"), request)
	requireStatus(t, 200, code, m)
	if sizes[3] != 2 {
		t.Fatal("cross-device context leaked")
	}
	answer = `{"utterance":"Gọi điện","tool":"call","arguments":{"phone_number":"0901234567"}}`
	code, m = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "gọi điện", "mode": "tools", "conversation_id": "call-session", "request_id": "call-one"})
	requireStatus(t, 200, code, m)
	if m["confirmation_required"] != true {
		t.Fatal("call not confirmed")
	}
	confirmation := str(m, "confirmation_token")
	code, m = call(t, s, "POST", "/v1/assistant/text", str(other, "access_token"), Object{"text": "đồng ý", "mode": "tools", "conversation_id": "call-session", "request_id": "cross-device-confirm", "confirmation_token": confirmation})
	requireStatus(t, 409, code, m)
	code, m = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "đồng ý", "mode": "tools", "conversation_id": "call-session", "request_id": "call-two", "confirmation_token": confirmation})
	requireStatus(t, 200, code, m)
	directives := m["directives"].([]any)
	if str(directives[0].(map[string]any), "type") != "PhoneCall" {
		t.Fatal("confirmed tool missing")
	}
	code, m = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "đồng ý", "mode": "tools", "conversation_id": "call-session", "request_id": "call-three", "confirmation_token": confirmation})
	requireStatus(t, 409, code, m)
	answer = `{"tool":"delete_data","arguments":{}}`
	code, m = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "xóa dữ liệu", "mode": "tools", "request_id": "unsafe-tool"})
	requireStatus(t, 502, code, m)
}

func TestValidatedTNSkillToolsUseOwnedDeviceAdaptersAndConfirmation(t *testing.T) {
	for _, test := range []struct {
		tool, wantType string
		args           Object
	}{
		{"play_music", "PlayMusicApp", Object{"query": "Hoa Hải Đường"}},
		{"play_radio", "PlayRadioApp", Object{"query": "VOV Giao thông"}},
		{"play_video", "PlayVideoApp", Object{"query": "video bóng đá"}},
		{"play_tv", "PlayTVApp", Object{"query": "VTV1"}},
	} {
		directive, sensitive, err := validatedAITool(Object{"tool": test.tool, "arguments": test.args})
		if err != nil || sensitive || str(directive, "type") != test.wantType || str(directive["payload"].(map[string]any), "query") != str(test.args, "query") {
			t.Fatalf("%s did not map to the selected media adapter: %v %v", test.tool, directive, err)
		}
	}

	tomorrow := time.Now().AddDate(0, 0, 1)
	dateArgs := Object{"hour": 8, "minute": 15, "day": tomorrow.Day(), "month": int(tomorrow.Month()), "year": tomorrow.Year(), "repeat_type": "None"}
	for _, test := range []struct {
		tool, alertType string
		args            Object
	}{
		{"set_alarm", "Alarm", dateArgs},
		{"create_reminder", "Reminder", Object{"hour": dateArgs["hour"], "minute": dateArgs["minute"], "day": dateArgs["day"], "month": dateArgs["month"], "year": dateArgs["year"], "repeat_type": "None", "task": "đi họp"}},
	} {
		directive, sensitive, err := validatedAITool(Object{"tool": test.tool, "arguments": test.args})
		payload, _ := directive["payload"].(map[string]any)
		if err != nil || !sensitive || str(directive, "type") != "Alerts" || str(payload, "type") != test.alertType {
			t.Fatalf("%s must require user confirmation and use the Original Alerts adapter: %v %v", test.tool, directive, err)
		}
		if test.alertType == "Reminder" && str(payload, "task") != "đi họp" {
			t.Fatal("reminder content was not preserved")
		}
	}

	for _, invalid := range []Object{
		{"tool": "set_alarm", "arguments": Object{"hour": 24, "minute": 0}},
		{"tool": "set_alarm", "arguments": Object{"hour": 8, "minute": 0, "repeat_type": "EveryMinute"}},
		{"tool": "create_reminder", "arguments": Object{"hour": 8, "minute": 0, "task": ""}},
		{"tool": "dismiss_alarm", "arguments": Object{"search_mode": "all"}},
		{"tool": "dismiss_alarm", "arguments": Object{"search_mode": "time", "hour": 25, "minute": 0}},
		{"tool": "play_music", "arguments": Object{"query": strings.Repeat("x", 201)}},
	} {
		if _, _, err := validatedAITool(invalid); err == nil {
			t.Fatalf("invalid skill tool accepted: %v", invalid)
		}
	}
}

func TestAlarmListIsReadOnlyAndDismissalRequiresConfirmation(t *testing.T) {
	show, sensitive, err := validatedAITool(Object{"tool": "show_alarms", "arguments": Object{}})
	showPayload, _ := show["payload"].(map[string]any)
	if err != nil || sensitive || str(show, "type") != "Alerts" || str(showPayload, "type") != "ShowAlarms" {
		t.Fatalf("show alarms should only open the system alarm list: directive=%v sensitive=%t err=%v", show, sensitive, err)
	}

	for _, args := range []Object{
		{"search_mode": "next"},
		{"search_mode": "time", "hour": 6, "minute": 30},
	} {
		directive, sensitive, err := validatedAITool(Object{"tool": "dismiss_alarm", "arguments": args})
		payload, _ := directive["payload"].(map[string]any)
		if err != nil || !sensitive || str(directive, "type") != "Alerts" || str(payload, "type") != "DismissAlarm" {
			t.Fatalf("alarm dismissal must require explicit confirmation and target one mode: %v %v", directive, err)
		}
	}
}

func TestAlarmToolIsConfirmedOnceBeforeReturningAndroidAlertDirective(t *testing.T) {
	s := testServer(t)
	var requests atomic.Int32
	tomorrow := time.Now().AddDate(0, 0, 1)
	answer := jsonText(Object{"utterance": "Đặt báo thức lúc 8 giờ 15 ngày mai.", "tool": "set_alarm", "arguments": Object{"hour": 8, "minute": 15, "day": tomorrow.Day(), "month": int(tomorrow.Month()), "year": tomorrow.Year(), "repeat_type": "None"}})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		send(w, 200, Object{"choices": []Object{{"message": Object{"content": answer}}}})
	}))
	defer upstream.Close()
	configureTestAI(t, s, upstream.URL)
	g := guest(t, s)
	grantTestAI(t, s, g)
	token := str(g, "access_token")
	code, pending := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "Đặt báo thức lúc 8 giờ 15 ngày mai", "mode": "tools", "conversation_id": "alarm-session", "request_id": "alarm-request"})
	requireStatus(t, 200, code, pending)
	if pending["confirmation_required"] != true || str(pending, "confirmation_token") == "" || !strings.Contains(str(pending, "utterance"), "Xác nhận thao tác") {
		t.Fatalf("alarm action did not ask for explicit confirmation: %v", pending)
	}
	confirmation := str(pending, "confirmation_token")
	code, completed := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "Đồng ý", "mode": "tools", "conversation_id": "alarm-session", "request_id": "alarm-confirm", "confirmation_token": confirmation})
	requireStatus(t, 200, code, completed)
	directives, _ := completed["directives"].([]any)
	if len(directives) != 1 {
		t.Fatalf("confirmed alarm returned unexpected directives: %v", completed)
	}
	directive, _ := directives[0].(map[string]any)
	payload, _ := directive["payload"].(map[string]any)
	if str(directive, "type") != "Alerts" || str(payload, "type") != "Alarm" || integer(payload, "hour", -1) != 8 || integer(payload, "minute", -1) != 15 || requests.Load() != 1 {
		t.Fatalf("confirmed alarm did not return the bounded Original adapter request: %v", completed)
	}
	code, reused := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "Đồng ý", "mode": "tools", "conversation_id": "alarm-session", "request_id": "alarm-confirm-again", "confirmation_token": confirmation})
	requireStatus(t, 409, code, reused)
}

func TestAIFallbackNeverRetriesToolRequests(t *testing.T) {
	s := testServer(t)
	var first, second atomic.Int32
	a := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { first.Add(1); w.WriteHeader(503) }))
	defer a.Close()
	b := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		second.Add(1)
		send(w, 200, Object{"choices": []Object{{"message": Object{"content": "TN trả lời"}}}})
	}))
	defer b.Close()
	s.put("config", "ai-providers", "", Object{"providers": []AIProvider{{ID: "first", Kind: "custom", Model: "fixture", Endpoint: a.URL, Enabled: true, Priority: 0, MaxRequestsPerDay: 100, MaxOutputTokens: 1024}, {ID: "second", Kind: "custom", Model: "fixture", Endpoint: b.URL, Enabled: true, Priority: 1, MaxRequestsPerDay: 100, MaxOutputTokens: 1024}}})
	if _, e := s.aiAnswer(context.Background(), []aiMessage{{"user", "hello"}}, false); e != nil {
		t.Fatal(e)
	}
	if first.Load() != 1 || second.Load() != 1 {
		t.Fatal("QA fallback missing")
	}
	if _, e := s.aiAnswer(context.Background(), []aiMessage{{"user", "open app"}}, true); e == nil {
		t.Fatal("tools failure hidden")
	}
	if first.Load() != 2 || second.Load() != 1 {
		t.Fatal("tool request repeated through fallback")
	}
}
func TestTNSettingsValidationAndPrivateContext(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	code, m := call(t, s, "GET", "/v1/settings", token, nil)
	requireStatus(t, 200, code, m)
	if integer(m, "conversation_idle_timeout_ms", 0) != 10000 || str(m, "ai_mode") != "commands" {
		t.Fatal("wrong defaults")
	}
	code, m = call(t, s, "PUT", "/v1/settings", token, Object{"conversation_idle_timeout_ms": 15000, "command_end_silence_ms": 1500, "tv_package": "com.example.tv", "radio_package": "com.example.radio", "media_package": "com.example.media"})
	requireStatus(t, 200, code, m)
	code, m = call(t, s, "PUT", "/v1/settings", token, Object{"command_end_silence_ms": -1})
	requireStatus(t, 400, code, m)
	code, m = call(t, s, "PUT", "/v1/settings", token, Object{"ai_mode": "tools"})
	requireStatus(t, 403, code, m)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		send(w, 200, Object{"choices": []Object{{"message": Object{"content": "private answer"}}}})
	}))
	defer upstream.Close()
	configureTestAI(t, s, upstream.URL)
	grantTestAI(t, s, g)
	code, m = call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
	requireStatus(t, 200, code, m)
	code, m = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "private question", "mode": "qa", "request_id": "private-request"})
	requireStatus(t, 200, code, m)
	var n int
	s.db.QueryRow("SELECT count(*) FROM records WHERE kind='ai_conversation'").Scan(&n)
	if n != 0 {
		t.Fatal("private context retained")
	}
	var raw string
	s.db.QueryRow("SELECT data FROM records WHERE kind='assistant_request'").Scan(&raw)
	if strings.Contains(raw, "private answer") || strings.Contains(raw, "private question") {
		t.Fatal("private replay body retained")
	}
}
