package main

import (
	"fmt"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestIndependentBasicAlertsUseBoundConsentWithoutAI(t *testing.T) {
	s := independentTestServer(t)
	account := guest(t, s)
	token := str(account, "access_token")
	other := guest(t, s)
	var upstream atomic.Int32
	tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
		upstream.Add(1)
		return tnAITestResponse(503, `{}`), nil
	})
	for index, test := range []struct{ text, kind, summary string }{
		{"đặt hẹn giờ năm phút", "Timer", "5 phút"},
		{"đặt báo thức lúc bảy giờ sáng", "Alarm", "07:00"},
		{"nhắc tôi kiểm tra lịch lúc bảy giờ sáng", "Reminder", "kiểm tra lịch"},
		{"tắt báo thức tiếp theo", "DismissAlarm", "tắt báo thức"},
	} {
		t.Run(test.kind, func(t *testing.T) {
			conversation := fmt.Sprintf("basic-alert-%d", index)
			code, offer := call(t, s, "POST", "/v1/assistant/text", token, Object{
				"text": test.text, "request_id": conversation + "-offer", "conversation_id": conversation,
				"device_id": "alert-owner", "timezone": "Asia/Ho_Chi_Minh",
				"client_capabilities": Object{"basic_alerts": true},
			})
			requireStatus(t, 200, code, offer)
			if offer["confirmation_required"] != true {
				t.Fatalf("Basic %s was not routed to confirmation", test.kind)
			}
			preview, _ := offer["confirmation"].(map[string]any)
			if str(preview, "mode") != "commands" || !strings.Contains(str(preview, "summary"), test.summary) {
				t.Fatalf("Confirmation does not describe the exact Basic operation: %#v", preview)
			}
			for _, directive := range objectDirectives(offer) {
				if str(directive, "type") != "SpeechSynthesizer" {
					t.Fatal("An unconfirmed mutation escaped")
				}
			}
			code, denied := call(t, s, "POST", "/v1/assistant/text", str(other, "access_token"), Object{
				"text": "Xác nhận", "mode": "commands", "request_id": conversation + "-other-account",
				"conversation_id": conversation, "device_id": "other-device", "confirmation_token": str(offer, "confirmation_token"),
			})
			requireStatus(t, 409, code, denied)
			approval := Object{"text": "Xác nhận", "mode": "commands", "request_id": conversation + "-approve",
				"conversation_id": conversation, "device_id": "alert-owner", "confirmation_token": str(offer, "confirmation_token")}
			code, approved := call(t, s, "POST", "/v1/assistant/text", token, approval)
			requireStatus(t, 200, code, approved)
			directives := objectDirectives(approved)
			if len(directives) != 1 || str(directives[0], "type") != "Alerts" || str(directives[0], "action_id") == "" {
				t.Fatal("Approved Basic alert did not reach the owned action channel")
			}
			payload, _ := directives[0]["payload"].(map[string]any)
			if str(payload, "type") != test.kind {
				t.Fatal("Confirmation changed the alert type")
			}
			approval["request_id"] = conversation + "-replay"
			code, replay := call(t, s, "POST", "/v1/assistant/text", token, approval)
			requireStatus(t, 409, code, replay)
		})
	}
	if upstream.Load() != 0 {
		t.Fatal("Local Basic alert requests consumed an upstream provider")
	}
}

func TestBasicAlertDurationAndCalendarBoundaries(t *testing.T) {
	for _, test := range []struct {
		text    string
		seconds int
	}{
		{"năm phút", 300}, {"nam phut", 300}, {"một giờ hai mươi lăm phút", 5100},
		{"mot gio hai muoi lam phut", 5100}, {"1 giờ 0 phút 30 giây", 3630},
		{"24 giờ", 86400}, {"nửa giờ", 1800}, {"nửa phút", 30}, {"trong 15 giây", 15},
	} {
		if seconds, ok := basicTimerSeconds(test.text); !ok || seconds != test.seconds {
			t.Errorf("%q: got %d/%v, want%d", test.text, seconds, ok, test.seconds)
		}
	}
	for _, text := range []string{"0 giây", "-1 phút", "25 giờ", "86401 giây", "năm phút và mở nhạc", "một hai phút", "1 phút 1 giờ", "1 phút 2 phút", "1.5 phút", "trong sau 5 phút"} {
		if _, ok := basicTimerSeconds(text); ok {
			t.Errorf("Ambiguous timer was accepted: %q", text)
		}
	}
	clock, err := basicAlertClock(Object{}, time.Date(2026, 11, 9, 20, 30, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		text              string
		hour, minute, day int
		repeat            string
	}{
		{"đặt báo thức lúc bảy giờ sáng", 7, 0, 10, "None"},
		{"dat bao thuc luc bay gio ba muoi phut sang", 7, 30, 10, "None"},
		{"đặt báo thức 7h30", 7, 30, 10, "None"},
		{"đặt báo thức 7 giờ rưỡi tối", 19, 30, 10, "None"},
		{"đặt báo thức 12 giờ đêm", 0, 0, 11, "None"},
		{"đặt báo thức 12 giờ trưa hằng ngày", 12, 0, 10, "Daily"},
	} {
		directive, explanation := basicAlertDirective(test.text, clock)
		payload, _ := directive["payload"].(map[string]any)
		if directive == nil || explanation != "" || integer(payload, "hour", -1) != test.hour || integer(payload, "minute", -1) != test.minute || integer(payload, "day", -1) != test.day || str(payload, "repeatType") != test.repeat {
			t.Errorf("%q: unexpected alert %#v / %q", test.text, directive, explanation)
		}
	}
	if directive, explanation := basicAlertDirective("đặt báo thức lúc 7 giờ sáng ngày mai", clock); directive != nil || explanation == "" {
		t.Fatal("Clock cannot represent tomorrow when today's same time is earlier")
	}
	reminder, _ := basicAlertDirective("nhắc tôi kiểm tra lịch lúc 7 giờ sáng ngày mai", clock)
	payload, _ := reminder["payload"].(map[string]any)
	if str(payload, "task") != "kiểm tra lịch" || integer(payload, "day", 0) != 11 || str(payload, "timezone") != "Asia/Ho_Chi_Minh" || integer(payload, "utc_offset_seconds", 0) != 25200 {
		t.Fatal("Calendar reminder lost its title/date/timezone")
	}
	for _, text := range []string{"đừng đặt báo thức 7 giờ", "có nên đặt báo thức 7 giờ không", "đặt báo thức lúc bây giờ", "đặt báo thức 25 giờ", "đặt báo thức 7:60", "đặt báo thức 7 giờ sáng và mở nhạc", "đặt báo thức 7 giờ sáng hôm nay ngày mai"} {
		if directive, _ := basicAlertDirective(text, clock); directive != nil {
			t.Errorf("Ambiguous alert was accepted: %q", text)
		}
	}
	friday := time.Date(2026, 11, 13, 22, 0, 0, 0, clock.Location())
	date, repeat, ok := basicAlertDate("7 giờ các ngày trong tuần", friday, false)
	if !ok || repeat != "Weekdays" || date.Weekday() != time.Monday || date.Day() != 16 {
		t.Fatal("Weekday alarm did not skip the weekend")
	}
	endOfYear := time.Date(2026, 12, 31, 23, 50, 0, 0, clock.Location())
	date, _, ok = basicAlertDate("0:05", endOfYear, false)
	if !ok || date.Year() != 2027 || date.Month() != time.January || date.Day() != 1 {
		t.Fatal("Next occurrence did not cross the year correctly")
	}
}

func TestBasicAlertTimezoneValidationAndRequestReplay(t *testing.T) {
	instant := time.Date(2027, 1, 1, 0, 30, 0, 0, time.UTC)
	for _, zone := range []string{"Asia/Ho_Chi_Minh", "America/Los_Angeles", "UTC", "GMT+07:00", "GMT-05:30"} {
		if _, err := basicAlertClock(Object{"timezone": zone}, instant); err != nil {
			t.Errorf("Valid timezone %s rejected", zone)
		}
	}
	for _, zone := range []any{"", "Local", "Not/AZone", "GMT+15:00", "GMT+07:99", " Asia/Bangkok", true, 7} {
		if _, err := basicAlertClock(Object{"timezone": zone}, instant); err == nil {
			t.Errorf("Invalid timezone accepted: %#v", zone)
		}
	}
	local, _ := basicAlertClock(Object{"timezone": "America/Los_Angeles"}, instant)
	date, _, ok := basicAlertDate("20:00", local, false)
	if !ok || date.Year() != 2026 || date.Month() != time.December || date.Day() != 31 {
		t.Fatal("VPS UTC date replaced the device's local date")
	}
	gap, _ := basicAlertClock(Object{"timezone": "America/New_York"}, time.Date(2027, 3, 14, 5, 0, 0, 0, time.UTC))
	if _, _, ok := basicAlertDate("2:30", gap, false); ok {
		t.Fatal("A nonexistent DST wall time was normalized silently")
	}
	s := independentTestServer(t)
	account := guest(t, s)
	token := str(account, "access_token")
	request := Object{"text": "đặt báo thức 20 giờ", "request_id": "timezone-offer", "conversation_id": "timezone-context", "timezone": "Asia/Ho_Chi_Minh"}
	request["client_capabilities"] = Object{"basic_alerts": true}
	code, offer := call(t, s, "POST", "/v1/assistant/text", token, request)
	requireStatus(t, 200, code, offer)
	request["timezone"] = "America/Los_Angeles"
	code, conflict := call(t, s, "POST", "/v1/assistant/text", token, request)
	requireStatus(t, 409, code, conflict)
	if str(conflict["error"].(map[string]any), "code") != "REQUEST_ID_CONFLICT" {
		t.Fatal("Changed timezone reused an old pending proposal")
	}
	code, list := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "mở danh sách báo thức", "request_id": "show-alarms"})
	requireStatus(t, 200, code, list)
	if list["confirmation_required"] == true || len(objectDirectives(list)) != 1 || str(objectDirectives(list)[0]["payload"].(map[string]any), "type") != "ShowAlarms" {
		t.Fatal("Reading alarms should not create a mutation confirmation")
	}
	code, legacy := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "đặt hẹn giờ 5 phút", "request_id": "legacy-client"})
	requireStatus(t, 200, code, legacy)
	if legacy["confirmation_required"] == true || str(legacy, "confirmation_token") != "" || !strings.Contains(str(legacy, "utterance"), "cập nhật") {
		t.Fatal("Older Android clients cannot display commands-mode Alerts consent")
	}
	for _, directive := range objectDirectives(legacy) {
		if str(directive, "type") != "SpeechSynthesizer" {
			t.Fatal("Unsupported legacy client received a device mutation")
		}
	}
}
