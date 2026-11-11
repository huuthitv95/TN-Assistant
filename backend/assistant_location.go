package main

import (
	"context"
	"crypto/md5"
	"database/sql"
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

type privateLocationRequest struct {
	Expires    time.Time
	Directives []Object
}
type privateLocationReply struct {
	Expires     time.Time
	Fingerprint string
	Result      Object
}

func locationProviderBinding(p Provider) string {
	// commandAssistant creates an empty HeaderEnv map when none was configured.
	// Canonicalize nil/empty before hashing while retaining every configured
	// reference and its resolved value; empty-map allocation is not a key change.
	headerEnv := map[string]string{}
	for name, reference := range p.HeaderEnv {
		headerEnv[name] = reference
	}
	return hash(jsonText(Object{"url": p.URL, "uid": p.userID(), "token": os.Getenv(p.TokenEnv), "device_token": os.Getenv(p.DeviceTokenEnv), "device_id": os.Getenv(str(p.Options, "device_id_env")), "secret": os.Getenv(p.SecretEnv), "headers": p.headers(), "header_env": headerEnv, "client_app_type": p.ClientAppType, "version": p.Version, "options": p.Options}))
}

func objectDirectives(out Object) []Object {
	if values, ok := out["directives"].([]Object); ok {
		return values
	}
	values, _ := out["directives"].([]any)
	result := []Object{}
	for _, value := range values {
		if item, ok := value.(map[string]any); ok {
			result = append(result, item)
		}
	}
	return result
}

func (s *Server) prunePrivateLocations() {
	for key, value := range s.privateLocations {
		if time.Now().After(value.Expires) {
			delete(s.privateLocations, key)
		}
	}
	for key, value := range s.privateLocationReplies {
		if time.Now().After(value.Expires) {
			delete(s.privateLocationReplies, key)
		}
	}
}

// Private-mode provenance and callback answers exist only in bounded volatile
// memory. They never restore the question or directives into SQLite replay data.
func (s *Server) rememberPrivateLocationRequest(key string, out Object) {
	values := []Object{}
	for _, directive := range objectDirectives(out) {
		if strings.HasPrefix(str(directive, "type"), "MapDirection") || str(directive, "type") == "CheckTrafficViolation" || str(directive, "type") == "CompoundCommand" || str(directive, "type") == "CallApi" {
			values = append(values, directive)
		}
	}
	if len(values) == 0 {
		return
	}
	s.locationMu.Lock()
	defer s.locationMu.Unlock()
	s.prunePrivateLocations()
	if s.privateLocations == nil {
		s.privateLocations = map[string]privateLocationRequest{}
	}
	if len(s.privateLocations) >= 200 {
		return
	}
	s.privateLocations[key] = privateLocationRequest{time.Now().Add(5 * time.Minute), values}
}

func (s *Server) assistantLocation(w http.ResponseWriter, r *http.Request, principal *Principal) error {
	if s.independentRuntime() {
		return problem(410, "LEGACY_CALLBACK_RETIRED", "Provider-bound location callbacks are retired in TN runtime")
	}
	m, err := body(r)
	if err != nil {
		return err
	}
	for key := range m {
		if key != "request_id" && key != "action_id" && key != "operation" && key != "parser_result" {
			return problem(400, "INVALID_LOCATION_REQUEST", "Location callback accepts request/action identifiers and parser_result only")
		}
	}
	requestID, actionID, operation := str(m, "request_id"), str(m, "action_id"), str(m, "operation")
	if !aiIdentifier.MatchString(requestID) || !aiIdentifier.MatchString(actionID) || (operation != "address" && operation != "gg_parser") {
		return problem(400, "INVALID_LOCATION_REQUEST", "Valid request_id, action_id and address/gg_parser operation required")
	}
	provenance, err := s.assistantCallbackAction(principal, requestID, actionID, "MapDirectionGGParser", "MapDirectionOpenApp", "MapDirection", "CompoundCommand")
	if err != nil {
		return err
	}
	action, p, private := provenance.Action, provenance.Provider, provenance.Private
	uid, requestKey := provenance.Owner, provenance.RequestKey
	data, _ := action["data"].(map[string]any)
	field := "searchLocationRequest"
	if operation == "gg_parser" {
		field = "ggParserApiRequest"
	}
	callback, _ := data[field].(map[string]any)
	if callback == nil {
		return problem(409, "LOCATION_CALLBACK_UNAVAILABLE", "Original directive has no callback for this operation")
	}
	endpoint, err := locationEndpoint(p, callback)
	if err != nil {
		return err
	}
	form := url.Values{}
	payload, ok := callback["payload"].(map[string]any)
	if !ok {
		return problem(502, "LOCATION_CALLBACK_INVALID", "Original callback payload must be an object")
	}
	if operation == "address" {
		if _, present := m["parser_result"]; present {
			return problem(400, "INVALID_LOCATION_REQUEST", "Address callbacks do not accept parser_result")
		}
		for key, value := range payload {
			if text, ok := value.(string); ok {
				form.Set(key, text)
			} else {
				form.Set(key, jsonText(value))
			}
		}
	} else {
		form.Set("echo_result", jsonText(payload))
		if value, exists := m["parser_result"]; exists {
			parsed, valid := value.(map[string]any)
			if !valid || !validParserResult(parsed) {
				return problem(400, "INVALID_LOCATION_PARSER", "Parser result must contain valid observed coordinates and supported fields")
			}
			form.Set("parser_result", jsonText(parsed))
		}
	}
	fingerprint := hash(jsonText(m))
	key := hash(requestKey + "\x00" + actionID + "\x00" + operation)
	result, err := s.reserveLocation(key, uid, fingerprint, private)
	if err != nil {
		return err
	}
	if result != nil {
		result["replayed"] = true
		send(w, 200, result)
		return nil
	}
	signOriginalCallbackForm(p, form)
	timeout := integer(callback, "timeout", 5000)
	if timeout < 1 || timeout > 30000 {
		timeout = 5000
	}
	ctx, cancel := context.WithTimeout(r.Context(), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	p.URL = endpoint
	raw, _, err := providerRequest(ctx, p, "application/x-www-form-urlencoded", []byte(form.Encode()))
	if err != nil {
		return err
	}
	var response Object
	if json.Unmarshal(raw, &response) != nil {
		return problem(502, "PROVIDER_INVALID_RESPONSE", "Location callback is not JSON")
	}
	result, err = normalizeLocationResponse(response, operation)
	if err != nil {
		return err
	}
	result["request_id"], result["action_id"], result["operation"], result["replayed"] = requestID, actionID, operation, false
	if err = s.completeLocation(key, uid, fingerprint, result, private); err != nil {
		return err
	}
	send(w, 200, result)
	return nil
}

func locationEndpoint(p Provider, callback Object) (string, error) {
	base, err := url.Parse(p.URL)
	if err != nil || base.Host == "" || base.User != nil {
		return "", problem(503, "PROVIDER_CONFIG_INVALID", "Invalid configured NLU origin")
	}
	path := str(callback, "pathUrl")
	if path == "" {
		paths, _ := p.Options["location_paths"].(map[string]any)
		path = str(paths, str(callback, "pathKey"))
	}
	ref, err := url.Parse(path)
	if path == "" || err != nil || ref.User != nil || ref.RawQuery != "" || ref.Fragment != "" || strings.Contains(path, "\\") {
		return "", problem(409, "LOCATION_ENDPOINT_UNAVAILABLE", "Callback requires a verified pathUrl or configured pathKey mapping")
	}
	// Dynamic Retrofit @Url paths use the app root, not /api/event/.
	base.Path, base.RawPath, base.RawQuery, base.Fragment = "/", "", "", ""
	endpoint := base.ResolveReference(ref)
	if !strings.EqualFold(endpoint.Host, base.Host) || endpoint.Scheme != base.Scheme || endpoint.User != nil || (endpoint.Scheme != "https" && !(endpoint.Scheme == "http" && (endpoint.Hostname() == "localhost" || endpoint.Hostname() == "127.0.0.1"))) {
		return "", problem(409, "LOCATION_ENDPOINT_REJECTED", "Location callbacks must stay on the configured NLU origin")
	}
	return endpoint.String(), nil
}

func validLocationAddress(address Object) bool {
	lat, latOK := address["lat"].(float64)
	lon, lonOK := address["lon"].(float64)
	return latOK && lonOK && !math.IsNaN(lat) && !math.IsInf(lat, 0) && !math.IsNaN(lon) && !math.IsInf(lon, 0) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(lat == -1 && lon == -1)
}

func validParserResult(parser Object) bool {
	if !validLocationAddress(parser) {
		return false
	}
	for key, value := range parser {
		switch key {
		case "lat", "lon":
		case "name", "address", "type":
			text, ok := value.(string)
			if !ok || len(text) > 2000 {
				return false
			}
		case "is_closed":
			if _, ok := value.(bool); !ok {
				return false
			}
		case "rating":
			number, ok := value.(float64)
			if !ok || number < 0 || number > 5 {
				return false
			}
		default:
			return false
		}
	}
	return true
}

func normalizeLocationResponse(response Object, operation string) (Object, error) {
	data, _ := response["data"].(map[string]any)
	code := integer(response, "code", -1)
	invalid := func() (Object, error) {
		return nil, problem(502, "PROVIDER_INVALID_RESPONSE", "Invalid Original location callback response")
	}
	if operation == "address" {
		if code != 0 || !validLocationAddress(data) {
			return invalid()
		}
		return Object{"status": "resolved", "address": data}, nil
	}
	speech, _ := data["speech"].(map[string]any)
	if strings.TrimSpace(str(speech, "utter_text")) == "" && strings.TrimSpace(str(speech, "cached_url")) == "" {
		return invalid()
	}
	if code == 1 {
		return Object{"status": "speech", "speech": speech}, nil
	}
	direction, _ := data["direction"].(map[string]any)
	address, _ := direction["destination"].(map[string]any)
	if code != 0 || !validLocationAddress(address) {
		return invalid()
	}
	return Object{"status": "resolved", "direction": direction, "speech": speech}, nil
}

func (s *Server) reserveLocation(key, uid, fingerprint string, private bool) (Object, error) {
	if private {
		s.locationMu.Lock()
		defer s.locationMu.Unlock()
		s.prunePrivateLocations()
		if value, exists := s.privateLocationReplies[key]; exists {
			if value.Fingerprint != fingerprint {
				return nil, problem(409, "LOCATION_CALLBACK_CONFLICT", "Callback already used with different parser input")
			}
			if value.Result != nil {
				var copy Object
				json.Unmarshal([]byte(jsonText(value.Result)), &copy)
				return copy, nil
			}
			return nil, problem(409, "LOCATION_CALLBACK_IN_PROGRESS", "Location callback is pending or interrupted")
		}
		if s.privateLocationReplies == nil {
			s.privateLocationReplies = map[string]privateLocationReply{}
		}
		if len(s.privateLocationReplies) >= 400 {
			return nil, problem(429, "LOCATION_CALLBACK_LIMIT", "Too many private location callbacks")
		}
		s.privateLocationReplies[key] = privateLocationReply{time.Now().Add(5 * time.Minute), fingerprint, nil}
		return nil, nil
	}
	previous, err := s.record("assistant_location", key)
	if err == nil {
		if str(previous, "fingerprint") != fingerprint {
			return nil, problem(409, "LOCATION_CALLBACK_CONFLICT", "Callback already used with different parser input")
		}
		if result, ok := previous["result"].(map[string]any); ok {
			return result, nil
		}
		return nil, problem(409, "LOCATION_CALLBACK_IN_PROGRESS", "Location callback is pending or interrupted")
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	reserved, err := s.db.Exec("INSERT OR IGNORE INTO records(kind,id,owner,data,created_at) VALUES('assistant_location',?,?,?,?)", key, uid, jsonText(Object{"fingerprint": fingerprint}), now())
	if err != nil {
		return nil, err
	}
	count, _ := reserved.RowsAffected()
	if count != 1 {
		return nil, problem(409, "LOCATION_CALLBACK_IN_PROGRESS", "Location callback already reserved")
	}
	return nil, nil
}

func (s *Server) completeLocation(key, uid, fingerprint string, result Object, private bool) error {
	if private {
		s.locationMu.Lock()
		defer s.locationMu.Unlock()
		s.privateLocationReplies[key] = privateLocationReply{time.Now().Add(5 * time.Minute), fingerprint, result}
		return nil
	}
	return s.put("assistant_location", key, uid, Object{"fingerprint": fingerprint, "result": result})
}

type assistantCallbackProvenance struct {
	Action            Object
	Provider          Provider
	Private           bool
	Owner, RequestKey string
}

func allowedType(value string, allowed []string) bool {
	for _, candidate := range allowed {
		if value == candidate {
			return true
		}
	}
	return false
}
func (s *Server) assistantCallbackAction(principal *Principal, requestID, actionID string, types ...string) (assistantCallbackProvenance, error) {
	uid, did := str(principal.User, "id"), str(principal.Device, "id")
	if did == "" {
		did = "web"
	}
	requestKey := hash(uid+"\x00"+did) + ":" + requestID
	reserved, err := s.record("assistant_request", requestKey)
	if err != nil || str(reserved, "status") != "complete" {
		return assistantCallbackProvenance{}, problem(404, "LOCATION_ACTION_NOT_FOUND", "No completed assistant request for this account and device")
	}
	created, err := time.Parse(time.RFC3339Nano, str(reserved, "created_at"))
	if err != nil || time.Since(created) > 30*time.Minute {
		return assistantCallbackProvenance{}, problem(409, "LOCATION_ACTION_EXPIRED", "Original location action has expired")
	}
	out, _ := reserved["result"].(map[string]any)
	directives := objectDirectives(out)
	private := false
	s.locationMu.Lock()
	s.prunePrivateLocations()
	if value, ok := s.privateLocations[requestKey]; ok {
		directives = value.Directives
		private = true
	}
	s.locationMu.Unlock()
	var action Object
	for _, directive := range directives {
		payload, _ := directive["payload"].(map[string]any)
		id := str(payload, "action_id")
		if id == "" {
			id = str(directive, "action_id")
		}
		if id == actionID && allowedType(str(directive, "type"), types) {
			action = payload
			if str(directive, "type") == "CompoundCommand" {
				// Original x/g classifier forwards map.data to y/a.a, which
				// reads another data object. Only resolve_geo has a callback;
				// by_name and unrelated compound actions cannot select a URL.
				child, _ := payload["map"].(map[string]any)
				if str(child, "type") != "resolve_geo" {
					action = nil
				} else {
					action, _ = child["data"].(map[string]any)
				}
			}
			break
		}
	}
	if action == nil {
		return assistantCallbackProvenance{}, problem(404, "LOCATION_ACTION_NOT_FOUND", "Location action not present in the authenticated assistant response")
	}
	p, err := s.requireProvider("nlu")
	if err != nil {
		return assistantCallbackProvenance{}, err
	}
	if p.Mode != "legacy_tn" || str(reserved, "nlu_provider_binding") == "" || str(reserved, "nlu_provider_binding") != locationProviderBinding(p) {
		return assistantCallbackProvenance{}, problem(409, "LOCATION_PROVIDER_CHANGED", "Location action belongs to another provider configuration")
	}
	return assistantCallbackProvenance{action, p, private, uid, requestKey}, nil
}

func signOriginalCallbackForm(p Provider, form url.Values) {
	// Match configured provider request interceptor common fields. Only the configured
	// origin receives credentials; request/response redirects are never followed.
	if extra, ok := p.Options["form_fields"].(map[string]any); ok {
		for _, name := range []string{"os_version", "is_android_box", "device_type", "installed_src", "firebase_analytic_id", "sd_card_id", "device_model", "activate_source", "device_bundle_token", "gen_ai_mode"} {
			if value, exists := extra[name]; exists {
				form.Set(name, fmt.Sprint(value))
			}
		}
	}
	form.Set("device_token", os.Getenv(p.DeviceTokenEnv))
	form.Set("device_id", os.Getenv(str(p.Options, "device_id_env")))
	if p.SecretEnv != "" {
		stamp := strconv.FormatInt(time.Now().UnixMilli(), 10)
		digest := func(value string) string { sum := md5.Sum([]byte(value)); return hex.EncodeToString(sum[:]) }
		form.Set("client_app_type", p.ClientAppType)
		form.Set("version_code", p.Version)
		form.Set("timestamp", stamp)
		form.Set("hash", digest(os.Getenv(p.SecretEnv)+digest(p.ClientAppType+stamp)))
	}
}
