package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type tnAITransport func(*http.Request) (*http.Response, error)

func (f tnAITransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func tnAIMockTransport(t *testing.T, fn tnAITransport) {
	t.Helper()
	previous := http.DefaultTransport
	http.DefaultTransport = fn
	t.Cleanup(func() { http.DefaultTransport = previous })
}

func tnAITestResponse(status int, body string) *http.Response {
	return &http.Response{StatusCode: status, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}
}

func tnAIFreeProvider(t *testing.T, s *Server) AIProvider {
	t.Helper()
	s.cfg.RuntimeMode = "independent"
	key, err := s.encryptAIKey("free-fixture", "TEST_ONLY_GEMINI_KEY")
	if err != nil {
		t.Fatal(err)
	}
	return AIProvider{ID: "free-fixture", Kind: "gemini", Model: independentGeminiModel, Endpoint: defaultAIEndpoint("gemini", independentGeminiModel), Enabled: true, MaxRequestsPerDay: 10, MaxOutputTokens: 1024, EncryptedKey: key, ProjectAlias: "fixture-project", QuotaGroup: "fixture-project", FreeTierVerified: true}
}

func tnAIErrorCode(err error) string {
	var problem *apiError
	if errors.As(err, &problem) {
		return problem.Code
	}
	return ""
}

func TestIndependentAIFreePolicyBlocksBeforeNetwork(t *testing.T) {
	s := testServer(t)
	p := tnAIFreeProvider(t, s)
	var requests atomic.Int32
	tnAIMockTransport(t, func(*http.Request) (*http.Response, error) { requests.Add(1); return tnAITestResponse(200, `{}`), nil })
	for _, change := range []func(*AIProvider){
		func(p *AIProvider) { p.FreeTierVerified = false },
		func(p *AIProvider) { p.ProjectAlias = ""; p.QuotaGroup = "" },
		func(p *AIProvider) { p.Endpoint = "https://unapproved.example/generate" },
		func(p *AIProvider) { p.Model = "unapproved-model" },
		func(p *AIProvider) { p.Kind = "custom" },
		func(p *AIProvider) { p.Enabled = false },
		func(p *AIProvider) { p.EncryptedKey = "" },
	} {
		candidate := p
		change(&candidate)
		if _, err := s.callAI(context.Background(), candidate, []aiMessage{{"user", "hello"}}, false); err == nil {
			t.Fatal("free policy accepted an unapproved inference")
		}
	}
	if _, _, err := s.callGeminiGrounded(context.Background(), p, []aiMessage{{"user", "weather"}}); tnAIErrorCode(err) != "AI_FREE_POLICY" {
		t.Fatal("free mode permitted search grounding", err)
	}
	if requests.Load() != 0 {
		t.Fatal("blocked configuration reached the network")
	}
}

func TestIndependentGeminiLowThinkingSkipsThoughtsAndSearch(t *testing.T) {
	s := testServer(t)
	p := tnAIFreeProvider(t, s)
	var requests atomic.Int32
	tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
		requests.Add(1)
		if r.URL.String() != p.Endpoint || r.Header.Get("x-goog-api-key") != "TEST_ONLY_GEMINI_KEY" {
			t.Error("incorrect endpoint or credential")
		}
		var payload Object
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		generation, _ := payload["generationConfig"].(map[string]any)
		thinking, _ := generation["thinkingConfig"].(map[string]any)
		if thinking["thinkingLevel"] != "LOW" || payload["tools"] != nil {
			t.Error("low thinking/no-search policy missing")
		}
		return tnAITestResponse(200, `{"candidates":[{"content":{"parts":[{"thought":true,"text":"PRIVATE_THOUGHT"},{"text":"Xin chào"}]}}]}`), nil
	})
	text, err := s.callAI(context.Background(), p, []aiMessage{{"user", "xin chào"}}, false)
	if err != nil || text != "Xin chào" {
		t.Fatal("generation response mismatch", text, err)
	}
	if err := s.put("config", "ai-providers", "", Object{"providers": []AIProvider{p}}); err != nil {
		t.Fatal(err)
	}
	if _, _, err = s.aiAnswerWithSources(context.Background(), []aiMessage{{"user", "thời tiết hôm nay"}}, false); tnAIErrorCode(err) != "LIVE_DATA_PROVIDER_REQUIRED" {
		t.Fatal("current data request used search grounding", err)
	}
	if requests.Load() != 1 {
		t.Fatal("live-data refusal called inference")
	}
}

func TestGeminiQuotaSharedAcrossCredentialsAndPacificReset(t *testing.T) {
	s := testServer(t)
	p := tnAIFreeProvider(t, s)
	p.MaxRequestsPerDay = 1
	q := p
	q.ID = "second-key"
	if err := s.put("config", "ai-providers", "", Object{"providers": []AIProvider{p, q}}); err != nil {
		t.Fatal(err)
	}
	if err := s.reserveAIUsage(p); err != nil {
		t.Fatal(err)
	}
	if err := s.reserveAIUsage(q); tnAIErrorCode(err) != "AI_DAILY_LIMIT" {
		t.Fatal("same-project credential multiplied quota", err)
	}
	// UTC midnight is not Google's Pacific midnight, including DST dates.
	before := time.Date(2026, 11, 1, 6, 59, 0, 0, time.UTC)
	after := before.Add(time.Minute)
	if aiUsageID(p, before) == aiUsageID(p, after) {
		t.Fatal("Pacific midnight did not reset date")
	}
	if aiUsageID(p, before.Add(-7*time.Hour)) != aiUsageID(p, before) {
		t.Fatal("quota reset at UTC midnight")
	}
	unknown := p
	unknown.ProjectAlias = ""
	unknown.ID = "unknown-one"
	unknown2 := unknown
	unknown2.ID = "unknown-two"
	unknown2.QuotaGroup = "pretend-independent"
	if aiQuotaIdentity(unknown) != aiQuotaIdentity(unknown2) {
		t.Fatal("unknown projects split quota by key or unverified group")
	}
	custom := AIProvider{ID: "local", Kind: "custom"}
	if aiUsageID(custom, time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)) != "local:2026-11-01" {
		t.Fatal("non-Gemini UTC accounting changed")
	}
}

func TestAIQuota429StopsFallbackAndPersistsCooldown(t *testing.T) {
	s := testServer(t)
	first := AIProvider{ID: "first", Kind: "custom", Model: "fixture", Endpoint: "https://fixture.invalid/first", Enabled: true, MaxRequestsPerDay: 10}
	second := first
	second.ID = "second"
	second.Endpoint = "https://fixture.invalid/second"
	second.Priority = 1
	if err := s.put("config", "ai-providers", "", Object{"providers": []AIProvider{first, second}}); err != nil {
		t.Fatal(err)
	}
	var requests atomic.Int32
	tnAIMockTransport(t, func(r *http.Request) (*http.Response, error) {
		requests.Add(1)
		response := tnAITestResponse(429, `{"error":{"message":"PRIVATE_QUOTA_PROJECT"}}`)
		response.Header.Set("Retry-After", "120")
		return response, nil
	})
	if _, err := s.aiAnswer(context.Background(), []aiMessage{{"user", "hello"}}, false); tnAIErrorCode(err) != "AI_PROJECT_QUOTA" {
		t.Fatal("429 was not preserved", err)
	}
	if requests.Load() != 1 {
		t.Fatal("429 triggered key/provider cycling")
	}
	if err := s.reserveAIUsage(first); tnAIErrorCode(err) != "AI_PROJECT_QUOTA" {
		t.Fatal("persisted cooldown was ignored", err)
	}
	s.aiMu.Lock()
	s.aiMu.Unlock()
	if saved, err := s.record("ai_cooldown", aiQuotaIdentity(first)); err != nil || strings.Contains(jsonText(saved), "PRIVATE_QUOTA_PROJECT") {
		t.Fatal("cooldown leaked or was not persisted", err)
	}
}

func TestAIProviderAtomicRevisionAndAuditFailureRollback(t *testing.T) {
	s := testServer(t)
	admin := adminToken(t, s)
	profile := Object{"id": "fixture", "kind": "custom", "model": "fixture", "endpoint": "http://127.0.0.1:9999", "enabled": false, "max_requests_per_day": 10, "max_output_tokens": 1024}
	status, response := call(t, s, "PUT", "/v1/admin/ai/providers", admin, Object{"base_revision": 0, "providers": []Object{profile}})
	requireStatus(t, 200, status, response)
	if integer(response, "revision", 0) != 1 {
		t.Fatal("revision missing")
	}
	status, response = call(t, s, "PUT", "/v1/admin/ai/providers", admin, Object{"base_revision": 0, "providers": []Object{}})
	requireStatus(t, 409, status, response)
	if _, err := s.db.Exec(`CREATE TRIGGER fixture_fail_ai_audit BEFORE INSERT ON records WHEN NEW.kind='audit' BEGIN SELECT RAISE(ABORT,'fixture'); END`); err != nil {
		t.Fatal(err)
	}
	status, _ = call(t, s, "PUT", "/v1/admin/ai/providers", admin, Object{"base_revision": 1, "providers": []Object{}})
	if status != 500 {
		t.Fatal("failed audit accepted config", status)
	}
	saved, err := s.aiProviders()
	if err != nil || len(saved) != 1 || s.aiConfigRevision() != 1 {
		t.Fatal("failed audit partially changed config", err)
	}
}

func TestAIProviderConcurrentRevisionAllowsOneWriter(t *testing.T) {
	s := testServer(t)
	admin := adminToken(t, s)
	var wg sync.WaitGroup
	var successes, conflicts atomic.Int32
	for range 2 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, _ := call(t, s, "PUT", "/v1/admin/ai/providers", admin, Object{"base_revision": 0, "providers": []Object{}})
			if status == 200 {
				successes.Add(1)
			} else if status == 409 {
				conflicts.Add(1)
			}
		}()
	}
	wg.Wait()
	if successes.Load() != 1 || conflicts.Load() != 1 {
		t.Fatal("revision compare-and-swap lost a concurrent write", successes.Load(), conflicts.Load())
	}
}

func TestIndependentAIProbeRequiresFreeVerificationAndRedactsPersistentState(t *testing.T) {
	s := testServer(t)
	p := tnAIFreeProvider(t, s)
	p.FreeTierVerified = false
	if err := s.put("config", "ai-providers", "", Object{"providers": []AIProvider{p}}); err != nil {
		t.Fatal(err)
	}
	var requests atomic.Int32
	tnAIMockTransport(t, func(*http.Request) (*http.Response, error) { requests.Add(1); return tnAITestResponse(200, `{}`), nil })
	if _, err := s.runAIProbe(context.Background(), p); tnAIErrorCode(err) != "AI_FREE_TIER_UNVERIFIED" {
		t.Fatal("unverified probe not blocked", err)
	}
	state := s.publicAIProviderState([]AIProvider{p})
	text := jsonText(state)
	if requests.Load() != 0 || !strings.Contains(text, `"status":"blocked"`) || strings.Contains(text, "TEST_ONLY_GEMINI_KEY") || strings.Contains(text, "encrypted_key") {
		t.Fatal("probe leaked credentials or dispatched unverified inference", text)
	}
}
