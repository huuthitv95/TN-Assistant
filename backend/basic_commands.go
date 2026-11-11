package main

import (
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

var volumePercentageCommand = regexp.MustCompile(`^(?:đặt |chỉnh |điều chỉnh )?âm lượng (?:về |ở mức )?([0-9]{1,3})\s*(?:%|phần trăm)$`)

// Interpret only a complete call imperative. Numeric targets must be complete
// digit sequences; named targets are passed to the on-device contacts resolver
// and remain behind the same explicit confirmation.
func basicPhoneDirective(text string) Object {
	target := firstTNCommandRemainder(strings.TrimRight(strings.TrimSpace(text), ".!?"),
		"gọi điện đến số", "gọi điện cho số", "gọi điện số", "gọi đến số", "gọi cho số", "gọi tới số", "gọi số",
		"gọi điện đến", "gọi điện cho", "gọi điện", "gọi đến", "gọi tới", "gọi cho", "gọi")
	if target == "" {
		return nil
	}
	if strings.HasPrefix(strings.TrimSpace(target), "-") {
		return nil
	}
	words := strings.Fields(strings.NewReplacer("-", " ", ".", " ", ",", " ", "(", " ", ")", " ").Replace(normalizeTNCommand(target)))
	digits := map[string]string{"khong": "0", "mot": "1", "hai": "2", "ba": "3", "bon": "4", "tu": "4", "nam": "5", "lam": "5", "sau": "6", "bay": "7", "tam": "8", "chin": "9"}
	var number strings.Builder
	numeric := true
	for index, word := range words {
		if index == 0 && (word == "cong" || word == "+") {
			number.WriteByte('+')
			continue
		}
		if value, ok := digits[word]; ok {
			number.WriteString(value)
			continue
		}
		for offset, char := range word {
			if char >= '0' && char <= '9' {
				number.WriteRune(char)
			} else if index == 0 && offset == 0 && char == '+' {
				number.WriteByte('+')
			} else {
				numeric = false
				break
			}
		}
	}
	phone := number.String()
	if numeric && aiPhone.MatchString(phone) {
		return Object{"type": "PhoneCall", "payload": Object{"phone_number": phone}}
	}
	if contact := basicContactPhoneTarget(text); contact != "" {
		return Object{"type": "PhoneCall", "payload": Object{"contact_name": contact}}
	}
	return nil
}

func basicContactPhoneTarget(text string) string {
	// Requiring an explicit "cho" form avoids interpreting unrelated commands
	// such as "gọi xe" as contact lookups. Retain the original name accents.
	query := firstTNCommandRemainder(text, "gọi điện cho", "gọi cho")
	if query == "" {
		return ""
	}
	query = strings.TrimSpace(strings.TrimRight(strings.TrimSpace(query), ".!?"))
	runes := []rune(query)
	if len(runes) < 2 || len(runes) > 80 {
		return ""
	}
	normalized := normalizeTNCommand(query)
	if strings.HasPrefix(normalized, "so ") {
		return ""
	}
	for _, word := range strings.Fields(normalized) {
		// Do not collapse a multi-action request into a call to a contact whose
		// name happens to precede the second command.
		if word == "va" {
			return ""
		}
	}
	for _, r := range runes {
		if unicode.IsLetter(r) || unicode.IsMark(r) || unicode.IsSpace(r) || strings.ContainsRune("-'.’", r) {
			continue
		}
		return ""
	}
	return query
}

// The owned guide explicitly exposes max/increase/decrease volume, and the
// original PlayerVolume classifier/VolumeService defines relative steps and
// absolute percentages. Route only these complete imperative utterances to the
// real Android adapter; questions, negations and compound requests stay with NLU.
// An emitted directive is a request for execution, never an assertion that the
// device changed volume. Android must acknowledge its actual AudioManager state.
func basicVolumeCommand(text string) Object {
	command := strings.ToLower(strings.Join(strings.Fields(strings.TrimSpace(text)), " "))
	command = strings.TrimRight(command, ".!?")
	payload := Object{}
	switch command {
	case "tăng âm lượng":
		payload["command"] = "increase_volume"
	case "giảm âm lượng":
		payload["command"] = "decrease_volume"
	case "tăng âm lượng tối đa", "âm lượng tối đa":
		payload = Object{"command": "adjust_volume", "adjust_level": -1, "adjust_percentage": 100}
	case "tắt âm lượng":
		payload = Object{"command": "adjust_volume", "adjust_level": -1, "adjust_percentage": 0}
	default:
		matched := volumePercentageCommand.FindStringSubmatch(command)
		if matched == nil {
			return nil
		}
		percentage, err := strconv.Atoi(matched[1])
		if err != nil || percentage < 0 || percentage > 100 {
			return nil
		}
		payload = Object{"command": "adjust_volume", "adjust_level": -1, "adjust_percentage": percentage}
	}
	payload["action_id"] = "volume"
	payload["required"] = []string{}
	return Object{"source": "local-command", "utterance": "", "provider_latency_ms": 0,
		"directives": []Object{{"type": "PlayerVolume", "payload": payload}}}
}

// Only complete transport imperatives are resolved locally. Track search,
// favourites and provider accounts require their own real integrations.
func basicMediaCommand(text string) Object {
	command := normalizeTNCommand(strings.Join(strings.Fields(text), " "))
	command = strings.TrimRight(command, ".!")
	var action string
	switch command {
	case "bai tiep theo", "chuyen bai", "chuyen bai tiep theo", "bai ke tiep":
		action = "music_control_next"
	case "bai truoc", "bai truoc do", "quay lai bai truoc":
		action = "music_control_prev"
	case "phat tiep", "phat tiep nhac", "tiep tuc phat nhac", "tiep tuc nghe nhac":
		action = "music_control_resume"
	case "phat lai bai nay", "nghe lai bai nay", "phat lai tu dau":
		action = "music_control_replay"
	case "tam dung", "tam dung nhac", "dung nhac", "tat nhac":
		action = "music_control_stop"
	default:
		return nil
	}
	return Object{"source": "local-command", "utterance": "", "provider_latency_ms": 0,
		"directives": []Object{{"type": "PlayerMP3", "payload": Object{
			"command": action, "action_id": "media", "required": []string{},
		}}}}
}

// Route explicit YouTube searches and open-link commands locally. The original
// client prefers direct_link, then an explicit YouTube search, then video_id,
// and otherwise falls back to YouTube results when its private parser is not
// available. TN preserves that order without depending on the original service.
func basicYouTubeCommand(text string) Object {
	clean := strings.TrimSpace(strings.TrimRight(strings.TrimSpace(text), ".!?"))
	if clean == "" {
		return nil
	}
	switch normalizeTNCommand(strings.Join(strings.Fields(clean), " ")) {
	case "mo youtube", "mo ung dung youtube", "mo app youtube":
		return basicYouTubeDirective(Object{"direct_link": "https://www.youtube.com/"})
	}
	for _, prefix := range []string{"mo lien ket", "mo link", "mo duong dan", "mo url", "truy cap lien ket", "truy cap duong dan", "truy cap link", "mo trang", "truy cap", "mo"} {
		candidate := firstTNCommandRemainder(clean, prefix)
		if candidate == "" {
			continue
		}
		candidate = strings.Trim(strings.TrimSpace(candidate), "\"'<>.,!?;:()[]{}")
		// The short imperatives also match non-URL commands (for example
		// "Mở video ..."). Only treat them as a direct-link request when the
		// remaining first token is explicitly an HTTPS URL.
		if (prefix == "mo" || prefix == "truy cap") && !strings.HasPrefix(strings.ToLower(candidate), "https://") {
			continue
		}
		link, ok := basicHTTPSLink(candidate)
		if !ok {
			return nil
		}
		return basicYouTubeDirective(Object{"direct_link": link})
	}

	var query string
	for _, prefix := range []string{"tim tren youtube", "tim youtube", "tim video tren youtube", "mo video", "phat video", "tim video", "tim"} {
		candidate := strings.TrimSpace(firstTNCommandRemainder(clean, prefix))
		if candidate == "" {
			continue
		}
		normalized := strings.TrimRight(normalizeTNCommand(candidate), ".!?,;:")
		explicitYouTube := strings.HasPrefix(normalizeTNCommand(prefix), "tim tren youtube") ||
			strings.HasPrefix(normalizeTNCommand(prefix), "tim youtube") ||
			strings.HasPrefix(normalizeTNCommand(prefix), "tim video tren youtube") ||
			strings.HasSuffix(normalized, " tren youtube")
		if !explicitYouTube {
			continue
		}
		words := strings.Fields(candidate)
		if len(words) >= 2 && normalizeTNCommand(words[len(words)-2]+" "+words[len(words)-1]) == "tren youtube" {
			candidate = strings.Join(words[:len(words)-2], " ")
		}
		query = strings.Trim(strings.TrimSpace(candidate), "\"'.,!?;:")
		break
	}
	if query == "" || len([]rune(query)) > 200 {
		return nil
	}
	for _, r := range query {
		if unicode.IsControl(r) {
			return nil
		}
	}
	return basicYouTubeDirective(Object{"query": query, "youtube_search": true})
}

func basicHTTPSLink(value string) (string, bool) {
	if value == "" || len(value) > 2048 || strings.ContainsAny(value, "\r\n\t ") {
		return "", false
	}
	parsed, err := url.Parse(value)
	if err != nil || !strings.EqualFold(parsed.Scheme, "https") || parsed.Hostname() == "" || parsed.User != nil {
		return "", false
	}
	if port := parsed.Port(); port != "" && port != "443" {
		return "", false
	}
	return parsed.String(), true
}

func basicYouTubeDirective(payload Object) Object {
	payload["action_id"] = "youtube"
	payload["required"] = []string{}
	return Object{"source": "local-command", "utterance": "", "provider_latency_ms": 0,
		"directives": []Object{{"type": "YouTube", "payload": payload}}}
}
