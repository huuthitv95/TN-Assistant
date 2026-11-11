package main

import (
	"net/url"
	"strings"
	"time"
)

func normalizeTNCommand(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	return strings.NewReplacer(
		"à", "a", "á", "a", "ạ", "a", "ả", "a", "ã", "a", "â", "a", "ầ", "a", "ấ", "a", "ậ", "a", "ẩ", "a", "ẫ", "a", "ă", "a", "ằ", "a", "ắ", "a", "ặ", "a", "ẳ", "a", "ẵ", "a",
		"è", "e", "é", "e", "ẹ", "e", "ẻ", "e", "ẽ", "e", "ê", "e", "ề", "e", "ế", "e", "ệ", "e", "ể", "e", "ễ", "e",
		"ì", "i", "í", "i", "ị", "i", "ỉ", "i", "ĩ", "i",
		"ò", "o", "ó", "o", "ọ", "o", "ỏ", "o", "õ", "o", "ô", "o", "ồ", "o", "ố", "o", "ộ", "o", "ổ", "o", "ỗ", "o", "ơ", "o", "ờ", "o", "ớ", "o", "ợ", "o", "ở", "o", "ỡ", "o",
		"ù", "u", "ú", "u", "ụ", "u", "ủ", "u", "ũ", "u", "ư", "u", "ừ", "u", "ứ", "u", "ự", "u", "ử", "u", "ữ", "u",
		"ỳ", "y", "ý", "y", "ỵ", "y", "ỷ", "y", "ỹ", "y", "đ", "d",
	).Replace(value)
}

func firstTNCommandRemainder(text string, prefixes ...string) string {
	words := strings.Fields(text)
	for _, prefix := range prefixes {
		prefixWords := strings.Fields(prefix)
		if len(words) <= len(prefixWords) {
			continue
		}
		matches := true
		for index, word := range prefixWords {
			if normalizeTNCommand(words[index]) != normalizeTNCommand(word) {
				matches = false
				break
			}
		}
		if matches {
			// Match normalized words, then retain complete input words. Accents
			// change UTF-8 byte lengths; slicing by the template length corrupts
			// destinations and can panic on short unaccented commands.
			return strings.Join(words[len(prefixWords):], " ")
		}
	}
	return ""
}

func tnSpeechReply(text string, directive Object) Object {
	directives := []Object{}
	if directive != nil {
		directives = append(directives, directive)
	}
	if strings.TrimSpace(text) != "" {
		directives = append(directives, Object{"type": "SpeechSynthesizer", "payload": Object{"utter_text": text}})
	}
	return Object{"source": "tn_command_router", "utterance": text, "directives": directives, "provider_latency_ms": 0}
}

func (s *Server) tnCommandAssistant(text string) (Object, error) {
	if len([]rune(strings.TrimSpace(text))) == 0 || len([]rune(text)) > 1000 {
		return nil, problem(400, "INVALID_COMMAND", "Command text must contain 1..1000 characters")
	}
	normalized := normalizeTNCommand(text)
	if answer, ok := tnCalculatorAnswer(text); ok {
		return tnSpeechReply(answer, nil), nil
	}
	if strings.Contains(normalized, "may gio") || strings.Contains(normalized, "bay gio la may gio") {
		clock := time.Now().In(time.FixedZone("ICT", 7*60*60))
		return tnSpeechReply("Bây giờ là "+clock.Format("15 giờ 04"), nil), nil
	}
	if strings.Contains(normalized, "hom nay ngay may") || strings.Contains(normalized, "hom nay la ngay may") {
		clock := time.Now().In(time.FixedZone("ICT", 7*60*60))
		return tnSpeechReply("Hôm nay là ngày "+clock.Format("02 tháng 01 năm 2006"), nil), nil
	}
	if query := firstTNCommandRemainder(text, "chỉ đường đến", "chỉ đường tới", "dẫn đường đến", "dẫn đường tới", "tìm đường đến", "tìm đường tới", "đưa tôi đến", "đưa tôi tới", "đi đến"); query != "" {
		if len([]rune(query)) > 180 {
			return nil, problem(400, "INVALID_DESTINATION", "Destination name is too long")
		}
		mapsURL := "https://www.google.com/maps/search/?api=1&query=" + url.QueryEscape(query)
		return tnSpeechReply("Đang mở bản đồ tới "+query, Object{"type": "MapDirection", "payload": Object{"url": mapsURL, "query": query}}), nil
	}
	if query := firstTNCommandRemainder(text, "mở"); query != "" {
		packages := map[string]string{
			"ban do": "com.google.android.apps.maps", "google maps": "com.google.android.apps.maps", "maps": "com.google.android.apps.maps",
			"youtube": "com.google.android.youtube", "spotify": "com.spotify.music", "chrome": "com.android.chrome", "trinh duyet chrome": "com.android.chrome",
		}
		if packageName := packages[normalizeTNCommand(query)]; packageName != "" {
			return tnSpeechReply("Đang mở "+query, Object{"type": "OpenApp", "payload": Object{"package_name": packageName}}), nil
		}
	}
	if strings.HasPrefix(normalized, "phat nhac") || strings.HasPrefix(normalized, "mo nhac") || strings.HasPrefix(normalized, "phat bai") {
		return tnSpeechReply("Đang chuyển lệnh phát nhạc tới ứng dụng media trên thiết bị.", Object{"type": "PlayMusicApp", "payload": Object{"query": strings.TrimSpace(text)}}), nil
	}
	unknown := tnSpeechReply("Lệnh này chưa có trong TN Assistant.", nil)
	// Commands-only clients still receive the spoken fallback. QA/tools must
	// recognize it as unhandled rather than suppressing their configured AI.
	unknown["source"] = "tn_command_router_fallback"
	return unknown, nil
}
