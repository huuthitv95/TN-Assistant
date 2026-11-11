package main

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"
)

var basicClockTime = regexp.MustCompile(`^(?:luc |vao luc |vao )?([0-9]{1,2})(?::([0-9]{1,2})|h([0-9]{1,2})?| gio(?: ([0-9]{1,2})(?: phut)?)?)(?: (sang|trua|chieu|toi|dem))?$`)
var basicTimerPart = regexp.MustCompile(`^([0-9]{1,5})\s*(gio|phut|giay)(?:\s+|$)`)
var basicFixedTimezone = regexp.MustCompile(`^GMT([+-])([0-9]{2}):([0-9]{2})$`)

func basicAlertRequest(text string) bool {
	command := normalizeTNCommand(strings.Join(strings.Fields(strings.TrimRight(strings.TrimSpace(text), ".!?")), " "))
	for _, prefix := range []string{"dat hen gio", "hen gio", "dem nguoc", "dat bo dem nguoc", "dat bao thuc", "bao thuc",
		"nhac toi", "nhac nho toi", "tao nhac nho", "tat bao thuc", "huy bao thuc", "mo bao thuc", "xem bao thuc", "mo danh sach bao thuc", "xem danh sach bao thuc"} {
		if command == prefix || strings.HasPrefix(command, prefix+" ") {
			return true
		}
	}
	return false
}

// Old clients use the same Vietnam default as the existing TN date/time router.
// New clients send their actual IANA/fixed-offset timezone. Never use VPS Local.
func basicAlertClock(input Object, instant time.Time) (time.Time, error) {
	zone := "Asia/Ho_Chi_Minh"
	if value, exists := input["timezone"]; exists {
		var ok bool
		zone, ok = value.(string)
		if !ok || zone == "" || len(zone) > 80 || strings.TrimSpace(zone) != zone || zone == "Local" {
			return time.Time{}, problem(400, "INVALID_TIMEZONE", "A valid device timezone is required")
		}
	}
	location, err := time.LoadLocation(zone)
	if err != nil {
		if parts := basicFixedTimezone.FindStringSubmatch(zone); parts != nil {
			hour, _ := strconv.Atoi(parts[2])
			minute, _ := strconv.Atoi(parts[3])
			if hour < 14 || hour == 14 && minute == 0 {
				if minute < 60 {
					offset := hour*3600 + minute*60
					if parts[1] == "-" {
						offset = -offset
					}
					location, err = time.FixedZone(zone, offset), nil
				}
			}
		}
	}
	if err != nil {
		return time.Time{}, problem(400, "INVALID_TIMEZONE", "A valid device timezone is required")
	}
	return instant.In(location), nil
}

func basicTemporalText(text string) string {
	// Convert only exact accented cardinals or their unaccented ASR spellings.
	// In particular, "bây giờ" must never become "bảy giờ".
	names := map[string]string{"khong": "không", "mot": "một", "bon": "bốn", "nam": "năm", "sau": "sáu", "bay": "bảy", "tam": "tám", "chin": "chín",
		"muoi": "mười", "tram": "trăm", "linh": "linh", "le": "lẻ", "tu": "tư", "lam": "lăm"}
	words := strings.Fields(strings.ToLower(text))
	for index, word := range words {
		if canonical, ok := names[word]; ok {
			if word == "muoi" && index > 0 {
				if digit, ok := calculatorSpokenDigit(words[index-1]); ok && digit >= 2 {
					canonical = "mươi"
				}
			}
			words[index] = canonical
		}
	}
	return normalizeTNCommand(normalizeCalculatorSpokenNumbers(strings.Join(words, " ")))
}

func basicTimerSeconds(text string) (int, bool) {
	text = basicTemporalText(text)
	for _, prefix := range []string{"trong ", "sau "} {
		if strings.HasPrefix(text, prefix) {
			text = strings.TrimPrefix(text, prefix)
			break
		}
	}
	if text == "nua gio" {
		return 1800, true
	}
	if text == "nua phut" {
		return 30, true
	}
	total, previous := 0, 86401
	for text != "" {
		part := basicTimerPart.FindStringSubmatch(text)
		if part == nil {
			return 0, false
		}
		amount, _ := strconv.Atoi(part[1])
		scale := map[string]int{"gio": 3600, "phut": 60, "giay": 1}[part[2]]
		if scale >= previous || amount > 86400/scale || total+amount*scale > 86400 {
			return 0, false
		}
		total, previous = total+amount*scale, scale
		text = strings.TrimSpace(text[len(part[0]):])
	}
	return total, total > 0
}

func basicAlertDate(text string, clock time.Time, reminder bool) (time.Time, string, bool) {
	text = basicTemporalText(text)
	repeat := "None"
	for _, item := range []struct{ suffix, value string }{
		{"cac ngay trong tuan", "Weekdays"}, {"cuoi tuan", "Weekend"}, {"hang ngay", "Daily"}, {"moi ngay", "Daily"}, {"hang tuan", "Weekly"},
	} {
		if strings.HasSuffix(text, " "+item.suffix) {
			repeat, text = item.value, strings.TrimSuffix(text, " "+item.suffix)
			break
		}
	}
	day := ""
	for _, candidate := range []string{"hom nay", "ngay mai"} {
		if strings.HasPrefix(text, candidate+" ") {
			day, text = candidate, strings.TrimPrefix(text, candidate+" ")
		} else if strings.HasSuffix(text, " "+candidate) {
			day, text = candidate, strings.TrimSuffix(text, " "+candidate)
		}
	}
	text = strings.ReplaceAll(text, " gio ruoi", " gio 30")
	parts := basicClockTime.FindStringSubmatch(text)
	if parts == nil {
		return time.Time{}, "", false
	}
	hour, _ := strconv.Atoi(parts[1])
	minute := 0
	for _, candidate := range parts[2:5] {
		if candidate != "" {
			minute, _ = strconv.Atoi(candidate)
		}
	}
	if hour > 23 || minute > 59 {
		return time.Time{}, "", false
	}
	switch parts[5] {
	case "sang":
		if hour > 12 {
			return time.Time{}, "", false
		}
		hour %= 12
	case "chieu":
		if hour < 12 {
			hour += 12
		}
	case "toi":
		if hour == 12 {
			hour = 0
		} else if hour < 12 {
			hour += 12
		}
	case "trua":
		if hour == 1 || hour == 2 {
			hour += 12
		} else if hour != 11 && hour != 12 {
			return time.Time{}, "", false
		}
	case "dem":
		if hour == 12 {
			hour = 0
		} else if hour >= 6 && hour < 12 {
			hour += 12
		}
	}
	date := time.Date(clock.Year(), clock.Month(), clock.Day(), hour, minute, 0, 0, clock.Location())
	// A DST gap cannot silently normalize to a different wall clock time.
	if date.Hour() != hour || date.Minute() != minute {
		return time.Time{}, "", false
	}
	if !date.After(clock) {
		date = date.AddDate(0, 0, 1)
	}
	for (repeat == "Weekdays" && (date.Weekday() == time.Saturday || date.Weekday() == time.Sunday)) ||
		(repeat == "Weekend" && date.Weekday() != time.Saturday && date.Weekday() != time.Sunday) {
		date = date.AddDate(0, 0, 1)
	}
	if day != "" {
		explicit := clock
		if day == "ngay mai" {
			explicit = clock.AddDate(0, 0, 1)
		}
		if reminder {
			date = time.Date(explicit.Year(), explicit.Month(), explicit.Day(), hour, minute, 0, 0, clock.Location())
		} else if date.Year() != explicit.Year() || date.YearDay() != explicit.YearDay() {
			return time.Time{}, "unrepresentable", true
		}
	}
	if !date.After(clock) || date.Hour() != hour || date.Minute() != minute {
		return time.Time{}, "", false
	}
	return date, repeat, true
}

// Return an immutable Alerts proposal or an explicit representability explanation.
// Unrecognized/ambiguous utterances remain with the existing NLU/fallback path.
func basicAlertDirective(text string, clock time.Time) (Object, string) {
	clean := strings.TrimRight(strings.TrimSpace(text), ".!?")
	command := normalizeTNCommand(strings.Join(strings.Fields(clean), " "))
	switch command {
	case "mo bao thuc", "xem bao thuc", "mo danh sach bao thuc", "xem danh sach bao thuc":
		return Object{"type": "Alerts", "payload": Object{"type": "ShowAlarms"}}, ""
	case "tat bao thuc tiep theo", "huy bao thuc tiep theo", "tat bao thuc dang reo":
		return Object{"type": "Alerts", "payload": Object{"type": "DismissAlarm", "searchMode": "next"}}, ""
	}
	if tail := firstTNCommandRemainder(clean, "đặt hẹn giờ", "hẹn giờ", "đặt bộ đếm ngược", "đếm ngược"); tail != "" {
		if seconds, ok := basicTimerSeconds(tail); ok {
			return Object{"type": "Alerts", "payload": Object{"type": "Timer", "duration": seconds}}, ""
		}
		return nil, ""
	}
	kind, tail, task := "Alarm", "", ""
	if remainder := firstTNCommandRemainder(clean, "nhắc nhở tôi", "nhắc tôi", "tạo nhắc nhở"); remainder != "" {
		words := strings.Fields(remainder)
		for index := len(words) - 1; index > 0; index-- {
			marker := normalizeTNCommand(words[index])
			if marker == "luc" || marker == "vao" {
				kind, tail, task = "Reminder", strings.Join(words[index:], " "), strings.Join(words[:index], " ")
				break
			}
		}
		if task == "" || len([]rune(task)) > 200 {
			return nil, ""
		}
	} else if remainder := firstTNCommandRemainder(clean, "tắt báo thức", "hủy báo thức"); remainder != "" {
		kind, tail = "DismissAlarm", remainder
	} else {
		tail = firstTNCommandRemainder(clean, "đặt báo thức", "báo thức")
	}
	if tail == "" {
		return nil, ""
	}
	date, repeat, ok := basicAlertDate(tail, clock, kind == "Reminder")
	if !ok {
		return nil, ""
	}
	if repeat == "unrepresentable" {
		return nil, "Đồng hồ chỉ hỗ trợ lần báo thức kế tiếp ở giờ này. Hãy chọn giờ khác hoặc tạo nhắc việc trong lịch."
	}
	payload := Object{"type": kind, "hour": date.Hour(), "minute": date.Minute(), "timezone": clock.Location().String()}
	_, offset := date.Zone()
	if kind == "DismissAlarm" {
		_, offset = clock.Zone()
	}
	payload["utc_offset_seconds"] = offset
	if kind == "DismissAlarm" {
		payload["searchMode"] = "time"
	} else {
		payload["year"], payload["month"], payload["day"], payload["repeatType"] = date.Year(), int(date.Month()), date.Day(), repeat
		if kind == "Reminder" {
			payload["task"] = task
		}
	}
	return Object{"type": "Alerts", "payload": payload}, ""
}

func basicTimerSummary(seconds int) string {
	parts := []string{}
	for _, unit := range []struct {
		value int
		label string
	}{{3600, "giờ"}, {60, "phút"}, {1, "giây"}} {
		if count := seconds / unit.value; count > 0 {
			parts = append(parts, fmt.Sprintf("%d %s", count, unit.label))
		}
		seconds %= unit.value
	}
	return "Đặt hẹn giờ trong " + strings.Join(parts, " ") + "?"
}
