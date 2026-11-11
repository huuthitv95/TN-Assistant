package main

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
)

var (
	calcUnitQuery = regexp.MustCompile(`^\s*([+-]?[0-9]+(?:[.,][0-9]+)?)\s+(.+?)\s+(?:là|bằng)\s+(?:bao nhiêu|mấy)\s+(.+?)\s*[?!.]*\s*$`)
	calcDiscount  = regexp.MustCompile(`^\s*([0-9]+(?:[.,][0-9]+)?)\s*(triệu|nghìn|ngàn|tỷ|tỉ)?\s+giảm\s+([0-9]+(?:[.,][0-9]+)?)\s*%\s*(?:còn\s+(?:bao nhiêu|mấy))?\s*[?!.]*\s*$`)
	calcImplicitX = regexp.MustCompile(`([0-9.)])\s*x\b`)
	calcSpokenX   = regexp.MustCompile(`\bx\b`)
)

// tnCalculatorAnswer handles TN Assistant's bounded arithmetic,
// percentage-reduction, and unit-conversion forms. Unknown or malformed text is
// left for the configured NLU/AI provider; expressions are never evaluated as Go.
func tnCalculatorAnswer(text string) (string, bool) {
	text = normalizeCalculatorText(text)
	if answer, ok := convertTNUnit(text); ok {
		return answer, true
	}
	if answer, ok := calculateDiscount(text); ok {
		return answer, true
	}
	if answer, ok := calculateTNExpression(text); ok {
		return answer, true
	}
	return "", false
}

func normalizeCalculatorText(text string) string {
	text = strings.ToLower(strings.Join(strings.Fields(strings.TrimSpace(text)), " "))
	for _, greeting := range []string{"tn ơi, ", "tn ơi ", "tn, ", "tn "} {
		text = strings.TrimPrefix(text, greeting)
	}
	for _, prefix := range []string{"hãy tính giúp tôi ", "tính giúp tôi ", "tính toán ", "tính "} {
		text = strings.TrimPrefix(text, prefix)
	}
	return normalizeCalculatorSpokenNumbers(strings.TrimSpace(strings.TrimRight(text, "?! .")))
}

func convertTNUnit(text string) (string, bool) {
	match := calcUnitQuery.FindStringSubmatch(text)
	if match == nil {
		return "", false
	}
	value, ok := parseCalculatorNumber(match[1])
	if !ok || math.Abs(value) > 1e12 {
		return "", false
	}
	from, fromOK := tnUnit(strings.TrimSpace(match[2]))
	to, toOK := tnUnit(strings.TrimSpace(match[3]))
	if !fromOK || !toOK || from.dimension != to.dimension {
		return "", false
	}
	converted := 0.0
	switch from.dimension {
	case "temperature":
		if from.label == "°F" && to.label == "°C" {
			converted = (value - 32) * 5 / 9
		} else if from.label == "°C" && to.label == "°F" {
			converted = value*9/5 + 32
		} else {
			return "", false
		}
	default:
		converted = value * from.toBase / to.toBase
	}
	if !boundedCalculatorValue(converted) {
		return "", false
	}
	return fmt.Sprintf("%s %s bằng %s %s.", formatVietnameseNumber(value), from.label,
		formatVietnameseNumber(converted), to.label), true
}

type calculatorUnit struct {
	dimension string
	label     string
	toBase    float64
}

func tnUnit(value string) (calculatorUnit, bool) {
	value = strings.Join(strings.Fields(strings.TrimSpace(value)), " ")
	switch value {
	case "dặm", "mile", "miles":
		return calculatorUnit{"length", "dặm", 1609.344}, true
	case "mét", "met", "meter", "meters", "m":
		return calculatorUnit{"length", "mét", 1}, true
	case "cm", "centimet", "centimeter", "centimeters":
		return calculatorUnit{"length", "cm", 0.01}, true
	case "inch", "inches", "in":
		return calculatorUnit{"length", "inch", 0.0254}, true
	case "hecta", "hectare", "hectares", "ha":
		return calculatorUnit{"area", "hecta", 10000}, true
	case "mét vuông", "met vuong", "m vuông", "m2", "m²", "sqm":
		return calculatorUnit{"area", "mét vuông", 1}, true
	case "lít", "lit", "liter", "liters", "litre", "litres", "l":
		return calculatorUnit{"volume", "lít", 1}, true
	case "ml", "mililít", "mililit", "milliliter", "milliliters", "millilitre", "millilitres":
		return calculatorUnit{"volume", "ml", 0.001}, true
	case "ga lông", "ga lông mỹ", "gallon", "gallons", "gallon mỹ", "gal":
		return calculatorUnit{"volume", "ga lông", 3.785411784}, true
	case "độ f", "°f", "fahrenheit", "độ fahrenheit", "độ farenheit":
		return calculatorUnit{"temperature", "°F", 0}, true
	case "độ c", "°c", "celsius", "độ celsius":
		return calculatorUnit{"temperature", "°C", 0}, true
	default:
		return calculatorUnit{}, false
	}
}

func calculateDiscount(text string) (string, bool) {
	match := calcDiscount.FindStringSubmatch(text)
	if match == nil {
		return "", false
	}
	amount, ok := parseCalculatorNumber(match[1])
	if !ok || amount < 0 {
		return "", false
	}
	switch match[2] {
	case "nghìn", "ngàn":
		amount *= 1000
	case "triệu":
		amount *= 1_000_000
	case "tỷ", "tỉ":
		amount *= 1_000_000_000
	}
	percent, ok := parseCalculatorNumber(match[3])
	if !ok || percent < 0 || percent > 100 || !boundedCalculatorValue(amount) {
		return "", false
	}
	result := amount * (1 - percent/100)
	if !boundedCalculatorValue(result) {
		return "", false
	}
	return fmt.Sprintf("Còn %s sau khi giảm %s%%.", formatVietnameseNumber(result), formatVietnameseNumber(percent)), true
}

func calculateTNExpression(text string) (string, bool) {
	if strings.Contains(text, "=") {
		// Some speech clients append ", x bằng mấy" after the equation.
		if comma := strings.IndexByte(text, ','); comma >= 0 {
			text = strings.TrimSpace(text[:comma])
		}
		parts := strings.Split(text, "=")
		if len(parts) != 2 {
			return "", false
		}
		left, ok := parseCalculatorExpression(normalizeSpokenMath(parts[0], true))
		if !ok {
			return "", false
		}
		right, ok := parseCalculatorExpression(normalizeSpokenMath(parts[1], true))
		if !ok || (!left.hasX && !right.hasX) {
			return "", false
		}
		coefficient := left.x - right.x
		constant := right.constant - left.constant
		if math.Abs(coefficient) < 1e-12 {
			return "", false
		}
		answer := constant / coefficient
		if !boundedCalculatorValue(answer) {
			return "", false
		}
		return "x bằng " + formatVietnameseNumber(answer) + ".", true
	}

	text = strings.TrimSpace(text)
	for _, suffix := range []string{"bằng bao nhiêu", "bằng mấy", "là bao nhiêu", "là mấy"} {
		text = strings.TrimSuffix(text, suffix)
	}
	expression := normalizeSpokenMath(strings.TrimSpace(text), false)
	value, ok := parseCalculatorExpression(expression)
	if !ok || value.hasX || !value.hasOperator || !boundedCalculatorValue(value.constant) {
		return "", false
	}
	return "Kết quả là " + formatVietnameseNumber(value.constant) + ".", true
}

func normalizeSpokenMath(text string, equation bool) string {
	text = strings.ReplaceAll(text, "×", "*")
	text = strings.ReplaceAll(text, "nhân", "*")
	text = strings.ReplaceAll(text, "cộng", "+")
	text = strings.ReplaceAll(text, "trừ", "-")
	text = strings.ReplaceAll(text, "chia", "/")
	text = strings.ReplaceAll(text, "mũ", "^")
	text = strings.ReplaceAll(text, "phẩy", ".")
	if equation {
		text = calcImplicitX.ReplaceAllString(text, "$1*x")
	} else {
		text = calcSpokenX.ReplaceAllString(text, "*")
	}
	return strings.Join(strings.Fields(text), " ")
}

func parseCalculatorNumber(value string) (float64, bool) {
	value = strings.TrimSpace(value)
	if strings.Count(value, ",") == 1 && !strings.Contains(value, ".") {
		value = strings.Replace(value, ",", ".", 1)
	}
	parsed, err := strconv.ParseFloat(value, 64)
	return parsed, err == nil && boundedCalculatorValue(parsed)
}

func boundedCalculatorValue(value float64) bool {
	return !math.IsNaN(value) && !math.IsInf(value, 0) && math.Abs(value) <= 1e15
}

func formatVietnameseNumber(value float64) string {
	if math.Abs(value) < 0.0000005 {
		value = 0
	}
	text := strconv.FormatFloat(value, 'f', 6, 64)
	if strings.Contains(text, ".") {
		text = strings.TrimRight(strings.TrimRight(text, "0"), ".")
	}
	parts := strings.SplitN(text, ".", 2)
	integer := parts[0]
	start := 0
	if strings.HasPrefix(integer, "-") || strings.HasPrefix(integer, "+") {
		start = 1
	}
	for i := len(integer) - 3; i > start; i -= 3 {
		integer = integer[:i] + "." + integer[i:]
	}
	if len(parts) == 2 {
		return integer + "," + parts[1]
	}
	return integer
}

type calculatorLinear struct {
	constant    float64
	x           float64
	hasX        bool
	hasOperator bool
}

type calculatorParser struct {
	input string
	pos   int
	depth int
	steps int
}

func parseCalculatorExpression(input string) (calculatorLinear, bool) {
	if len(input) == 0 || len(input) > 256 {
		return calculatorLinear{}, false
	}
	p := calculatorParser{input: input}
	value, ok := p.expression()
	p.space()
	return value, ok && p.pos == len(p.input) && p.steps <= 128
}

func (p *calculatorParser) expression() (calculatorLinear, bool) {
	left, ok := p.term()
	if !ok {
		return calculatorLinear{}, false
	}
	for {
		p.space()
		if p.consume('+') {
			right, valid := p.term()
			if !valid {
				return calculatorLinear{}, false
			}
			left.constant += right.constant
			left.x += right.x
			left.hasX = left.hasX || right.hasX
			left.hasOperator = true
		} else if p.consume('-') {
			right, valid := p.term()
			if !valid {
				return calculatorLinear{}, false
			}
			left.constant -= right.constant
			left.x -= right.x
			left.hasX = left.hasX || right.hasX
			left.hasOperator = true
		} else {
			break
		}
		if !boundedCalculatorValue(left.constant) || !boundedCalculatorValue(left.x) {
			return calculatorLinear{}, false
		}
	}
	return left, true
}

func (p *calculatorParser) term() (calculatorLinear, bool) {
	left, ok := p.power()
	if !ok {
		return calculatorLinear{}, false
	}
	for {
		p.space()
		if p.consume('*') {
			right, valid := p.power()
			if !valid || (left.hasX && right.hasX) {
				return calculatorLinear{}, false
			}
			left = calculatorLinear{constant: left.constant * right.constant,
				x:    left.x*right.constant + right.x*left.constant,
				hasX: left.hasX || right.hasX, hasOperator: true}
		} else if p.consume('/') {
			right, valid := p.power()
			if !valid || right.hasX || right.constant == 0 {
				return calculatorLinear{}, false
			}
			left.constant /= right.constant
			left.x /= right.constant
			left.hasOperator = true
		} else {
			break
		}
		if !boundedCalculatorValue(left.constant) || !boundedCalculatorValue(left.x) {
			return calculatorLinear{}, false
		}
	}
	return left, true
}

func (p *calculatorParser) power() (calculatorLinear, bool) {
	left, ok := p.unary()
	if !ok {
		return calculatorLinear{}, false
	}
	p.space()
	if !p.consume('^') {
		return left, true
	}
	right, valid := p.unary()
	if !valid || right.hasX || right.constant < -10 || right.constant > 10 || math.Trunc(right.constant) != right.constant {
		return calculatorLinear{}, false
	}
	if left.hasX {
		if right.constant == 1 {
			left.hasOperator = true
			return left, true
		}
		if right.constant != 0 {
			return calculatorLinear{}, false
		}
		return calculatorLinear{constant: 1, hasOperator: true}, true
	}
	left.constant = math.Pow(left.constant, right.constant)
	left.hasOperator = true
	return left, boundedCalculatorValue(left.constant)
}

func (p *calculatorParser) unary() (calculatorLinear, bool) {
	p.space()
	if p.consume('+') {
		return p.unary()
	}
	if p.consume('-') {
		value, ok := p.unary()
		value.constant = -value.constant
		value.x = -value.x
		return value, ok && boundedCalculatorValue(value.constant) && boundedCalculatorValue(value.x)
	}
	return p.primary()
}

func (p *calculatorParser) primary() (calculatorLinear, bool) {
	p.space()
	if p.consume('(') {
		p.depth++
		if p.depth > 32 {
			return calculatorLinear{}, false
		}
		value, ok := p.expression()
		p.space()
		p.depth--
		if !ok || !p.consume(')') {
			return calculatorLinear{}, false
		}
		return value, true
	}
	if p.pos < len(p.input) && p.input[p.pos] == 'x' {
		p.pos++
		return calculatorLinear{x: 1, hasX: true}, true
	}
	start := p.pos
	dot := false
	for p.pos < len(p.input) {
		ch := p.input[p.pos]
		if ch >= '0' && ch <= '9' {
			p.pos++
			continue
		}
		if ch == '.' && !dot {
			dot = true
			p.pos++
			continue
		}
		break
	}
	if start == p.pos {
		return calculatorLinear{}, false
	}
	value, err := strconv.ParseFloat(p.input[start:p.pos], 64)
	if err != nil || !boundedCalculatorValue(value) {
		return calculatorLinear{}, false
	}
	p.steps++
	if p.steps > 128 {
		return calculatorLinear{}, false
	}
	return calculatorLinear{constant: value}, true
}

func (p *calculatorParser) consume(ch byte) bool {
	if p.pos < len(p.input) && p.input[p.pos] == ch {
		p.pos++
		p.steps++
		return p.steps <= 128
	}
	return false
}

func (p *calculatorParser) space() {
	for p.pos < len(p.input) && p.input[p.pos] == ' ' {
		p.pos++
	}
}
