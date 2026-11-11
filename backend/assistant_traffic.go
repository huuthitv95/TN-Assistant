package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

var trafficRegistration = regexp.MustCompile(`^[A-Za-z0-9 .-]{3,24}$`)

// Exact Original h0/a.b upload. The authenticated action supplies the plate;
// neither a client-selected plate nor a client URL can use provider credentials.
func (s *Server) assistantTrafficViolations(w http.ResponseWriter, r *http.Request, principal *Principal) error {
	if s.independentRuntime() {
		return problem(503, "TRAFFIC_VIOLATIONS_API_REQUIRED", "Automatic violation lookup is unavailable until an authorized TN API is configured")
	}
	m, err := body(r)
	if err != nil {
		return err
	}
	for key := range m {
		if key != "request_id" && key != "action_id" && key != "violations" {
			return problem(400, "INVALID_TRAFFIC_UPLOAD", "Only request_id, action_id and observed violations are accepted")
		}
	}
	requestID, actionID := str(m, "request_id"), str(m, "action_id")
	if !aiIdentifier.MatchString(requestID) || !aiIdentifier.MatchString(actionID) {
		return problem(400, "INVALID_TRAFFIC_UPLOAD", "Valid request and action identifiers required")
	}
	rows, ok := m["violations"].([]any)
	if !ok || len(rows) > 500 || len(jsonText(rows)) > 512<<10 {
		return problem(400, "INVALID_TRAFFIC_UPLOAD", "Violations must be an array of at most 500 bounded rows")
	}
	normalized := []Object{}
	for _, value := range rows {
		row, valid := value.(map[string]any)
		if !valid {
			return problem(400, "INVALID_TRAFFIC_UPLOAD", "Each violation must be an object")
		}
		for key := range row {
			if !allowedType(key, []string{"dateTime", "location", "offence", "amount", "status"}) {
				return problem(400, "INVALID_TRAFFIC_UPLOAD", "Unsupported violation field")
			}
		}
		clean := Object{}
		for _, key := range []string{"dateTime", "location", "offence", "amount", "status"} {
			text := ""
			if value, exists := row[key]; exists {
				var valid bool
				text, valid = value.(string)
				if !valid || len(text) > 2000 {
					return problem(400, "INVALID_TRAFFIC_UPLOAD", "Violation values must be bounded strings")
				}
			}
			clean[key] = text
		}
		normalized = append(normalized, clean)
	}
	provenance, err := s.assistantCallbackAction(principal, requestID, actionID, "CheckTrafficViolation")
	if err != nil {
		return err
	}
	plate := strings.TrimSpace(str(provenance.Action, "registrationNumber"))
	if !trafficRegistration.MatchString(plate) {
		return problem(502, "INVALID_TRAFFIC_ACTION", "Original traffic action has no valid registration number")
	}
	if enabled, present := provenance.Action["queryEnabled"].(bool); present && !enabled {
		return problem(409, "TRAFFIC_QUERY_DISABLED", "Cached-only traffic actions cannot upload a fresh query")
	}
	p := provenance.Provider
	endpoint, err := locationEndpoint(p, Object{"pathUrl": "/api/android-dvd/update-traffic-violations"})
	if err != nil {
		return err
	}
	fingerprint := hash(jsonText(Object{"violations": normalized}))
	key := hash(provenance.RequestKey + "\x00" + actionID + "\x00traffic-violations")
	result, err := s.reserveLocation(key, provenance.Owner, fingerprint, provenance.Private)
	if err != nil {
		return err
	}
	if result != nil {
		result["replayed"] = true
		send(w, 200, result)
		return nil
	}
	form := url.Values{"registration_number": {plate}, "violations": {jsonText(normalized)}}
	signOriginalCallbackForm(p, form)
	p.URL = endpoint
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	raw, _, err := providerRequest(ctx, p, "application/x-www-form-urlencoded", []byte(form.Encode()))
	if err != nil {
		return err
	}
	// Original ignores the HTTP2xx body. Honor an explicit error if a deployed
	// endpoint returns one, while retaining transport-only acknowledgment when
	// it has no application status contract. Do not claim the rows were stored.
	var response Object
	if json.Unmarshal(raw, &response) == nil {
		for _, name := range []string{"code", "status", "error_code"} {
			if _, exists := response[name]; exists && integer(response, name, -1) != 0 {
				return problem(502, "TRAFFIC_UPLOAD_REJECTED", "Traffic provider rejected the update")
			}
		}
	}
	result = Object{"request_id": requestID, "action_id": actionID, "status": "submitted", "transport_ack": true, "replayed": false}
	if err = s.completeLocation(key, provenance.Owner, fingerprint, result, provenance.Private); err != nil {
		return err
	}
	send(w, 200, result)
	return nil
}
