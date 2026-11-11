package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"unicode/utf8"
)

func TestTNBasicPhoneParsesCompleteDigitCommandsWithoutGuessingContactsOrPartialNumbers(t *testing.T) {
	for _, test := range []struct{ text, number string }{
		{"gọi số 5551234567", "5551234567"},
		{"GOI DIEN CHO SO 555 123 4567", "5551234567"},
		{"  gọi\tđến số (555) 123-4567!  ", "5551234567"},
		{"gọi tới +84901234567", "+84901234567"},
		{"gọi số cộng tám bốn chín không một hai ba bốn năm sáu bảy", "+84901234567"},
		{"gọi số không chín không một hai ba bốn năm sáu bảy", "0901234567"},
		{"gọi cho số 090.123.4567", "0901234567"},
	} {
		directive := basicPhoneDirective(test.text)
		if str(directive, "type") != "PhoneCall" || str(directive["payload"].(map[string]any), "phone_number") != test.number {
			t.Fatalf("Unexpected phone interpretation for %q", test.text)
		}
	}
	for _, text := range []string{"gọi", "gọi mẹ", "gọi xe", "đừng gọi số 5551234567", "không gọi 5551234567",
		"có nên gọi số 5551234567 không", "gọi số 5551234567 và mở bản đồ", "gọi số 5551234567 hủy",
		"gọi số tel:5551234567", "gọi số *123#", "gọi số 555;1234567", "gọi số ++84901234567", "gọi số -5551234567",
		"gọi số 12", "gọi số 1234567890123456", "gọi số chín trăm hai mươi", "gọi số 555abc1234567"} {
		if basicPhoneDirective(text) != nil {
			t.Fatalf("Unsafe/ambiguous phone command was interpreted: %q", text)
		}
	}
}

func TestTNBasicPhoneResolvesOnlyExplicitContactPhrases(t *testing.T) {
	for _, test := range []struct{ text, name string }{
		{"gọi cho anh Nam", "anh Nam"},
		{"GỌI ĐIỆN CHO mẹ", "mẹ"},
		{"gọi cho Nam", "Nam"},
	} {
		directive := basicPhoneDirective(test.text)
		payload, _ := directive["payload"].(map[string]any)
		if str(directive, "type") != "PhoneCall" || str(payload, "contact_name") != test.name || payload["phone_number"] != nil {
			t.Fatalf("Expected an on-device contact target for %q; got %#v", test.text, directive)
		}
	}
	for _, text := range []string{
		"gọi cho số chín trăm", "gọi cho anh Nam và mở bản đồ", "gọi cho anh 2",
		"gọi cho anh Nam, mở bản đồ", "gọi xe", "gọi cho", "gọi anh Nam",
	} {
		if basicPhoneDirective(text) != nil {
			t.Fatalf("Ambiguous/non-contact imperative was interpreted: %q", text)
		}
	}
}

func TestIndependentBasicContactCallRequiresBoundSingleUseConsent(t *testing.T) {
	s := independentTestServer(t)
	account := guest(t, s)
	token := str(account, "access_token")
	var requests atomic.Int32
	tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
		requests.Add(1)
		return tnAITestResponse(503, `{}`), nil
	})
	input := Object{"text": "gọi cho anh Nam", "request_id": "basic-contact-offer", "conversation_id": "basic-contact-context"}
	status, offer := call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, status, offer)
	preview := offer["confirmation"].(map[string]any)
	if offer["confirmation_required"] != true || str(preview, "mode") != "commands" || str(preview, "summary") != "Gọi cho anh Nam?" {
		t.Fatal("Basic contact call lacks contact-specific commands-only consent")
	}
	for _, directive := range objectDirectives(offer) {
		if str(directive, "type") != "SpeechSynthesizer" {
			t.Fatal("Contact call directive escaped before explicit consent")
		}
	}
	approved := Object{"text": "Xác nhận", "mode": "commands", "request_id": "basic-contact-approve",
		"conversation_id": "basic-contact-context", "confirmation_token": str(offer, "confirmation_token")}
	status, result := call(t, s, "POST", "/v1/assistant/text", token, approved)
	requireStatus(t, 200, status, result)
	directives := objectDirectives(result)
	if len(directives) != 1 || str(directives[0], "type") != "PhoneCall" ||
		str(directives[0]["payload"].(map[string]any), "contact_name") != "anh Nam" || str(directives[0], "action_id") == "" {
		t.Fatal("Approved Basic contact call did not retain its exact on-device lookup target and action ID")
	}
	if requests.Load() != 0 {
		t.Fatal("Basic contact lookup was sent to an upstream provider")
	}
}

func TestIndependentBasicPhoneRequiresBoundSingleUseConsentWithoutAIOrPremium(t *testing.T) {
	s := independentTestServer(t)
	account := guest(t, s)
	token := str(account, "access_token")
	if s.aiPremium(str(account["user"].(map[string]any), "id")) {
		t.Fatal("Basic fixture unexpectedly has AI rights")
	}
	var requests atomic.Int32
	tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
		requests.Add(1)
		return tnAITestResponse(503, `{}`), nil
	})
	input := Object{"text": "gọi số không chín không một hai ba bốn năm sáu bảy", "request_id": "basic-phone-offer", "conversation_id": "basic-phone-context"}
	status, offer := call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, status, offer)
	preview := offer["confirmation"].(map[string]any)
	if offer["confirmation_required"] != true || str(preview, "mode") != "commands" || str(preview, "summary") != "Gọi số 0901234567?" {
		t.Fatal("Basic phone command lacks canonical commands-only consent")
	}
	for _, directive := range objectDirectives(offer) {
		if str(directive, "type") != "SpeechSynthesizer" {
			t.Fatal("Call directive escaped before explicit consent")
		}
	}
	status, replay := call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, status, replay)
	if replay["replayed"] != true || str(replay, "confirmation_token") != str(offer, "confirmation_token") {
		t.Fatal("Offer replay minted a new authorization")
	}
	other := guest(t, s)
	approved := Object{"text": "Xác nhận", "mode": "commands", "request_id": "basic-phone-approve", "conversation_id": "basic-phone-context", "confirmation_token": str(offer, "confirmation_token")}
	status, rejected := call(t, s, "POST", "/v1/assistant/text", str(other, "access_token"), approved)
	requireStatus(t, 409, status, rejected)
	status, out := call(t, s, "POST", "/v1/assistant/text", token, approved)
	requireStatus(t, 200, status, out)
	directives := objectDirectives(out)
	if len(directives) != 1 || str(directives[0], "type") != "PhoneCall" || str(directives[0]["payload"].(map[string]any), "phone_number") != "0901234567" || str(directives[0], "action_id") == "" {
		t.Fatal("Approved Basic phone command did not retain its exact number and issued action ID")
	}
	status, out = call(t, s, "POST", "/v1/assistant/text", token, approved)
	requireStatus(t, 200, status, out)
	if out["replayed"] != true {
		t.Fatal("Approved request replay could execute another call")
	}
	approved["request_id"] = "basic-phone-again"
	status, out = call(t, s, "POST", "/v1/assistant/text", token, approved)
	requireStatus(t, 409, status, out)
	if requests.Load() != 0 {
		t.Fatal("Basic phone command contacted an upstream provider")
	}
	status, out = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "gọi số 5551234567", "mode": "tools", "request_id": "basic-forged-ai"})
	requireStatus(t, 403, status, out)
}

func TestIndependentBasicPhoneRejectsAnotherDeviceExpiredOrRetiredConsent(t *testing.T) {
	for _, scenario := range []string{"device", "expired", "generation"} {
		t.Run(scenario, func(t *testing.T) {
			s := independentTestServer(t)
			account := guest(t, s)
			token := str(account, "access_token")
			status, offer := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "gọi số 5551234567", "request_id": "local-offer", "conversation_id": "local-context"})
			requireStatus(t, 200, status, offer)
			if scenario == "device" {
				other := guest(t, s)
				uid := str(account["user"].(map[string]any), "id")
				did := str(other["device"].(map[string]any), "id")
				s.db.Exec("UPDATE devices SET user_id=? WHERE id=?", uid, did)
				s.db.Exec("UPDATE sessions SET user_id=? WHERE device_id=?", uid, did)
				token = str(other, "access_token")
			} else if scenario == "expired" {
				s.db.Exec("UPDATE records SET data=json_set(data,'$.expires_at',?) WHERE kind='ai_confirmation'", time.Now().UTC().Add(-time.Minute).Format(time.RFC3339Nano))
			} else {
				cfg := runtimeObject(t, s)
				cfg["generation"] = integer(cfg, "generation", 1) + 1
				if err := s.put("config", "runtime", "", cfg); err != nil {
					t.Fatal(err)
				}
			}
			status, rejected := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "Xác nhận", "mode": "commands", "request_id": "local-approved", "conversation_id": "local-context", "confirmation_token": str(offer, "confirmation_token")})
			requireStatus(t, 409, status, rejected)
		})
	}
}

func TestIndependentAIConsentCannotBeDowngradedToBasicAndExpiredLocalProposalsArePurged(t *testing.T) {
	s := independentTestServer(t)
	account := guest(t, s)
	uid := str(account["user"].(map[string]any), "id")
	did := str(account["device"].(map[string]any), "id")
	scope := hash(uid + "\x00" + did)
	token := "fixture-ai-confirmation"
	if err := s.put("ai_confirmation", hash(scope+":"+token), uid, Object{"conversation_id": "ai-only", "mode": "tools", "origin": "ai", "expires_at": time.Now().UTC().Add(time.Minute).Format(time.RFC3339Nano)}); err != nil {
		t.Fatal(err)
	}
	status, rejected := call(t, s, "POST", "/v1/assistant/text", str(account, "access_token"), Object{"text": "Xác nhận", "mode": "commands", "request_id": "forged-local-approval", "conversation_id": "ai-only", "confirmation_token": token})
	requireStatus(t, 400, status, rejected)
	if err := s.put("ai_confirmation", "expired-local-proposal", uid, Object{"mode": "commands", "origin": "tn-command", "expires_at": time.Now().UTC().Add(-time.Minute).Format(time.RFC3339Nano)}); err != nil {
		t.Fatal(err)
	}
	status, offer := call(t, s, "POST", "/v1/assistant/text", str(account, "access_token"), Object{"text": "gọi số 5551234567", "request_id": "purge-local-offer"})
	requireStatus(t, 200, status, offer)
	if _, err := s.record("ai_confirmation", "expired-local-proposal"); err == nil {
		t.Fatal("Expired local proposal survived without AI-context cleanup")
	}
	if _, err := s.record("ai_confirmation", hash(scope+":"+token)); err != nil {
		t.Fatal("Unexpired AI consent was removed during local cleanup")
	}
}

func TestTNRouterPreservesNavigationTextAcrossVietnameseInputForms(t *testing.T) {
	server := &Server{}
	for _, test := range []struct{ text, query string }{
		{"Chỉ đường đến Bến Thành", "Bến Thành"},
		{"chi duong den Ben Thanh", "Ben Thanh"},
		{"CHỈ ĐƯỜNG ĐẾN Đà Nẵng", "Đà Nẵng"},
		{"  dẫn   đường\tđến  Đà Nẵng  ", "Đà Nẵng"},
		{"chi duong den A", "A"},
	} {
		out, err := server.tnCommandAssistant(test.text)
		if err != nil {
			t.Fatalf("%q: %v", test.text, err)
		}
		directives := objectDirectives(out)
		if len(directives) == 0 || str(directives[0], "type") != "MapDirection" {
			t.Errorf("%q: missing navigation directive", test.text)
			continue
		}
		payload := directives[0]["payload"].(map[string]any)
		if got := str(payload, "query"); got != test.query || !utf8.ValidString(got) {
			t.Errorf("%q: got destination %q, want %q", test.text, got, test.query)
		}
	}
}

func TestTNRouterRecognizesApplicationAliasesWithAndWithoutAccents(t *testing.T) {
	server := &Server{}
	for _, test := range []struct{ text, app string }{
		{"Mở bản đồ", "com.google.android.apps.maps"},
		{"mo ban do", "com.google.android.apps.maps"},
		{"MỞ GOOGLE MAPS", "com.google.android.apps.maps"},
		{"mở trình duyệt chrome", "com.android.chrome"},
		{"mo trinh duyet chrome", "com.android.chrome"},
	} {
		out, err := server.tnCommandAssistant(test.text)
		if err != nil {
			t.Fatalf("%q: %v", test.text, err)
		}
		directives := objectDirectives(out)
		if len(directives) == 0 || str(directives[0], "type") != "OpenApp" {
			t.Errorf("%q: missing application directive", test.text)
			continue
		}
		if got := str(directives[0]["payload"].(map[string]any), "package_name"); got != test.app {
			t.Errorf("%q: got %q, want %q", test.text, got, test.app)
		}
	}
}

func TestIndependentNavigationRetainsDestinationThroughAuthenticatedAPI(t *testing.T) {
	server := independentTestServer(t)
	account := guest(t, server)
	for index, test := range []struct{ text, query string }{
		{"chi duong den A", "A"},
		{"Chỉ đường đến Đà Nẵng", "Đà Nẵng"},
		{"dẫn   đường\tđến Bến Thành", "Bến Thành"},
	} {
		code, response := call(t, server, "POST", "/v1/assistant/text", str(account, "access_token"), Object{
			"text": test.text, "mode": "commands", "request_id": "routing-" + string(rune('a'+index)),
		})
		requireStatus(t, 200, code, response)
		directives := objectDirectives(response)
		if len(directives) == 0 || str(directives[0], "type") != "MapDirection" {
			t.Fatalf("%q did not reach navigation through the authenticated API", test.text)
		}
		if got := str(directives[0]["payload"].(map[string]any), "query"); got != test.query {
			t.Errorf("%q: destination %q, want %q", test.text, got, test.query)
		}
	}
}

func TestIndependentUnknownCommandIsNotMistakenForAHandledIntent(t *testing.T) {
	server := &Server{}
	unknown, err := server.tnCommandAssistant("Giải thích vì sao bầu trời có màu xanh")
	if err != nil {
		t.Fatal(err)
	}
	if commandHandled(unknown) {
		t.Fatal("Unknown TN reply swallowed the AI fallback")
	}
	for _, text := range []string{"mấy giờ rồi", "chỉ đường đến Đà Nẵng", "mở bản đồ"} {
		out, err := server.tnCommandAssistant(text)
		if err != nil || !commandHandled(out) {
			t.Fatalf("Known command %q must stay local: %v", text, err)
		}
	}
}

func TestIndependentQAReachesMissingAIConfigurationInsteadOfFakeCommandSuccess(t *testing.T) {
	server := independentTestServer(t)
	account := guest(t, server)
	grantTestAI(t, server, account)
	token := str(account, "access_token")
	code, response := call(t, server, "POST", "/v1/assistant/text", token, Object{
		"text": "Giải thích vì sao bầu trời có màu xanh", "mode": "qa", "request_id": "qa-unconfigured",
	})
	requireStatus(t, 503, code, response)
	code, response = call(t, server, "POST", "/v1/assistant/text", token, Object{
		"text": "Giải thích vì sao bầu trời có màu xanh", "mode": "commands", "request_id": "commands-unconfigured",
	})
	requireStatus(t, 200, code, response)
	if len(objectDirectives(response)) != 1 || str(objectDirectives(response)[0], "type") != "SpeechSynthesizer" {
		t.Fatal("Commands-only mode must retain its explicit unsupported-command speech")
	}
}

func TestBasicVolumeCommandsUseOriginalDirectiveAndRejectUnclearSpeech(t *testing.T) {
	for _, test := range []struct {
		text, command string
		percentage    int
	}{
		{"Tăng âm lượng", "increase_volume", -1},
		{"Giảm âm lượng", "decrease_volume", -1},
		{"Tăng âm lượng tối đa", "adjust_volume", 100},
		{"Âm lượng 50%", "adjust_volume", 50},
		{"Đặt âm lượng 35 phần trăm", "adjust_volume", 35},
		{"Chỉnh âm lượng về 0%", "adjust_volume", 0},
		{"Tắt âm lượng", "adjust_volume", 0},
		{"  TĂNG   ÂM LƯỢNG! ", "increase_volume", -1},
	} {
		out := basicVolumeCommand(test.text)
		if out == nil || str(out, "utterance") != "" {
			t.Fatalf("missing command or fake completed speech for %q", test.text)
		}
		payload := objectDirectives(out)[0]["payload"].(map[string]any)
		if str(payload, "command") != test.command || (test.percentage >= 0 && (integer(payload, "adjust_percentage", -2) != test.percentage || integer(payload, "adjust_level", 0) != -1)) {
			t.Fatalf("invalid Original volume payload for %q: %v", test.text, payload)
		}
	}
	for _, text := range []string{"Đừng tăng âm lượng", "Không tăng âm lượng", "Cách tăng âm lượng", "Tăng âm lượng là gì?", "Tăng âm lượng và mở bản đồ", "Tăng âm lượng nếu có cảnh báo", "Âm lượng 101%", "Âm lượng -1%", "Âm lượng 5", "Mở âm lượng", "âm lượng năm mươi", "Hãy xóa dữ liệu"} {
		if out := basicVolumeCommand(text); out != nil {
			t.Fatalf("unclear or unsupported text became an action: %q", text)
		}
	}
}

func TestBasicVolumeRoutingAvoidsUpstreamDeviceProfileButNeverClaimsExecution(t *testing.T) {
	s := testServer(t)
	var calls atomic.Int32
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		send(w, 200, Object{"utterance": "fixture declined", "directives": []Object{}})
	}))
	defer provider.Close()
	s.saveProviders(Object{"nlu": Object{"mode": "generic", "url": provider.URL}})
	account := guest(t, s)
	token := str(account, "access_token")
	input := Object{"text": "Giảm âm lượng", "request_id": "basic-volume", "mode": "commands"}
	code, response := call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, response)
	if str(response, "source") != "local-command" || str(response, "utterance") != "" || len(response["directives"].([]any)) != 1 || calls.Load() != 0 {
		t.Fatal("local volume intent did not produce only the real adapter request")
	}
	code, response = call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, response)
	if response["replayed"] != true || calls.Load() != 0 {
		t.Fatal("duplicate volume request was sent to provider")
	}
	code, response = call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "Đừng giảm âm lượng", "request_id": "negative-volume", "mode": "commands"})
	requireStatus(t, 200, code, response)
	if calls.Load() != 1 || len(response["directives"].([]any)) != 0 {
		t.Fatal("negative volume speech became a device action")
	}
}

func TestBasicMediaImperativesPreserveOriginalControlMeaning(t *testing.T) {
	for _, test := range []struct{ text, command string }{
		{"Bài tiếp theo", "music_control_next"}, {"  CHUYỂN   BÀI! ", "music_control_next"},
		{"chuyen bai tiep theo", "music_control_next"}, {"Quay lại bài trước", "music_control_prev"},
		{"Phát tiếp nhạc", "music_control_resume"}, {"Tiếp tục nghe nhạc", "music_control_resume"},
		{"Phát lại bài này", "music_control_replay"}, {"Nghe lại bài này", "music_control_replay"},
		{"Tạm dừng", "music_control_stop"}, {"Tắt nhạc", "music_control_stop"},
	} {
		out := basicMediaCommand(test.text)
		if out == nil || str(out, "utterance") != "" {
			t.Fatalf("missing directive or unconfirmed completion speech for %q", test.text)
		}
		directive := objectDirectives(out)[0]
		if str(directive, "type") != "PlayerMP3" || str(directive["payload"].(map[string]any), "command") != test.command {
			t.Fatalf("wrong transport for %q: %v", test.text, directive)
		}
	}
	for _, text := range []string{"Đừng chuyển bài", "Không tắt nhạc", "Tạm dừng là gì", "Bài tiếp theo?", "Cách phát tiếp nhạc", "Tắt nhạc và mở bản đồ", "Phát bài Nắng ấm xa dần", "Thích bài này", "Tắt", "Phát", "Nhạc", "Chuyển bài nếu đang phát"} {
		if basicMediaCommand(text) != nil {
			t.Fatalf("ambiguous or provider request executed as transport: %q", text)
		}
	}
}

func TestBasicYouTubeCommandsRouteSearchAndHTTPSLinksWithoutGuessing(t *testing.T) {
	for _, test := range []struct{ text, key, want string }{
		{"Mở YouTube", "direct_link", "https://www.youtube.com/"},
		{"Tìm trên YouTube nhạc Trịnh", "query", "nhạc Trịnh"},
		{"Tìm video trên YouTube nhạc Trịnh", "query", "nhạc Trịnh"},
		{"Tìm video nhạc Trịnh trên YouTube", "query", "nhạc Trịnh"},
		{"Mở video Vũ trên YouTube", "query", "Vũ"},
		{"Mở link https://example.com/video?id=1&from=assistant", "direct_link", "https://example.com/video?id=1&from=assistant"},
		{"Mở https://youtu.be/dQw4w9WgXcQ?si=tn", "direct_link", "https://youtu.be/dQw4w9WgXcQ?si=tn"},
		{"Mở trang https://example.com/watch?v=1", "direct_link", "https://example.com/watch?v=1"},
		{"Truy cập https://example.com/a?x=1&y=2", "direct_link", "https://example.com/a?x=1&y=2"},
	} {
		response := basicYouTubeCommand(test.text)
		if response == nil || str(response, "source") != "local-command" || str(response, "utterance") != "" {
			t.Fatalf("explicit YouTube command was not routed locally: %q", test.text)
		}
		directives := objectDirectives(response)
		if len(directives) != 1 || str(directives[0], "type") != "YouTube" {
			t.Fatalf("wrong directive for %q: %v", test.text, directives)
		}
		payload, ok := directives[0]["payload"].(map[string]any)
		if !ok || str(payload, test.key) != test.want || str(payload, "action_id") != "youtube" {
			t.Fatalf("wrong YouTube payload for %q: %v", test.text, directives[0])
		}
		if test.key == "query" && !payload["youtube_search"].(bool) {
			t.Fatalf("search fallback was not selected for %q", test.text)
		}
	}
	for _, text := range []string{
		"Mở link http://example.com/video", "Mở link https://user@example.com/video",
		"Tìm video nhạc Trịnh", "Mở video Vũ", "Mở YouTube và gọi cho Nam",
	} {
		if basicYouTubeCommand(text) != nil {
			t.Fatalf("ambiguous or unsafe command was routed as YouTube: %q", text)
		}
	}
}

func TestBasicYouTubeSearchFlowsThroughAssistantTextWithoutAIProvider(t *testing.T) {
	s := independentTestServer(t)
	account := guest(t, s)
	code, response := call(t, s, "POST", "/v1/assistant/text", str(account, "access_token"), Object{
		"text": "Tìm video trên YouTube nhạc Trịnh", "request_id": "basic-youtube-search", "mode": "commands",
	})
	requireStatus(t, 200, code, response)
	directives := objectDirectives(response)
	if len(directives) != 1 || str(directives[0], "type") != "YouTube" ||
		str(directives[0]["payload"].(map[string]any), "query") != "nhạc Trịnh" || str(response, "utterance") != "" {
		t.Fatalf("assistant text did not return the deterministic YouTube search: %v", response)
	}
}

func TestIndependentMediaActionsHaveOwnedReceiptsAndDoNotClaimPlayback(t *testing.T) {
	s := independentTestServer(t)
	account := guest(t, s)
	foreign := guest(t, s)
	token := str(account, "access_token")
	input := Object{"text": "Bài tiếp theo", "request_id": "basic-media-next", "mode": "commands"}
	code, response := call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, response)
	directives := objectDirectives(response)
	if len(directives) != 1 || str(directives[0], "type") != "PlayerMP3" || str(response, "utterance") != "" {
		t.Fatal("transport was not emitted as an unconfirmed device request")
	}
	result := Object{"request_id": str(response, "request_id"), "action_id": str(directives[0], "action_id"), "result": "failed",
		"details": Object{"reason": "media_result_unconfirmed", "transport_dispatched": true}}
	code, ack := call(t, s, "POST", "/v1/device/actions/results", str(foreign, "access_token"), result)
	// Do not disclose another account's action existence.
	requireStatus(t, 404, code, ack)
	code, ack = call(t, s, "POST", "/v1/device/actions/results", token, result)
	requireStatus(t, 200, code, ack)
	if ack["accepted"] != true || ack["replayed"] != false {
		t.Fatal("owner's real failure could not be recorded")
	}
	code, ack = call(t, s, "POST", "/v1/device/actions/results", token, result)
	requireStatus(t, 200, code, ack)
	if ack["replayed"] != true {
		t.Fatal("duplicate action result was not deduplicated")
	}
	code, response = call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, response)
	if response["replayed"] != true {
		t.Fatal("duplicate transport request could execute again")
	}
}

func TestIndependentAndroidOmittedModeUsesAccountPreferenceAndKeepsFollowupContext(t *testing.T) {
	s := independentTestServer(t)
	provider := tnAIFreeProvider(t, s)
	if err := s.put("config", "ai-providers", "", Object{"providers": []AIProvider{provider}}); err != nil {
		t.Fatal(err)
	}
	var sizes []int
	tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
		var payload Object
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		if r.URL.String() != provider.Endpoint || payload["tools"] != nil {
			t.Fatal("request bypassed the independent free-only generation path")
		}
		contents, _ := payload["contents"].([]any)
		sizes = append(sizes, len(contents))
		return tnAITestResponse(200, `{"candidates":[{"content":{"parts":[{"text":"Đây là câu trả lời kiểm thử TN."}]}}]}`), nil
	})
	basic, premium := guest(t, s), guest(t, s)
	input := Object{"text": "Giải thích vì sao bầu trời màu xanh", "request_id": "android-basic", "conversation_id": "android-shared-context"}
	code, out := call(t, s, "POST", "/v1/assistant/text", str(basic, "access_token"), input)
	requireStatus(t, 200, code, out)
	if len(sizes) != 0 || str(out, "source") != "tn_command_router_fallback" {
		t.Fatal("Basic without mode reached AI")
	}
	grantTestAI(t, s, premium)
	token := str(premium, "access_token")
	for index, request := range []string{"android-first", "android-followup"} {
		input["request_id"] = request
		code, out = call(t, s, "POST", "/v1/assistant/text", token, input)
		requireStatus(t, 200, code, out)
		if str(out, "source") != "ai" || str(out, "conversation_id") != str(input, "conversation_id") || sizes[index] != 1+2*index {
			t.Fatalf("Premium default QA lost follow-up context: sizes=%v", sizes)
		}
	}
	code, out = call(t, s, "PUT", "/v1/settings", token, Object{"ai_mode": "commands"})
	requireStatus(t, 200, code, out)
	input["request_id"] = "android-commands-preference"
	code, out = call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, out)
	if len(sizes) != 2 || str(out, "source") != "tn_command_router_fallback" {
		t.Fatal("Android omitted-mode request overrode the explicit commands preference")
	}
	code, out = call(t, s, "PUT", "/v1/settings", token, Object{"ai_mode": "qa"})
	requireStatus(t, 200, code, out)
	input["request_id"] = "android-return-to-qa"
	code, out = call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, out)
	if str(out, "source") != "ai" || len(sizes) != 3 || sizes[2] != 5 {
		t.Fatal("server-side QA preference was ignored")
	}
	// An unchanged context label on another account never inherits this user's messages.
	input["request_id"] = "android-admin-default"
	code, out = call(t, s, "POST", "/v1/assistant/text", adminToken(t, s), input)
	requireStatus(t, 200, code, out)
	if str(out, "source") != "ai" || len(sizes) != 4 || sizes[3] != 1 {
		t.Fatal("Admin default QA missing or cross-account context leaked")
	}
}

func TestAIToolNavigationProducesTheAllowedAndroidMapUrlWithoutQueryInjection(t *testing.T) {
	query := "Bến Thành & destination=https://untrusted.invalid/?x=1"
	directive, sensitive, err := validatedAITool(Object{"tool": "navigate", "arguments": Object{"query": query}})
	if err != nil || sensitive || str(directive, "type") != "MapDirection" {
		t.Fatalf("navigation tool validation failed: %v", err)
	}
	payload := directive["payload"].(map[string]any)
	target, err := url.Parse(str(payload, "url"))
	if err != nil || target.Scheme != "https" || target.Host != "www.google.com" || target.Path != "/maps/dir/" {
		t.Fatal("AI navigation lacks the HTTPS Google Maps URL required by both active Android executors")
	}
	values := target.Query()
	if values.Get("api") != "1" || len(values["destination"]) != 1 || values.Get("destination") != query || str(payload, "query") != query {
		t.Fatal("navigation destination was altered or became URL parameters")
	}
}

func TestIndependentConfirmationPreviewIsCanonicalAndRetiredRuntimeCannotRedeemIt(t *testing.T) {
	s := independentTestServer(t)
	provider := tnAIFreeProvider(t, s)
	if err := s.put("config", "ai-providers", "", Object{"providers": []AIProvider{provider}}); err != nil {
		t.Fatal(err)
	}
	tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
		return tnAITestResponse(200, jsonText(Object{"candidates": []Object{{"content": Object{"parts": []Object{{"text": jsonText(Object{
			"utterance": "UNTRUSTED_MODEL_CLAIM", "tool": "call", "arguments": Object{"phone_number": "0900000000"},
		})}}}}}})), nil
	})
	account := guest(t, s)
	grantTestAI(t, s, account)
	token := str(account, "access_token")
	code, out := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "gọi số kiểm thử", "mode": "tools", "request_id": "confirm-preview", "conversation_id": "confirm-context"})
	requireStatus(t, 200, code, out)
	preview, _ := out["confirmation"].(map[string]any)
	if out["confirmation_required"] != true || str(preview, "type") != "PhoneCall" || str(preview, "summary") != "Gọi số 0900000000?" ||
		strings.Contains(jsonText(out), "UNTRUSTED_MODEL_CLAIM") || integer(preview, "expires_in_ms", 0) != 120000 {
		t.Fatal("consent preview was not derived from the immutable validated action")
	}
	for _, directive := range objectDirectives(out) {
		if str(directive, "type") != "SpeechSynthesizer" {
			t.Fatal("sensitive directive escaped before consent")
		}
	}
	cfg := runtimeObject(t, s)
	cfg["generation"] = integer(cfg, "generation", 1) + 1
	if err := s.put("config", "runtime", "", cfg); err != nil {
		t.Fatal(err)
	}
	code, rejected := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "Xác nhận", "mode": "tools", "request_id": "confirm-retired", "conversation_id": "confirm-context", "confirmation_token": str(out, "confirmation_token")})
	requireStatus(t, 409, code, rejected)
	if str(rejected["error"].(map[string]any), "code") != "CONFIRMATION_RETIRED" {
		t.Fatal("old runtime consent remained executable")
	}
}

func TestIndependentConfirmationConcurrentClicksEmitOneSensitiveDirective(t *testing.T) {
	s := independentTestServer(t)
	provider := tnAIFreeProvider(t, s)
	if err := s.put("config", "ai-providers", "", Object{"providers": []AIProvider{provider}}); err != nil {
		t.Fatal(err)
	}
	tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
		return tnAITestResponse(200, jsonText(Object{"candidates": []Object{{"content": Object{"parts": []Object{{"text": jsonText(Object{
			"utterance": "test", "tool": "call", "arguments": Object{"phone_number": "0900000000"},
		})}}}}}})), nil
	})
	account := guest(t, s)
	grantTestAI(t, s, account)
	token := str(account, "access_token")
	code, pending := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "gọi số kiểm thử", "mode": "tools", "request_id": "concurrent-offer", "conversation_id": "concurrent-context"})
	requireStatus(t, 200, code, pending)
	type result struct {
		code int
		body Object
	}
	results := make(chan result, 2)
	var start sync.WaitGroup
	start.Add(1)
	for _, request := range []string{"concurrent-confirm-a", "concurrent-confirm-b"} {
		go func(request string) {
			start.Wait()
			status, body := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "Xác nhận", "mode": "tools", "request_id": request,
				"conversation_id": "concurrent-context", "confirmation_token": str(pending, "confirmation_token")})
			results <- result{status, body}
		}(request)
	}
	start.Done()
	executions := 0
	for index := 0; index < 2; index++ {
		out := <-results
		if out.code == 200 {
			for _, directive := range objectDirectives(out.body) {
				if str(directive, "type") == "PhoneCall" {
					executions++
				}
			}
		} else if out.code != 409 {
			t.Fatalf("unexpected concurrent result: %d", out.code)
		}
	}
	if executions != 1 {
		t.Fatalf("concurrent consent emitted %d sensitive directives", executions)
	}
}
