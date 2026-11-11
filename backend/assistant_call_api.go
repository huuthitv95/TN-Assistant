package main

import (
	"context"
	"encoding/json"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// CallApi is an Original server callback, not an Android deep link. The phone
// submits identifiers only; the retained provider directive supplies the path,
// nested payload, timeout and wait_result. legacy provider call handler sends
// one form field named payload containing JSON, plus its common signed fields.
// A non-waiting success acknowledges dispatch, never provider-side completion.
func (s *Server) assistantCallAPI(w http.ResponseWriter, r *http.Request, principal *Principal) error {
	if s.independentRuntime() {
		return problem(410, "LEGACY_CALLBACK_RETIRED", "Provider-bound call callbacks are retired in TN runtime")
	}
	m, err := body(r)
	if err != nil {
		return err
	}
	for key := range m {
		if key != "request_id" && key != "action_id" {
			return problem(400, "INVALID_CALL_API_REQUEST", "Only request_id and action_id are accepted")
		}
	}
	requestID, actionID := str(m, "request_id"), str(m, "action_id")
	if !aiIdentifier.MatchString(requestID) || !aiIdentifier.MatchString(actionID) {
		return problem(400, "INVALID_CALL_API_REQUEST", "Valid request and action identifiers required")
	}
	provenance, err := s.assistantCallbackAction(principal, requestID, actionID, "CallApi")
	if err != nil {
		return err
	}
	path, nested, timeout, wait, err := originalCallAPIFields(provenance.Action)
	if err != nil {
		return err
	}
	endpoint, err := locationEndpoint(provenance.Provider, Object{"pathUrl": path})
	if err != nil {
		return err
	}
	fingerprint := hash(jsonText(Object{"path": path, "payload": nested, "timeout_ms": timeout.Milliseconds(), "wait_result": wait}))
	key := hash(provenance.RequestKey + "\x00" + actionID + "\x00call-api")
	previous, err := s.reserveLocation(key, provenance.Owner, fingerprint, provenance.Private)
	if err != nil {
		return err
	}
	if previous != nil {
		previous["replayed"] = true
		send(w, 200, previous)
		return nil
	}
	result := func(outcome, status, reason string, confirmed bool) Object {
		out := Object{"request_id": requestID, "action_id": actionID, "result": outcome,
			"status": status, "wait_result": wait, "upstream_confirmed": confirmed, "replayed": false}
		if reason != "" {
			out["reason"] = reason
		}
		return out
	}
	complete := func(out Object) error {
		if err := s.completeLocation(key, provenance.Owner, fingerprint, out, provenance.Private); err != nil {
			return err
		}
		send(w, 200, out)
		return nil
	}
	if timeout <= 0 && wait {
		return complete(result("fail", "completed", "timeout", false))
	}
	if !s.reserveCallAPIWorker() {
		return complete(result("fail", "completed", "busy", false))
	}
	// Snapshot just the authenticated identity. Mutable user/profile maps and
	// request bodies are never retained by the detached worker.
	identity := &Principal{User: Object{"id": provenance.Owner}, Device: Object{"id": str(principal.Device, "id")}, SessionID: principal.SessionID}
	invoke := func(ctx context.Context) (string, string) {
		if ctx.Err() != nil {
			return "fail", "timeout_or_cancelled"
		}
		// Recheck both current session ownership and the bound provider just
		// before dispatch. Logout, unlink or changing provider credentials must
		// not authorize a previously queued callback for a different account.
		if !s.callAPIScopeCurrent(identity) {
			return "fail", "account_changed"
		}
		current, err := s.assistantCallbackAction(identity, requestID, actionID, "CallApi")
		if err != nil || locationProviderBinding(current.Provider) != locationProviderBinding(provenance.Provider) {
			return "fail", "action_changed"
		}
		p := current.Provider
		p.URL = endpoint
		form := url.Values{"payload": {jsonText(nested)}}
		signOriginalCallbackForm(p, form)
		raw, _, err := providerRequest(ctx, p, "application/x-www-form-urlencoded", []byte(form.Encode()))
		if err != nil {
			if ctx.Err() != nil {
				return "fail", "timeout_or_cancelled"
			}
			return "fail", "provider_error"
		}
		var reply Object
		if json.Unmarshal(raw, &reply) != nil || reply == nil {
			return "fail", "invalid_response"
		}
		code, ok := originalCallAPIStatus(reply["code"])
		if !ok || code != 0 {
			return "fail", "provider_rejected"
		}
		// Never forward upstream fields, directives or private response text.
		return "success", ""
	}
	if wait {
		defer s.releaseCallAPIWorker()
		ctx, cancel := context.WithTimeout(r.Context(), timeout)
		defer cancel()
		outcome, reason := invoke(ctx)
		return complete(result(outcome, "completed", reason, outcome == "success"))
	}
	// Store the dispatch acknowledgement before launching. A lost HTTP reply
	// or concurrent retry cannot create a second possibly actionable POST.
	out := result("success", "dispatched", "", false)
	if err := s.completeLocation(key, provenance.Owner, fingerprint, out, provenance.Private); err != nil {
		s.releaseCallAPIWorker()
		return err
	}
	go func() {
		defer s.releaseCallAPIWorker()
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		// Original wait_result=false has no completion callback. In particular,
		// late errors cannot alter the accepted result or run a directive chain.
		invoke(ctx)
	}()
	send(w, 200, out)
	return nil
}

// Android JSONObject.getInt converts finite numeric strings/numbers through
// Java's int conversion: truncate toward zero, with saturation outside int32.
// Bound strings and reject non-finite inputs rather than adopting Java's NaN
// to zero coercion as a successful provider acknowledgement.
func originalCallAPIStatus(value any) (int32, bool) {
	number, valid := originalCallAPINumber(value)
	if !valid || math.IsNaN(number) || math.IsInf(number, 0) {
		return 0, false
	}
	if number >= math.MaxInt32 {
		return math.MaxInt32, true
	}
	if number <= math.MinInt32 {
		return math.MinInt32, true
	}
	return int32(number), true
}

// Numeric parsing shared by JSONObject.getInt/optLong compatibility. Invalid
// syntax is distinct from a parsed non-finite number so callers can fail closed.
func originalCallAPINumber(value any) (float64, bool) {
	var number float64
	switch value := value.(type) {
	case float64:
		number = value
	case float32:
		number = float64(value)
	case int:
		number = float64(value)
	case int32:
		number = float64(value)
	case int64:
		number = float64(value)
	case json.Number:
		return originalCallAPINumber(string(value))
	case string:
		if len(value) > 256 {
			return 0, false
		}
		// Double.valueOf uses String.trim (ASCII control/space), accepts Java
		// floating suffixes and hex exponents, but rejects Go's underscores.
		text := strings.TrimFunc(value, func(r rune) bool { return r <= ' ' })
		if strings.ContainsRune(text, '_') {
			return 0, false
		}
		if strings.HasSuffix(text, "d") || strings.HasSuffix(text, "D") || strings.HasSuffix(text, "f") || strings.HasSuffix(text, "F") {
			text = text[:len(text)-1]
		}
		parsed, err := strconv.ParseFloat(text, 64)
		if err != nil && !math.IsInf(parsed, 0) {
			return 0, false
		}
		number = parsed
	default:
		return 0, false
	}
	return number, true
}

func originalCallAPIFields(action Object) (string, Object, time.Duration, bool, error) {
	invalid := func() (string, Object, time.Duration, bool, error) {
		return "", nil, 0, false, problem(502, "INVALID_CALL_API_ACTION", "Original CallApi requires a pathUrl and bounded nested payload object")
	}
	path, ok := action["pathUrl"].(string)
	if !ok || strings.TrimSpace(path) == "" || len(path) > 2048 {
		return invalid()
	}
	nested, ok := action["payload"].(map[string]any)
	if !ok || nested == nil || len(jsonText(nested)) > 512<<10 {
		return invalid()
	}
	// JSONObject.optLong accepts finite numeric values/strings, truncating
	// toward zero, and defaults malformed values to 5000. Clamp before an int64
	// conversion to bound detached work at 30 seconds without overflow. Parsed
	// non-finite or overlong strings receive an already-expired deadline.
	milliseconds := int64(5000)
	if number, valid := originalCallAPINumber(action["timeout"]); valid {
		switch {
		case math.IsNaN(number), math.IsInf(number, 0), number <= 0:
			milliseconds = 0
		case number > 30000:
			milliseconds = 30000
		default:
			milliseconds = int64(number)
		}
	} else if text, ok := action["timeout"].(string); ok && len(text) > 256 {
		milliseconds = 0
	}
	// optBoolean accepts boolean strings in the Original JSONObject contract.
	wait := false
	switch value := action["wait_result"].(type) {
	case bool:
		wait = value
	case string:
		wait = strings.EqualFold(value, "true")
	}
	return path, nested, time.Duration(milliseconds) * time.Millisecond, wait, nil
}

func (s *Server) reserveCallAPIWorker() bool {
	s.locationMu.Lock()
	defer s.locationMu.Unlock()
	if s.callAPIWorkers == nil {
		s.callAPIWorkers = make(chan struct{}, 8)
	}
	select {
	case s.callAPIWorkers <- struct{}{}:
		return true
	default:
		return false
	}
}

func (s *Server) releaseCallAPIWorker() { <-s.callAPIWorkers }

func (s *Server) callAPIScopeCurrent(principal *Principal) bool {
	var present int
	err := s.db.QueryRow(`SELECT count(*) FROM sessions s JOIN users u ON u.id=s.user_id
		WHERE s.id=? AND s.user_id=? AND coalesce(s.device_id,'')=? AND s.revoked=0 AND s.access_expires>?
		AND (s.device_id IS NULL OR EXISTS(SELECT 1 FROM devices d WHERE d.id=s.device_id AND d.user_id=s.user_id))`,
		principal.SessionID, str(principal.User, "id"), str(principal.Device, "id"), time.Now().Unix()).Scan(&present)
	return err == nil && present == 1
}
