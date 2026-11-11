package main

import (
	"strconv"
	"strings"
)

// ASR may spell the same spoken number as digits over HTTP and words over WS.
// Normalize only complete cardinal groups in 0..999. The calculator's existing
// complete-expression/unit grammar still decides whether the request is math.
// Ambiguous sequences such as "một hai" remain unchanged and cannot be evaluated.
func normalizeCalculatorSpokenNumbers(text string) string {
	words := strings.Fields(text)
	for index := 0; index < len(words); {
		if !calculatorNumberWord(words[index]) {
			index++
			continue
		}
		end := index + 1
		for end < len(words) && calculatorNumberWord(words[end]) {
			end++
		}
		if number, ok := parseCalculatorSpokenCardinal(words[index:end]); ok {
			words = append(words[:index], append([]string{strconv.Itoa(number)}, words[end:]...)...)
			index++
		} else {
			index = end
		}
	}
	return strings.Join(words, " ")
}

func calculatorNumberWord(word string) bool {
	if _, ok := calculatorSpokenDigit(word); ok {
		return true
	}
	switch word {
	case "mốt", "tư", "lăm", "mười", "mươi", "trăm", "linh", "lẻ":
		return true
	}
	return false
}

func calculatorSpokenDigit(word string) (int, bool) {
	for digit, name := range []string{"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"} {
		if word == name {
			return digit, true
		}
	}
	return 0, false
}

func parseCalculatorSpokenCardinal(words []string) (int, bool) {
	if len(words) == 0 {
		return 0, false
	}
	if len(words) >= 2 && words[1] == "trăm" {
		hundreds, ok := calculatorSpokenDigit(words[0])
		if !ok || hundreds == 0 {
			return 0, false
		}
		if len(words) == 2 {
			return hundreds * 100, true
		}
		rest := words[2:]
		if rest[0] == "linh" || rest[0] == "lẻ" {
			if len(rest) != 2 {
				return 0, false
			}
			units, ok := calculatorSpokenDigit(rest[1])
			return hundreds*100 + units, ok && units > 0
		}
		remainder, ok := parseCalculatorSpokenTens(rest)
		return hundreds*100 + remainder, ok && remainder >= 10
	}
	if len(words) == 1 {
		if digit, ok := calculatorSpokenDigit(words[0]); ok {
			return digit, true
		}
	}
	return parseCalculatorSpokenTens(words)
}

func parseCalculatorSpokenTens(words []string) (int, bool) {
	if len(words) == 0 {
		return 0, false
	}
	tens, consumed := 0, 0
	if words[0] == "mười" {
		tens, consumed = 1, 1
	} else if len(words) >= 2 && words[1] == "mươi" {
		value, ok := calculatorSpokenDigit(words[0])
		if !ok || value < 2 {
			return 0, false
		}
		tens, consumed = value, 2
	} else {
		return 0, false
	}
	if len(words) == consumed {
		return tens * 10, true
	}
	if len(words) != consumed+1 {
		return 0, false
	}
	unit := words[consumed]
	units, ok := calculatorSpokenDigit(unit)
	switch unit {
	case "mốt":
		units, ok = 1, tens >= 2
	case "tư":
		units, ok = 4, tens >= 2
	case "lăm":
		units, ok = 5, true
	}
	return tens*10 + units, ok && units > 0
}
