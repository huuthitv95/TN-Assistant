package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// RuntimeConfig contains only non-secret operational settings. Mode, cost policy,
// generation and revision cannot be selected by an API client.
type RuntimeConfig struct {
	Revision         int    `json:"revision"`
	Generation       int    `json:"generation"`
	RuntimeMode      string `json:"runtime_mode"`
	CostPolicy       string `json:"cost_policy"`
	LegacyFallback   bool   `json:"legacy_fallback"`
	ASRURL           string `json:"asr_url"`
	TTSURL           string `json:"tts_url"`
	TTSVoice         string `json:"tts_voice"`
	MaxDevices       int    `json:"max_devices"`
	SpeechQueueLimit int    `json:"speech_queue_limit"`
	DrivingEnabled   bool   `json:"driving_enabled"`
	NewsEnabled      bool   `json:"news_enabled"`
}

func defaultRuntimeConfig() RuntimeConfig {
	return RuntimeConfig{Revision: 1, Generation: 1, RuntimeMode: "independent", CostPolicy: "free_only", ASRURL: "http://127.0.0.1:8091", TTSURL: "http://127.0.0.1:8092", TTSVoice: "Thùy Dung", MaxDevices: 100, SpeechQueueLimit: 4}
}

func (s *Server) independentRuntime() bool {
	if s.cfg.RuntimeMode == "independent" {
		return true
	}
	var mode string
	return s.db.QueryRow("SELECT json_extract(data,'$.runtime_mode') FROM records WHERE kind='config' AND id='runtime'").Scan(&mode) == nil && mode == "independent"
}

func (s *Server) runtimeConfig() (RuntimeConfig, error) {
	var raw string
	err := s.db.QueryRow("SELECT data FROM records WHERE kind='config' AND id='runtime'").Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		return defaultRuntimeConfig(), nil
	}
	if err != nil {
		return RuntimeConfig{}, err
	}
	var cfg RuntimeConfig
	if err = json.Unmarshal([]byte(raw), &cfg); err != nil {
		return cfg, err
	}
	if err = validateRuntimeConfig(cfg); err != nil {
		return cfg, err
	}
	return cfg, nil
}

func validateRuntimeConfig(c RuntimeConfig) error {
	if c.RuntimeMode != "independent" || c.CostPolicy != "free_only" || c.LegacyFallback {
		return problem(400, "RUNTIME_POLICY_IMMUTABLE", "Independent runtime and free-only policy are required; legacy fallback is disabled")
	}
	if c.Revision < 1 || c.Generation < 1 {
		return problem(400, "INVALID_RUNTIME_CONFIG", "Invalid runtime revision or generation")
	}
	for name, value := range map[string]string{"asr_url": c.ASRURL, "tts_url": c.TTSURL} {
		port := "8091"
		if name == "tts_url" {
			port = "8092"
		}
		u, err := url.Parse(value)
		if err != nil || u.Scheme != "http" || u.User != nil || u.Host != "127.0.0.1:"+port || u.Path != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.Opaque != "" {
			return problem(400, "INVALID_SPEECH_ENDPOINT", "Speech workers require their fixed loopback HTTP endpoints")
		}
	}
	if c.TTSVoice != "Thùy Dung" {
		return problem(400, "INVALID_TTS_VOICE", "The installed Vietnamese model is pinned to the Thùy Dung voice")
	}
	if c.MaxDevices < 1 || c.MaxDevices > 100 {
		return problem(400, "INVALID_FLEET_LIMIT", "max_devices must be 1..100")
	}
	if c.SpeechQueueLimit != 4 {
		return problem(400, "INVALID_SPEECH_QUEUE", "The speech queue is fixed at four waiting requests")
	}
	return nil
}

// The first independent boot retires executable legacy responses, preserving
// fingerprints as tombstones so retries cannot re-execute former actions.
func (s *Server) initializeIndependentRuntime() error {
	if !s.independentRuntime() {
		return nil
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var raw string
	err = tx.QueryRow("SELECT data FROM records WHERE kind='config' AND id='runtime'").Scan(&raw)
	if err == nil {
		var cfg RuntimeConfig
		if err = json.Unmarshal([]byte(raw), &cfg); err != nil {
			return err
		}
		if err = validateRuntimeConfig(cfg); err != nil {
			return err
		}
		return tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	cfg := defaultRuntimeConfig()
	if _, err = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('config','runtime','',?,?)", jsonText(cfg), now()); err != nil {
		return err
	}
	for _, statement := range []string{
		`UPDATE records SET data=json_set(json_remove(data,'$.result','$.nlu_provider_binding'),'$.status','runtime_retired','$.retired_generation',1) WHERE kind='assistant_request'`,
		`UPDATE records SET data=json_set(json_remove(data,'$.session_key','$.result'),'$.status','runtime_retired','$.retired_generation',1) WHERE kind IN ('nlu_session','assistant_location')`,
		`UPDATE records SET data=json_set(json_remove(data,'$.directive'),'$.status','runtime_retired','$.retired_generation',1) WHERE kind='ai_confirmation'`,
	} {
		if _, err = tx.Exec(statement); err != nil {
			return err
		}
	}
	if err = runtimeAudit(tx, "system", "runtime_initialized", Object{"revision": cfg.Revision, "generation": cfg.Generation}); err != nil {
		return err
	}
	return tx.Commit()
}

func runtimeAudit(tx *sql.Tx, actor, action string, data Object) error {
	id := ident()
	entry := Object{"id": id, "actor": actor, "action": action, "data": data, "created_at": now()}
	_, err := tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('runtime_audit',?,?,?,?)", id, actor, jsonText(entry), entry["created_at"])
	return err
}

func (s *Server) adminRuntimeConfig(w http.ResponseWriter, r *http.Request, p *Principal) error {
	if r.Method == "GET" {
		cfg, err := s.runtimeConfig()
		if err != nil {
			return err
		}
		send(w, 200, cfg)
		return nil
	}
	if r.Method != "PUT" {
		return problem(405, "METHOD_NOT_ALLOWED", "GET/PUT required")
	}
	m, err := body(r)
	if err != nil {
		return err
	}
	if len(m) != 2 || m["base_revision"] == nil || m["config"] == nil {
		return problem(400, "INVALID_RUNTIME_CONFIG", "base_revision and complete config are required")
	}
	base, valid := m["base_revision"].(float64)
	if !valid || base < 1 || base != float64(int(base)) {
		return problem(400, "BASE_REVISION_REQUIRED", "An integer base_revision is required")
	}
	input, valid := m["config"].(map[string]any)
	if !valid {
		return problem(400, "INVALID_RUNTIME_CONFIG", "Complete config object required")
	}
	// Required keys and a strict decoder prevent partial/unknown updates from
	// silently resetting booleans or introducing unreviewed operational settings.
	for _, key := range []string{"runtime_mode", "cost_policy", "legacy_fallback", "asr_url", "tts_url", "tts_voice", "max_devices", "speech_queue_limit", "driving_enabled", "news_enabled"} {
		if _, ok := input[key]; !ok {
			return problem(400, "INCOMPLETE_RUNTIME_CONFIG", "Complete runtime config is required")
		}
	}
	for _, key := range []string{"legacy_fallback", "driving_enabled", "news_enabled"} {
		if _, valid := input[key].(bool); !valid {
			return problem(400, "INVALID_RUNTIME_CONFIG", "Runtime switches must be boolean")
		}
	}
	var next RuntimeConfig
	decoder := json.NewDecoder(strings.NewReader(jsonText(input)))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&next); err != nil {
		return problem(400, "INVALID_RUNTIME_CONFIG", "Runtime config has unknown fields or invalid value types")
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var raw string
	if err = tx.QueryRow("SELECT data FROM records WHERE kind='config' AND id='runtime'").Scan(&raw); err != nil {
		return problem(409, "RUNTIME_NOT_INITIALIZED", "Independent runtime must be initialized before editing")
	}
	var previous RuntimeConfig
	if err = json.Unmarshal([]byte(raw), &previous); err != nil {
		return err
	}
	if previous.Revision != int(base) {
		return problem(409, "RUNTIME_REVISION_CONFLICT", "Runtime configuration changed; reload before saving")
	}
	if (next.Revision != 0 && next.Revision != previous.Revision) || (next.Generation != 0 && next.Generation != previous.Generation) {
		return problem(400, "RUNTIME_REVISION_IMMUTABLE", "Revision and generation are managed by the server")
	}
	next.Revision, next.Generation = previous.Revision+1, previous.Generation
	if err = validateRuntimeConfig(next); err != nil {
		return err
	}
	var devices int
	if err = tx.QueryRow("SELECT count(*) FROM devices").Scan(&devices); err != nil {
		return err
	}
	if next.MaxDevices < devices {
		return problem(409, "FLEET_LIMIT_IN_USE", "max_devices cannot be below the current device count")
	}
	if _, err = tx.Exec("UPDATE records SET data=? WHERE kind='config' AND id='runtime'", jsonText(next)); err != nil {
		return err
	}
	if err = runtimeAudit(tx, str(p.User, "id"), "runtime_config_updated", Object{"before": previous, "after": next}); err != nil {
		return err
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	send(w, 200, next)
	return nil
}

// Configuration is deliberately not a health signal. A worker must affirm
// readiness after loading its model, and a negative readiness flag always wins.
func speechWorkerHealth(ctx context.Context, endpoint string) Object {
	check := Object{"healthy": false, "status": "unavailable"}
	request, err := http.NewRequestWithContext(ctx, "GET", endpoint+"/health", nil)
	if err != nil {
		return check
	}
	client := &http.Client{Timeout: 900 * time.Millisecond, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return check
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		check["status"] = "unhealthy"
		return check
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, 64<<10))
	var health Object
	if err != nil || json.Unmarshal(raw, &health) != nil {
		check["status"] = "invalid_health"
		return check
	}
	ready, explicit := health["ready"].(bool)
	if !explicit {
		loaded, _ := health["model_loaded"].(bool)
		ready = loaded && allowedType(strings.ToLower(str(health, "status")), []string{"ok", "ready", "healthy"})
	}
	if loaded, present := health["model_loaded"].(bool); present && !loaded {
		ready = false
	}
	if allowedType(strings.ToLower(str(health, "status")), []string{"error", "failed", "unhealthy", "not_ready", "loading"}) {
		ready = false
	}
	if dependencies, ok := health["checks"].(map[string]any); ok {
		for _, value := range dependencies {
			if value == false {
				ready = false
			}
			if check, ok := value.(map[string]any); ok {
				if healthy, exists := check["healthy"].(bool); exists && !healthy {
					ready = false
				}
			}
		}
	}
	check["healthy"] = ready
	check["status"] = "not_ready"
	if ready {
		check["status"] = "ready"
	}
	for _, field := range []string{"model_loaded", "queue_depth", "queue_limit"} {
		if value, exists := health[field]; exists {
			check[field] = value
		}
	}
	return check
}

func (s *Server) runtimeReadiness(ctx context.Context) Object {
	checks := Object{}
	ready := s.independentRuntime()
	checks["runtime"] = Object{"healthy": ready, "status": "independent_required"}
	if ready {
		checks["runtime"] = Object{"healthy": true, "status": "independent"}
		if originalTTSSelected() {
			checks["runtime"] = Object{"healthy": true, "status": "tn_with_original_tts", "external_dependency": true}
		}
	}
	dbHealthy := s.db.PingContext(ctx) == nil
	checks["database"] = Object{"healthy": dbHealthy}
	ready = ready && dbHealthy
	cfg, err := s.runtimeConfig()
	if err != nil {
		return Object{"ready": false, "status": "not_ready", "checks": checks}
	}
	var wg sync.WaitGroup
	var asr, tts Object
	wg.Add(2)
	go func() { defer wg.Done(); asr = s.speechProviderHealth(ctx, "asr", cfg) }()
	go func() { defer wg.Done(); tts = s.speechProviderHealth(ctx, "tts", cfg) }()
	wg.Wait()
	checks["asr"], checks["tts"] = asr, tts
	ready = ready && asr["healthy"] == true && tts["healthy"] == true
	status := "not_ready"
	if ready {
		status = "ready"
	}
	dependencies := []string{}
	if originalTTSSelected() {
		dependencies = append(dependencies, "original_tts")
	}
	return Object{"ready": ready, "status": status, "revision": cfg.Revision, "generation": cfg.Generation, "checks": checks, "external_dependencies": dependencies}
}

func (s *Server) runtimeCapabilities() (Object, error) {
	cfg, err := s.runtimeConfig()
	if err != nil {
		return nil, err
	}
	items := []Object{}
	for _, item := range []struct{ id, status, reason string }{
		{"tn_auth", "AVAILABLE", "tn_owned_runtime"},
		{"weather", "CONFIGURED", "met_norway_proxy_cache_ready_upstream_not_checked"},
		{"commands", "PARTIAL", "tn_command_router_supported_intents_only"},
		{"device_events", "AVAILABLE", "tn_owned_runtime"},
		{"action_results", "AVAILABLE", "tn_owned_runtime"},
		{"calculator", "AVAILABLE", "tn_owned_runtime"},
		{"navigation", "CLIENT_VALIDATION_REQUIRED", "android_intent_not_yet_accepted"},
		{"local_media", "CLIENT_VALIDATION_REQUIRED", "android_mediasession_not_yet_accepted"},
		{"contacts", "CLIENT_VALIDATION_REQUIRED", "android_contact_resolver_requires_permission_and_confirmation"},
	} {
		items = append(items, Object{"id": item.id, "status": item.status, "reason": item.reason})
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for _, item := range []struct {
		id string
	}{
		{"asr"},
		{"tts"},
	} {
		check := s.speechProviderHealth(ctx, item.id, cfg)
		status, reason := "NOT_READY", str(check, "status")
		if check["healthy"] == true {
			status, reason = "READY", "provider_inference_succeeded_within_24h"
			if check["provider"] == "local" {
				reason = "pinned_local_model_loaded"
			}
		} else if reason == "" {
			reason = "worker_readiness_required"
		}
		items = append(items, Object{"id": item.id, "status": status, "reason": reason, "provider": check["provider"], "speaker_id": check["speaker_id"], "external_dependency": check["external_dependency"] == true})
	}
	for _, id := range []string{"traffic_violations", "lottery", "gold", "exchange_rates", "external_video", "zalo_calls"} {
		items = append(items, Object{"id": id, "status": "BLOCKED", "reason": "legal_free_api_required"})
	}
	for _, item := range []struct {
		id       string
		enabled  bool
		dataKind string
	}{{"driving", cfg.DrivingEnabled, "managed_roads"}, {"news", cfg.NewsEnabled, "managed_news"}} {
		status, reason := "DISABLED", "disabled_by_admin"
		if item.enabled {
			status, reason = "NOT_READY", "enabled_but_licensed_data_or_runtime_acceptance_missing"
			var count int
			if item.dataKind == "managed_roads" {
				if err := s.db.QueryRow("SELECT count(*) FROM managed_roads").Scan(&count); err == nil && count > 0 {
					status, reason = "PARTIAL", "coverage_and_speed_unknowns_must_be_disclosed_per_request"
				}
			} else if item.dataKind == "managed_news" {
				if err := s.db.QueryRow("SELECT count(*) FROM records WHERE kind='managed_news' AND json_extract(data,'$.published')=1 AND json_extract(data,'$.audio_id') IS NOT NULL").Scan(&count); err == nil && count > 0 {
					status, reason = "PARTIAL", "tn_licensed_editorial_audio_requires_device_acceptance"
				}
			}
		}
		items = append(items, Object{"id": item.id, "status": status, "reason": reason})
	}
	dependencies := []string{}
	if originalTTSSelected() {
		dependencies = append(dependencies, "original_tts")
	}
	return Object{"runtime_mode": cfg.RuntimeMode, "revision": cfg.Revision, "generation": cfg.Generation, "cost_policy": cfg.CostPolicy, "items": items, "external_dependencies": dependencies}, nil
}

func (s *Server) adminOperations(w http.ResponseWriter, r *http.Request) error {
	cfg, err := s.runtimeConfig()
	if err != nil {
		return err
	}
	counts := Object{}
	for _, table := range []string{"users", "devices", "sessions"} {
		var count int
		query := "SELECT count(*) FROM " + table
		if table == "sessions" {
			err = s.db.QueryRow("SELECT count(*) FROM sessions WHERE revoked=0 AND refresh_expires>?", time.Now().Unix()).Scan(&count)
		} else {
			err = s.db.QueryRow(query).Scan(&count)
		}
		if err != nil {
			return err
		}
		counts[table] = count
	}
	for _, kind := range []string{"device_event", "device_action_result", "runtime_audit"} {
		var n int
		if err = s.db.QueryRow("SELECT count(*) FROM records WHERE kind=?", kind).Scan(&n); err != nil {
			return err
		}
		counts[kind] = n
	}
	capabilities, err := s.runtimeCapabilities()
	if err != nil {
		return err
	}
	send(w, 200, Object{"runtime_mode": cfg.RuntimeMode, "revision": cfg.Revision, "generation": cfg.Generation, "readiness": s.runtimeReadiness(r.Context()), "counts": counts, "capabilities": capabilities})
	return nil
}
