package main

import (
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// legacy provider welcome endpoint. This read-only bridge does
// not purchase, grant features, or substitute a local greeting for a rule.
func (s *Server) welcomeRule(w http.ResponseWriter, r *http.Request, principal *Principal) error {
	if s.independentRuntime() {
		now := time.Now()
		command := Object{"id": "tn.welcome.ready.v1", "message": "Xin chào, TN Assistant sẵn sàng hỗ trợ bạn.", "action": "SPEAK_ONLY", "priority": 0, "maxNumberOfTimes": -1, "audioLink": nil, "updatedTime": now.UnixMilli()}
		send(w, 200, Object{"status": 0, "data": Object{"version": 1, "onboardMessages": []Object{command}, "setupMessages": []Object{}}, "periodUpdate": -1, "lastUpdated": now.UnixMilli(), "serverTimeInMilli": now.UnixMilli()})
		return nil
	}
	p, e := s.requireProvider("welcome")
	if e != nil {
		return e
	}
	if str(p.Options, "owner_user_id") != str(principal.User, "id") {
		return problem(403, "PROVIDER_ACCOUNT_MISMATCH", "Welcome provider is bound to another account")
	}
	endpoint, e := url.Parse(p.URL)
	if e != nil || endpoint.User != nil || endpoint.RawQuery != "" || endpoint.Fragment != "" || endpoint.Path != "/api/welcome_message" || endpoint.Host == "" ||
		(endpoint.Scheme != "https" && !(endpoint.Scheme == "http" && (endpoint.Hostname() == "localhost" || endpoint.Hostname() == "127.0.0.1"))) {
		return problem(503, "PROVIDER_CONFIG_INVALID", "Welcome requires the Original api/welcome_message endpoint")
	}
	stamp := strconv.FormatInt(time.Now().UnixMilli(), 10)
	digest := func(value string) string { sum := md5.Sum([]byte(value)); return hex.EncodeToString(sum[:]) }
	form := url.Values{"device_token": {os.Getenv(p.DeviceTokenEnv)}, "device_id": {os.Getenv(str(p.Options, "device_id_env"))},
		"client_app_type": {p.ClientAppType}, "version_code": {p.Version}, "timestamp": {stamp},
		"hash": {digest(os.Getenv(p.SecretEnv) + digest(p.ClientAppType+stamp))}}
	if extra, ok := p.Options["form_fields"].(map[string]any); ok {
		for _, key := range []string{"os_version", "is_android_box", "device_type", "installed_src", "firebase_analytic_id", "sd_card_id", "device_model", "activate_source", "device_bundle_token", "gen_ai_mode"} {
			if value, exists := extra[key]; exists {
				form.Set(key, fmt.Sprint(value))
			}
		}
	}
	request, e := http.NewRequestWithContext(r.Context(), "POST", p.URL, strings.NewReader(form.Encode()))
	if e != nil {
		return problem(503, "PROVIDER_CONFIG_INVALID", "Invalid welcome endpoint")
	}
	request.Header = p.headers()
	request.Header.Set("Authorization", "Token "+os.Getenv(p.TokenEnv)+" UID "+p.userID())
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, e := client().Do(request)
	if e != nil {
		return problem(502, "PROVIDER_UNAVAILABLE", "Welcome provider connection failed")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return problem(502, "PROVIDER_HTTP_ERROR", fmt.Sprintf("Welcome provider returned HTTP %d", response.StatusCode))
	}
	raw, e := io.ReadAll(io.LimitReader(response.Body, (2<<20)+1))
	if e != nil || len(raw) > 2<<20 {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "Welcome rule exceeds limit or cannot be read")
	}
	serverTime := time.Now()
	if parsed, err := http.ParseTime(response.Header.Get("Date")); err == nil {
		serverTime = parsed
	}
	rule, e := parseWelcomeRule(raw, serverTime)
	if e != nil {
		return e
	}
	send(w, 200, rule)
	return nil
}

// Mirror the deployed DAO defaults, retaining command order and action names.
// HTTP Date wins over a JSON clock as in WelcomeMsgRuleDAOKt's Retrofit path.
func parseWelcomeRule(raw []byte, serverTime time.Time) (Object, error) {
	invalid := func() (Object, error) {
		return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Invalid Original welcome rule")
	}
	var source map[string]json.RawMessage
	if json.Unmarshal(raw, &source) != nil || source == nil {
		return invalid()
	}
	integer := func(raw json.RawMessage, fallback int) int {
		var value *int
		if json.Unmarshal(raw, &value) == nil && value != nil {
			return *value
		}
		return fallback
	}
	var status *int
	resultField := "status"
	if _, hasStatus := source["status"]; hasStatus {
		if json.Unmarshal(source["status"], &status) != nil || status == nil {
			return invalid()
		}
	} else {
		// The deployed current-account endpoint uses code:0, unlike the shipped
		// DAO's status field. Accept only the explicit observed success value.
		if json.Unmarshal(source["code"], &status) != nil || status == nil || *status != 0 {
			return invalid()
		}
		resultField = "code"
	}
	if code, exists := source["code"]; exists {
		var value *int
		if json.Unmarshal(code, &value) != nil || value == nil || *value != 0 || *value != *status {
			return invalid()
		}
	}
	var data map[string]json.RawMessage
	if json.Unmarshal(source["data"], &data) != nil || data == nil {
		return invalid()
	}
	var version *int
	if json.Unmarshal(data["version"], &version) != nil || version == nil {
		return invalid()
	}
	result := Object{"version": *version}
	now := time.Now().UnixMilli()
	for _, key := range []string{"onboardMessages", "setupMessages"} {
		var rows []map[string]json.RawMessage
		if json.Unmarshal(data[key], &rows) != nil || rows == nil {
			return invalid()
		}
		commands := []any{}
		for _, row := range rows {
			var id *string
			if row == nil || json.Unmarshal(row["id"], &id) != nil || id == nil {
				return invalid()
			}
			text := func(key, fallback string) string {
				var value *string
				if json.Unmarshal(row[key], &value) == nil && value != nil {
					return *value
				}
				return fallback
			}
			var updated *int64
			_ = json.Unmarshal(row["updatedTime"], &updated)
			updatedTime := now
			if updated != nil {
				updatedTime = *updated
			}
			var audio any
			if link := text("audioLink", ""); link != "" {
				audio = link
			}
			commands = append(commands, Object{"id": *id, "message": text("message", ""), "action": text("action", "NOTHING"),
				"priority": integer(row["priority"], -1), "maxNumberOfTimes": integer(row["maxNumberOfTimes"], -1), "audioLink": audio, "updatedTime": updatedTime})
		}
		result[key] = commands
	}
	// Source keeps status on the model; a structurally valid nonzero status is
	// not reinterpreted as a Premium grant, nor silently changed to zero.
	return Object{"status": *status, "data": result, "periodUpdate": integer(source["periodUpdate"], -1),
		"lastUpdated": now, "serverTimeInMilli": serverTime.UnixMilli(), "provider_result_field": resultField}, nil
}
