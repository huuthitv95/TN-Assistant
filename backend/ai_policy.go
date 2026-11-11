package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata" // Pacific quota resets must work on a minimal VPS/Windows.
)

const independentGeminiModel = "gemini-3.8-flash"

func (s *Server) aiConfigRevision() int {
	m, err := s.record("config", "ai-providers")
	if err != nil {
		return 0
	}
	return integer(m, "revision", 0)
}

func (s *Server) checkAIConfigRevision(m Object, revision int) error {
	v, supplied := m["base_revision"]
	if !supplied && !s.independentRuntime() {
		return nil
	}
	n, ok := v.(float64)
	if !ok || math.Trunc(n) != n || n < 0 {
		return problem(400, "CONFIG_REVISION_REQUIRED", "Reload the saved configuration before editing")
	}
	if n != float64(revision) {
		return problem(409, "CONFIG_CONFLICT", "Configuration changed; retain your draft and reload before saving")
	}
	return nil
}

func (s *Server) validateAIConfigPolicy(p *AIProvider) error {
	p.ProjectAlias = strings.TrimSpace(p.ProjectAlias)
	if p.ProjectAlias != "" && !aiIdentifier.MatchString(p.ProjectAlias) {
		return problem(400, "INVALID_PROJECT_ALIAS", "Use a project alias with letters, numbers, dots, dashes or underscores")
	}
	if p.Kind == "gemini" {
		group := p.ProjectAlias
		if group == "" {
			group = "unknown-shared"
		}
		if p.QuotaGroup != "" && p.QuotaGroup != group {
			return problem(400, "INVALID_QUOTA_GROUP", "Gemini credentials in one project must share its quota group")
		}
		p.QuotaGroup = group
	}
	if s.independentRuntime() && p.Enabled {
		if p.Kind != "gemini" || p.Model != independentGeminiModel || p.Endpoint != defaultAIEndpoint("gemini", independentGeminiModel) {
			return problem(400, "AI_FREE_POLICY", "Independent free-only mode permits only the approved Gemini model and official generation endpoint")
		}
	}
	if p.FreeTierVerified && (p.Kind != "gemini" || p.ProjectAlias == "") {
		return problem(400, "FREE_TIER_PROJECT_REQUIRED", "Identify the verified free-tier project before enabling its credential")
	}
	return nil
}

func (s *Server) guardAIInference(p AIProvider) error {
	if !s.independentRuntime() {
		return nil
	}
	if err := s.validateAIConfigPolicy(&p); err != nil {
		return err
	}
	// Validate again even for direct calls and disabled/stale persisted profiles.
	if !p.Enabled || p.Kind != "gemini" || p.Model != independentGeminiModel || p.Endpoint != defaultAIEndpoint("gemini", independentGeminiModel) {
		return problem(503, "AI_FREE_POLICY", "This AI route is disabled by the independent free-only policy")
	}
	if !p.FreeTierVerified || p.ProjectAlias == "" {
		return problem(503, "AI_FREE_TIER_UNVERIFIED", "Owner verification of the project's free tier is required before inference")
	}
	if p.EncryptedKey == "" {
		return problem(503, "AI_KEY_REQUIRED", "Configure an encrypted server credential before inference")
	}
	return nil
}

func (s *Server) commitAIProviders(providers []AIProvider, revision int, actor string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var raw string
	err = tx.QueryRow("SELECT data FROM records WHERE kind='config' AND id='ai-providers'").Scan(&raw)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	// The process lock covers other writers; this check also catches an external
	// maintenance change before the transaction acquired SQLite's connection.
	if raw != "" {
		var current Object
		if err = json.Unmarshal([]byte(raw), &current); err != nil {
			return err
		}
		if integer(current, "revision", 0) != revision-1 {
			return problem(409, "CONFIG_CONFLICT", "Configuration changed before commit")
		}
	}
	data := Object{"providers": providers, "revision": revision}
	_, err = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('config','ai-providers','',?,?) ON CONFLICT(kind,id) DO UPDATE SET data=excluded.data", jsonText(data), now())
	if err != nil {
		return err
	}
	_, err = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('audit',?,?,?,?)", ident(), actor, jsonText(Object{"action": "ai.providers.update", "revision": revision, "provider_count": len(providers), "created_at": now()}), now())
	if err != nil {
		return err
	}
	return tx.Commit()
}

func aiQuotaIdentity(p AIProvider) string {
	if p.Kind != "gemini" {
		return "provider:" + p.ID
	}
	group := p.ProjectAlias
	if group == "" {
		group = "unknown-shared"
	}
	return "gemini:" + group + ":" + p.Model
}

func aiQuotaClock(clock time.Time) time.Time {
	zone, err := time.LoadLocation("America/Los_Angeles")
	if err != nil {
		return clock.UTC()
	}
	return clock.In(zone)
}

func aiUsageID(p AIProvider, clock time.Time) string {
	if p.Kind != "gemini" {
		return p.ID + ":" + clock.UTC().Format("2006-01-02")
	}
	return aiQuotaIdentity(p) + ":" + aiQuotaClock(clock).Format("2006-01-02")
}

func (s *Server) aiGroupDailyLimit(p AIProvider) int {
	limit := p.MaxRequestsPerDay
	if limit <= 0 {
		limit = 100
	}
	providers, err := s.aiProviders()
	if err != nil {
		return limit
	}
	for _, other := range providers {
		if other.Enabled && aiQuotaIdentity(other) == aiQuotaIdentity(p) && other.MaxRequestsPerDay > 0 && other.MaxRequestsPerDay < limit {
			limit = other.MaxRequestsPerDay
		}
	}
	return limit
}

func (s *Server) markAIQuotaCooldown(p AIProvider, retryAfter string) error {
	s.aiMu.Lock()
	defer s.aiMu.Unlock()
	clock := time.Now()
	day := aiQuotaClock(clock)
	until := time.Date(day.Year(), day.Month(), day.Day()+1, 0, 0, 0, 0, day.Location())
	// Without an unambiguous upstream retry time, daily exhaustion is handled
	// conservatively until the next reset. No key/project cycling follows 429.
	if seconds, err := strconv.Atoi(retryAfter); err == nil && seconds >= 0 && seconds <= 86400 {
		until = clock.Add(time.Duration(max(seconds, 60)) * time.Second)
	} else if date, err := http.ParseTime(retryAfter); err == nil && date.After(clock) && date.Before(clock.Add(24*time.Hour)) {
		until = date
	}
	return s.put("ai_cooldown", aiQuotaIdentity(p), "", Object{"until": until.UTC().Format(time.RFC3339Nano), "reason": "AI_PROJECT_QUOTA", "observed_at": now()})
}

func aiProbeIdentity(p AIProvider) string {
	return p.ID + ":" + hash(p.Kind+"\x00"+p.Endpoint+"\x00"+p.Model+"\x00"+p.EncryptedKey+"\x00"+p.ProjectAlias+"\x00"+strconv.FormatBool(p.FreeTierVerified))
}

func (s *Server) runAIProbe(ctx context.Context, p AIProvider) (Object, error) {
	s.aiMu.Lock()
	current, readErr := s.aiProviders()
	if readErr != nil {
		s.aiMu.Unlock()
		return nil, readErr
	}
	found := false
	for _, item := range current {
		if item.ID == p.ID && aiProbeIdentity(item) == aiProbeIdentity(p) {
			p, found = item, true
			break
		}
	}
	if !found {
		s.aiMu.Unlock()
		return nil, problem(409, "CONFIG_CONFLICT", "The saved credential changed before its check; reload configuration")
	}
	identity := aiProbeIdentity(p)
	previous, err := s.record("ai_probe", identity)
	if err == nil && str(previous, "status") == "checking" {
		if started, parseErr := time.Parse(time.RFC3339Nano, str(previous, "checked_at")); parseErr == nil && time.Since(started) < 40*time.Second {
			s.aiMu.Unlock()
			return nil, problem(409, "AI_PROBE_PENDING", "A check of this saved credential is already in progress")
		}
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		s.aiMu.Unlock()
		return nil, err
	}
	err = s.guardAIInference(p)
	if err == nil {
		err = s.reserveAIUsageAt(p, time.Now())
	}
	if err == nil {
		err = s.put("ai_probe", identity, "", Object{"status": "checking", "checked_at": now()})
	}
	s.aiMu.Unlock()
	if err != nil {
		s.saveAIProbeResult(identity, time.Now(), err)
		return nil, err
	}
	bounded, cancel := context.WithTimeout(ctx, 35*time.Second)
	defer cancel()
	start := time.Now()
	_, err = s.callAI(bounded, p, []aiMessage{{"user", "Reply only: TN ready"}}, false)
	result, saveErr := s.saveAIProbeResult(identity, start, err)
	if saveErr != nil {
		return nil, saveErr
	}
	if err != nil {
		return nil, err
	}
	result["ok"], result["id"] = true, p.ID
	return result, nil
}

func (s *Server) saveAIProbeResult(identity string, start time.Time, err error) (Object, error) {
	result := Object{"status": "healthy", "checked_at": now(), "latency_ms": time.Since(start).Milliseconds()}
	if err != nil {
		result["status"], result["code"] = "failed", "AI_UNAVAILABLE"
		var api *apiError
		if errors.As(err, &api) {
			// Only our bounded codes are persisted; upstream messages and bodies
			// can contain account data and are never part of probe records.
			result["code"] = api.Code
			if api.Status == 429 {
				result["status"] = "quota_exhausted"
			}
			if api.Code == "AI_FREE_TIER_UNVERIFIED" || api.Code == "AI_FREE_POLICY" || api.Code == "AI_KEY_REQUIRED" {
				result["status"] = "blocked"
			}
			if api.Code == "AI_TIMEOUT" {
				result["status"] = "unknown"
			}
		}
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
			result["status"], result["code"] = "unknown", "AI_PROBE_INTERRUPTED"
		}
	}
	return result, s.put("ai_probe", identity, "", result)
}

func (s *Server) publicAIProviderState(providers []AIProvider) Object {
	out := publicAIProviders(providers)
	out["revision"], out["independent"] = s.aiConfigRevision(), s.independentRuntime()
	states := Object{}
	for _, p := range providers {
		state := Object{"quota_group": aiQuotaIdentity(p), "local_daily_limit": s.aiGroupDailyLimit(p), "usage_day": strings.TrimPrefix(aiUsageID(p, time.Now()), aiQuotaIdentity(p)+":"), "reset_timezone": "UTC"}
		if p.Kind == "gemini" {
			state["reset_timezone"] = "America/Los_Angeles"
		}
		if usage, err := s.record("ai_usage", aiUsageID(p, time.Now())); err == nil {
			state["requests_reserved"] = integer(usage, "count", 0)
		} else {
			state["requests_reserved"] = 0
		}
		if cooldown, err := s.record("ai_cooldown", aiQuotaIdentity(p)); err == nil {
			state["cooldown_until"] = cooldown["until"]
		}
		if probe, err := s.record("ai_probe", aiProbeIdentity(p)); err == nil {
			delete(probe, "id")
			state["probe"] = probe
		}
		states[p.ID] = state
	}
	out["states"] = states
	return out
}
