package main

import (
	"crypto/md5"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// ActionLogServiceImpl.sendLogV2 / legacy action-log API. The bridge never
// decrypts media/road data or grants an upstream right. Provider credentials are
// bound to the explicitly configured owner of this rebuilt account.
func (s *Server) drivingActionLog(w http.ResponseWriter, r *http.Request, principal *Principal) error {
	if s.independentRuntime() {
		return problem(410, "LEGACY_ACTION_LOG_RETIRED", "TN clients must submit owned device events; encrypted original-app action logs are retired")
	}
	m, e := body(r)
	if e != nil {
		return e
	}
	uid := str(principal.User, "id")
	logs, logsOK := m["logs"].([]any)
	mediaOnly := false
	for _, item := range logs {
		row, _ := item.(map[string]any)
		if code, ok := row["action_code"].(float64); ok && code == 10 {
			// DurationReporter sends one encrypted media event. It is a Basic
			// feature and must never relax the Premium gate for road records.
			if len(logs) != 1 {
				return problem(400, "INVALID_ACTION_LOG", "A media envelope cannot be mixed with other action logs")
			}
			mediaOnly = true
		}
	}
	if !mediaOnly && s.premiumFeatures(uid)["driving_info"] != true {
		return problem(403, "FEATURE_NOT_AVAILABLE", "Gói hiện tại chưa có Cảnh báo giao thông")
	}
	p, e := s.requireProvider("action_log")
	if e != nil {
		return e
	}
	if str(p.Options, "owner_user_id") != uid {
		return problem(403, "PROVIDER_ACCOUNT_MISMATCH", "Dịch vụ báo lỗi chưa được liên kết với tài khoản này")
	}
	endpoint, e := url.Parse(p.URL)
	if e != nil || endpoint.User != nil || endpoint.RawQuery != "" || endpoint.Fragment != "" || endpoint.Path != "/log/action/v2" || endpoint.Host == "" ||
		(endpoint.Scheme != "https" && !(endpoint.Scheme == "http" && (endpoint.Hostname() == "localhost" || endpoint.Hostname() == "127.0.0.1"))) {
		return problem(503, "PROVIDER_CONFIG_INVALID", "Action log requires the configured Original log/action/v2 endpoint")
	}
	if !logsOK || len(logs) < 1 || len(logs) > 2 {
		return problem(400, "INVALID_ACTION_LOG", "One media/road envelope or distinct road/feedback envelopes required")
	}
	seen := map[int]bool{}
	encrypted := func(v any, block int) bool {
		text, ok := v.(string)
		if !ok || text == "" || strings.ContainsAny(text, "\r\n ") {
			return false
		}
		raw, err := base64.URLEncoding.DecodeString(text)
		return err == nil && len(raw) >= block && len(raw)%block == 0
	}
	for _, item := range logs {
		row, ok := item.(map[string]any)
		if !ok {
			return problem(400, "INVALID_ACTION_LOG", "Encrypted envelope required")
		}
		code, ok := row["action_code"].(float64)
		if !ok || (code != 10 && code != 12 && code != 30) || seen[int(code)] {
			return problem(400, "INVALID_ACTION_LOG", "Unsupported or duplicate action code")
		}
		seen[int(code)] = true
		meta, _ := row["meta"].(map[string]any)
		setting, _ := meta["encryption_setting"].(map[string]any)
		if str(meta, "content_format") != "encrypted_array" || !encrypted(row["log_content"], 16) || !encrypted(setting["iv"], 128) || !encrypted(setting["secret_key"], 128) {
			return problem(400, "INVALID_ACTION_LOG", "AES content and RSA wrapped key/IV required")
		}
		// Only outer identity changes. AES content remains byte-for-byte unchanged.
		row["user_id"] = p.userID()
		row["device_id"] = os.Getenv(str(p.Options, "device_id_env"))
		// App metadata follows the configured upstream identity, independent of
		// the Android client version. The encrypted payload and key/IV are untouched.
		row["app_type"] = p.ClientAppType
	}
	if !mediaOnly && !seen[12] {
		return problem(400, "INVALID_ACTION_LOG", "Feedback must also include its road snapshot")
	}
	if seen[30] {
		config, _ := s.record("config", "public")
		feedback, _ := config["speed_limit_feedback"].(map[string]any)
		if feedback["enabled"] != true {
			return problem(403, "FEEDBACK_DISABLED", "Chức năng báo lỗi tốc độ đang tắt")
		}
	}
	coordinates := map[string]string{}
	for key, limit := range map[string]float64{"lat": 90, "lon": 180} {
		value, ok := m[key].(float64)
		if !ok || math.IsNaN(value) || math.IsInf(value, 0) || math.Abs(value) > limit {
			return problem(400, "INVALID_LOCATION", "Numeric log transport latitude/longitude required")
		}
		coordinates[key] = strconv.FormatFloat(value, 'f', -1, 64)
	}
	conversation, ok := m["session_key"].(string)
	if !ok || len(conversation) > 512 {
		return problem(400, "INVALID_SESSION", "Current TN conversation required (empty before first session)")
	}
	session, e := s.actionLogProviderSession(principal, p, conversation)
	if e != nil {
		return e
	}
	logJSON, _ := json.Marshal(logs)
	// The Original URL-encodes event once before Retrofit form encoding.
	const event = "{\n\"event\": \"MessageRequest\",\n\"payload\": {\n\"report\": {\n\"intent\": \"report_ki_ki_asr_fail\"\n}\n}\n}"
	form := url.Values{"device_token": {os.Getenv(p.DeviceTokenEnv)}, "log": {string(logJSON)},
		"asr": {str(p.Options, "asr_type")}, "nlu_type": {str(p.Options, "nlu_type")}, "micro_source": {str(p.Options, "micro_source")},
		"session_key": {session}, "session_state": {"session.chat"}, "device_id": {os.Getenv(str(p.Options, "device_id_env"))},
		"lat": {coordinates["lat"]}, "lon": {coordinates["lon"]}, "event": {url.QueryEscape(event)}}
	// Retrofit's configured provider request interceptor adds these fields to every
	// Original form POST, including log/action/v2 (not just NLU/road calls).
	stamp := strconv.FormatInt(time.Now().UnixMilli(), 10)
	md5text := func(value string) string { sum := md5.Sum([]byte(value)); return hex.EncodeToString(sum[:]) }
	form.Set("client_app_type", p.ClientAppType)
	form.Set("version_code", p.Version)
	form.Set("timestamp", stamp)
	form.Set("hash", md5text(os.Getenv(p.SecretEnv)+md5text(p.ClientAppType+stamp)))
	if extra, ok := p.Options["form_fields"].(map[string]any); ok {
		for _, key := range []string{"os_version", "is_android_box", "device_type", "installed_src", "firebase_analytic_id", "sd_card_id", "device_model", "activate_source", "device_bundle_token", "gen_ai_mode"} {
			if value, exists := extra[key]; exists {
				form.Set(key, fmt.Sprint(value))
			}
		}
	}
	raw, _, e := providerRequest(r.Context(), p, "application/x-www-form-urlencoded", []byte(form.Encode()))
	if e != nil {
		return e
	}
	// The shipped SDK checks status. The actual deployed endpoint instead
	// replies {code:0,message:...}. Only an explicit integer zero accepts
	// either observed contract; malformed/conflicting fields remain errors.
	var response map[string]json.RawMessage
	if json.Unmarshal(raw, &response) != nil || response == nil {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "Action log reply must be a JSON object")
	}
	field := ""
	for _, key := range []string{"status", "code"} {
		value, exists := response[key]
		if !exists {
			continue
		}
		var result *int
		if json.Unmarshal(value, &result) != nil || result == nil {
			return problem(502, "PROVIDER_INVALID_RESPONSE", "Action log result must be an integer")
		}
		if *result != 0 {
			return problem(502, "ACTION_LOG_REJECTED", "Dịch vụ báo lỗi từ chối dữ liệu")
		}
		if field == "" {
			field = key
		}
	}
	if field == "" {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "Action log reply missing status/code")
	}
	send(w, 200, Object{"status": 0, "acknowledged": true, "groups": len(logs), "provider_result_field": field, "provider_result_code": 0})
	return nil
}

// Android's conversation label is not the provider's session credential.
// Resolve the last successful NLU response at every send, including timer logs
// after a later non-media command. Never forward an unverified client value.
func (s *Server) actionLogProviderSession(principal *Principal, action Provider, conversation string) (string, error) {
	uid, device := str(principal.User, "id"), str(principal.Device, "id")
	if conversation == "" || uid == "" || device == "" || s.aiSettings(uid)["private_mode"] == true {
		return "", nil
	}
	providers, e := s.providers()
	if e != nil {
		return "", e
	}
	nlu := providers["nlu"]
	if nlu.Mode != "legacy_tn" || !nlu.configured("nlu") {
		return "", nil
	}
	if owner := str(nlu.Options, "owner_user_id"); owner != "" && owner != uid {
		return "", nil
	}
	// The NLU writer gives an explicit upstream device precedence over the
	// normal authenticated client device. Reproduce that same form precedence.
	if fields, ok := nlu.Options["form_fields"].(map[string]any); ok {
		if configured, exists := fields["device_id"]; exists {
			device = fmt.Sprint(configured)
		}
	}
	if ref := str(nlu.Options, "device_id_env"); ref != "" {
		device = os.Getenv(ref)
	}
	if nlu.userID() != action.userID() || device != os.Getenv(str(action.Options, "device_id_env")) {
		return "", nil
	}
	id := legacyNLUSessionRecordID(principal, conversation, nlu, device)
	var raw string
	e = s.db.QueryRow("SELECT data FROM records WHERE kind='nlu_session' AND id=? AND owner=?", id, uid).Scan(&raw)
	if errors.Is(e, sql.ErrNoRows) {
		return "", nil
	}
	if e != nil {
		return "", e
	}
	var saved Object
	if json.Unmarshal([]byte(raw), &saved) != nil || saved == nil {
		return "", nil
	}
	expires, e := time.Parse(time.RFC3339Nano, str(saved, "expires_at"))
	if e != nil || !expires.After(time.Now()) {
		return "", nil
	}
	return str(saved, "session_key"), nil
}
