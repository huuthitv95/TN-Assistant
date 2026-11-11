package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestAssistantHistoryStoresFinalAIAnswerOnceAndKeepsOwnership(t *testing.T) {
	s := testServer(t)
	var cloudCalls atomic.Int32
	command := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		send(w, 200, Object{"utterance": "preliminary command response", "directives": []Object{}})
	}))
	defer command.Close()
	cloud := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cloudCalls.Add(1)
		send(w, 200, Object{"choices": []Object{{"message": Object{"content": "The final AI answer is 42"}}}})
	}))
	defer cloud.Close()
	if err := s.saveProviders(Object{"nlu": Object{"mode": "generic", "url": command.URL}}); err != nil {
		t.Fatal(err)
	}
	configureTestAI(t, s, cloud.URL)
	first, second := guest(t, s), guest(t, s)
	grantTestAI(t, s, first)
	grantTestAI(t, s, second)
	input := Object{"text": "TEST final history answer", "mode": "qa", "request_id": "same-request-id", "conversation_id": "same-conversation"}
	for _, account := range []Object{first, second} {
		token := str(account, "access_token")
		code, response := call(t, s, "POST", "/v1/assistant/text", token, input)
		requireStatus(t, 200, code, response)
		code, replay := call(t, s, "POST", "/v1/assistant/text", token, input)
		requireStatus(t, 200, code, replay)
		if replay["replayed"] != true {
			t.Fatal("retry did not replay")
		}
		code, history := call(t, s, "GET", "/v1/history", token, nil)
		requireStatus(t, 200, code, history)
		items := []any{}
		for _, item := range history["items"].([]any) {
			if str(item.(map[string]any), "kind") == "assistant" {
				items = append(items, item)
			}
		}
		if len(items) != 1 {
			t.Fatalf("one final history row required, got %d", len(items))
		}
		row := items[0].(map[string]any)
		result := row["result"].(map[string]any)
		if str(row, "text") != str(input, "text") || str(result, "utterance") != str(response, "utterance") || str(result, "source") != "ai" || str(result, "request_id") != str(input, "request_id") || result["replayed"] != false {
			t.Fatalf("history did not retain the final reply: %v", row)
		}
		if strings.Contains(jsonText(row), "preliminary command response") {
			t.Fatal("legacy preliminary response retained")
		}
	}
	if cloudCalls.Load() != 2 {
		t.Fatal("replay called cloud or account requests were merged")
	}
}

func TestAssistantHistoryPrivateAndFailedFallbackLeaveNoPreliminaryRows(t *testing.T) {
	s := testServer(t)
	command := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		send(w, 200, Object{"utterance": "preliminary command response", "directives": []Object{}})
	}))
	defer command.Close()
	cloud := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var input Object
		json.NewDecoder(r.Body).Decode(&input)
		if strings.Contains(jsonText(input), "failed answer") {
			w.WriteHeader(503)
			return
		}
		send(w, 200, Object{"choices": []Object{{"message": Object{"content": "private answer text"}}}})
	}))
	defer cloud.Close()
	s.saveProviders(Object{"nlu": Object{"mode": "generic", "url": command.URL}})
	configureTestAI(t, s, cloud.URL)
	account := guest(t, s)
	grantTestAI(t, s, account)
	token := str(account, "access_token")
	code, response := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "failed answer", "mode": "qa", "request_id": "failed-history"})
	requireStatus(t, 502, code, response)
	code, response = call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": true})
	requireStatus(t, 200, code, response)
	input := Object{"text": "private question text", "mode": "qa", "request_id": "private-history"}
	code, response = call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, response)
	if str(response, "utterance") != "private answer text" {
		t.Fatal("private caller did not receive answer")
	}
	code, response = call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, response)
	if response["replayed"] != true || str(response, "utterance") != "" {
		t.Fatal("private replay retained answer or became executable")
	}
	code, history := call(t, s, "GET", "/v1/history", token, nil)
	requireStatus(t, 200, code, history)
	for _, item := range history["items"].([]any) {
		if str(item.(map[string]any), "kind") == "assistant" {
			t.Fatal("private or failed fallback retained preliminary history")
		}
	}
	rows, err := s.db.Query("SELECT data FROM records WHERE kind IN ('assistant_request','ai_conversation')")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var data string
		rows.Scan(&data)
		if strings.Contains(data, "private question text") || strings.Contains(data, "private answer text") {
			t.Fatal("private content retained")
		}
	}
}

func TestAssistantHistoryCommandReplyAndReplayStayAtomic(t *testing.T) {
	s := testServer(t)
	command := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		send(w, 200, Object{"utterance": "command accepted", "directives": []Object{{"type": "PlayerVolume", "payload": Object{"command": "up"}}}})
	}))
	defer command.Close()
	s.saveProviders(Object{"nlu": Object{"mode": "generic", "url": command.URL}})
	account := guest(t, s)
	token := str(account, "access_token")
	input := Object{"text": "TEST volume command", "mode": "commands", "request_id": "command-history"}
	code, response := call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, response)
	code, response = call(t, s, "POST", "/v1/assistant/text", token, input)
	requireStatus(t, 200, code, response)
	var count int
	s.db.QueryRow("SELECT count(*) FROM records WHERE kind='history'").Scan(&count)
	if count != 1 {
		t.Fatalf("command retry created %d history rows", count)
	}
}

func TestAssistantRemovedReservationCannotCreateOrphanHistory(t *testing.T) {
	s := testServer(t)
	command := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Account deletion and maintenance can remove the reserved record while
		// an upstream request is in flight. Completion must not recreate it.
		s.db.Exec("DELETE FROM records WHERE kind='assistant_request'")
		send(w, 200, Object{"utterance": "fixture answer after removal", "directives": []Object{}})
	}))
	defer command.Close()
	s.saveProviders(Object{"nlu": Object{"mode": "generic", "url": command.URL}})
	account := guest(t, s)
	code, response := call(t, s, "POST", "/v1/assistant/text", str(account, "access_token"), Object{"text": "fixture removed request", "mode": "commands", "request_id": "removed-history"})
	requireStatus(t, 409, code, response)
	if str(response["error"].(map[string]any), "code") != "REQUEST_RESERVATION_LOST" {
		t.Fatal("removed request returned a successful final answer")
	}
	var count int
	s.db.QueryRow("SELECT count(*) FROM records WHERE kind IN ('assistant_request','history')").Scan(&count)
	if count != 0 {
		t.Fatal("removed request created orphan history or replay data")
	}
}

func TestPrivateModeTransitionDuringGenerationNeverPersistsThatTurnsSpeech(t *testing.T) {
	for _, scenario := range []struct {
		name                    string
		initiallyPrivate, pulse bool
	}{
		{"disabled_while_waiting", true, false}, {"enabled_while_waiting", false, false}, {"enabled_then_disabled_while_waiting", false, true},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			s := independentTestServer(t)
			provider := tnAIFreeProvider(t, s)
			if err := s.put("config", "ai-providers", "", Object{"providers": []AIProvider{provider}}); err != nil {
				t.Fatal(err)
			}
			started, release := make(chan struct{}), make(chan struct{})
			var closeRelease sync.Once
			t.Cleanup(func() { closeRelease.Do(func() { close(release) }) })
			tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
				close(started)
				<-release
				return tnAITestResponse(200, `{"candidates":[{"content":{"parts":[{"text":"PRIVATE_INFLIGHT_ANSWER"}]}}]}`), nil
			})
			account := guest(t, s)
			grantTestAI(t, s, account)
			token := str(account, "access_token")
			status, response := call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": scenario.initiallyPrivate})
			requireStatus(t, 200, status, response)
			type result struct {
				status int
				body   Object
			}
			completed := make(chan result, 1)
			go func() {
				status, body := call(t, s, "POST", "/v1/assistant/text", token, Object{"text": "Giải thích PRIVATE_INFLIGHT_QUESTION", "mode": "qa", "request_id": "private-inflight", "conversation_id": "private-context"})
				completed <- result{status, body}
			}()
			select {
			case <-started:
			case <-time.After(5 * time.Second):
				t.Fatal("AI request did not start")
			}
			status, response = call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": !scenario.initiallyPrivate})
			requireStatus(t, 200, status, response)
			if scenario.pulse {
				status, response = call(t, s, "PUT", "/v1/settings", token, Object{"private_mode": false})
				requireStatus(t, 200, status, response)
			}
			closeRelease.Do(func() { close(release) })
			select {
			case out := <-completed:
				requireStatus(t, 200, out.status, out.body)
				if str(out.body, "utterance") != "PRIVATE_INFLIGHT_ANSWER" {
					t.Fatal("private answer was lost in transit")
				}
			case <-time.After(5 * time.Second):
				t.Fatal("AI request did not finish")
			}
			rows, err := s.db.Query("SELECT data FROM records WHERE kind IN ('history','assistant_request','ai_conversation')")
			if err != nil {
				t.Fatal(err)
			}
			defer rows.Close()
			for rows.Next() {
				var raw string
				if err := rows.Scan(&raw); err != nil {
					t.Fatal(err)
				}
				if strings.Contains(raw, "PRIVATE_INFLIGHT_QUESTION") || strings.Contains(raw, "PRIVATE_INFLIGHT_ANSWER") {
					t.Fatal("privacy transition persisted the in-flight question/answer")
				}
			}
		})
	}
}

func TestAIContextAndHistoryRollbackTogetherWhenCompletionCannotCommit(t *testing.T) {
	s := independentTestServer(t)
	provider := tnAIFreeProvider(t, s)
	if err := s.put("config", "ai-providers", "", Object{"providers": []AIProvider{provider}}); err != nil {
		t.Fatal(err)
	}
	tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
		return tnAITestResponse(200, `{"candidates":[{"content":{"parts":[{"text":"ROLLBACK_CONTEXT_ANSWER"}]}}]}`), nil
	})
	account := guest(t, s)
	grantTestAI(t, s, account)
	var baselineHistory int
	if err := s.db.QueryRow("SELECT count(*) FROM records WHERE kind='history'").Scan(&baselineHistory); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("CREATE TRIGGER reject_final_history BEFORE INSERT ON records WHEN NEW.kind='history' BEGIN SELECT RAISE(ABORT,'TEST rejected history'); END"); err != nil {
		t.Fatal(err)
	}
	input := Object{"text": "Giải thích rollback", "mode": "qa", "request_id": "rollback-context", "conversation_id": "rollback-conversation"}
	status, response := call(t, s, "POST", "/v1/assistant/text", str(account, "access_token"), input)
	requireStatus(t, 500, status, response)
	var contexts, history int
	if err := s.db.QueryRow("SELECT count(*) FROM records WHERE kind='ai_conversation'").Scan(&contexts); err != nil {
		t.Fatal(err)
	}
	if err := s.db.QueryRow("SELECT count(*) FROM records WHERE kind='history'").Scan(&history); err != nil {
		t.Fatal(err)
	}
	if contexts != 0 || history != baselineHistory {
		t.Fatal("AI context survived a rolled-back final history transaction")
	}
	status, response = call(t, s, "POST", "/v1/assistant/text", str(account, "access_token"), input)
	requireStatus(t, 409, status, response)
}
