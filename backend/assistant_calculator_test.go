package main

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestTNCalculatorExamples(t *testing.T) {
	for _, test := range []struct {
		name, input, want string
	}{
		{"precedence and spoken multiplication", "TN ơi, 20 x 2 + 15 x 3 bằng bao nhiêu", "Kết quả là 85."},
		{"power", "2 mũ 10 bằng mấy", "Kết quả là 1.024."},
		{"linear equation", "5 x + 2 = 12, x bằng mấy", "x bằng 2."},
		{"percentage reduction", "10 triệu giảm 15% còn bao nhiêu", "Còn 8.500.000 sau khi giảm 15%."},
		{"mile to meters", "1 dặm là bao nhiêu mét", "1 dặm bằng 1.609,344 mét."},
		{"inches to centimeters", "20 inch là bao nhiêu cm", "20 inch bằng 50,8 cm."},
		{"hectares to square meters", "2 hecta là bao nhiêu mét vuông", "2 hecta bằng 20.000 mét vuông."},
		{"liters to milliliters", "5 lít bằng bao nhiêu ml", "5 lít bằng 5.000 ml."},
		{"gallons to liters", "7 ga lông bằng bao nhiêu lít", "7 ga lông bằng 26,497882 lít."},
		{"Fahrenheit to Celsius", "84 độ F là mấy độ C", "84 °F bằng 28,888889 °C."},
	} {
		t.Run(test.name, func(t *testing.T) {
			got, ok := tnCalculatorAnswer(test.input)
			if !ok || got != test.want {
				t.Fatalf("tnCalculatorAnswer(%q) = (%q, %v), want (%q, true)", test.input, got, ok, test.want)
			}
		})
	}
}

func TestTNCalculatorRejectsUnsafeOrAmbiguousInput(t *testing.T) {
	for _, text := range []string{
		"mở bản đồ đến 20 x 2",
		"x + 1",
		"1 / 0",
		"2 mũ 100",
		"10 triệu giảm 150%",
		"1 dặm là bao nhiêu lít",
		"gửi email rồi tính 2 + 2",
	} {
		if answer, ok := tnCalculatorAnswer(text); ok {
			t.Errorf("unsupported or unsafe text %q became a calculator answer: %q", text, answer)
		}
	}
}

func TestTNCalculatorUnderstandsCompleteSpokenNumbers(t *testing.T) {
	for _, test := range []struct{ input, want string }{
		{"hai cộng hai bằng bao nhiêu", "Kết quả là 4."},
		{"TN ơi, hai mươi nhân hai cộng mười lăm nhân ba bằng mấy", "Kết quả là 85."},
		{"hai mũ mười bằng mấy", "Kết quả là 1.024."},
		{"một trăm linh năm trừ hai mươi mốt", "Kết quả là 84."},
		{"chín trăm chín mươi chín cộng một", "Kết quả là 1.000."},
		{"một dặm là bao nhiêu mét", "1 dặm bằng 1.609,344 mét."},
		{"hai hecta là bao nhiêu mét vuông", "2 hecta bằng 20.000 mét vuông."},
		{"mười triệu giảm mười lăm % còn bao nhiêu", "Còn 8.500.000 sau khi giảm 15%."},
	} {
		if got, ok := tnCalculatorAnswer(test.input); !ok || got != test.want {
			t.Errorf("spoken calculator %q = (%q,%v), want %q", test.input, got, ok, test.want)
		}
	}
	for _, text := range []string{"một hai cộng ba", "mười mốt cộng hai", "hai trăm linh cộng một", "hai mươi không cộng ba", "mở bản đồ đến hai cộng hai", "gọi cho hai cộng hai", "đừng tính hai cộng hai"} {
		if _, ok := tnCalculatorAnswer(text); ok {
			t.Errorf("ambiguous/non-math request must remain unsupported: %q", text)
		}
	}
}

func TestTNCalculatorRunsOnBasicAssistantPathAndReplaysWithoutProvider(t *testing.T) {
	s := testServer(t)
	var providerCalls atomic.Int32
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		providerCalls.Add(1)
		send(w, 200, Object{"utterance": "provider fallback", "directives": []Object{}})
	}))
	defer provider.Close()
	s.saveProviders(Object{"nlu": Object{"mode": "generic", "url": provider.URL}})
	account := guest(t, s)
	token := str(account, "access_token")
	input := Object{"text": "20 x 2 + 15 x 3 bằng bao nhiêu", "request_id": "tn-calculator", "mode": "commands"}
	code, response := call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, response)
	if str(response, "source") != "local-calculator" || str(response, "utterance") != "Kết quả là 85." {
		t.Fatalf("unexpected calculator response: %v", response)
	}
	directives, ok := response["directives"].([]any)
	if !ok || len(directives) != 1 || str(directives[0].(map[string]any), "type") != "SpeechSynthesizer" {
		t.Fatalf("calculator result did not use the Android speech directive: %v", response["directives"])
	}
	code, replay := call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, replay)
	if replay["replayed"] != true || providerCalls.Load() != 0 {
		t.Fatal("calculator replay repeated work or fell through to the provider")
	}
}
