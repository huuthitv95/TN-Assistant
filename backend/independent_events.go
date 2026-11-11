package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"time"
)

var deviceEventIdentifier = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$`)
var deviceEventKind = regexp.MustCompile(`^[a-z][a-z0-9_.-]{0,63}$`)

// Own device receipts are plain TN JSON. They never forward legacy RSA/AES
// envelopes, choose provider URLs or assert that an external action succeeded.
func (s *Server) deviceIngest(w http.ResponseWriter, r *http.Request, principal *Principal) error {
	if r.Method != "POST" {
		return problem(405, "METHOD_NOT_ALLOWED", "POST required")
	}
	if !s.independentRuntime() {
		return problem(409, "INDEPENDENT_RUNTIME_REQUIRED", "TN device events require independent runtime")
	}
	uid, did := str(principal.User, "id"), str(principal.Device, "id")
	if did == "" {
		return problem(403, "DEVICE_SESSION_REQUIRED", "An owned device-bound session is required")
	}
	cfg, err := s.runtimeConfig()
	if err != nil {
		return err
	}
	m, err := body(r)
	if err != nil {
		return err
	}
	resultRoute := r.URL.Path == "/v1/device/actions/results"
	kind, id, requestID, actionID := "device_event", str(m, "event_id"), "", ""
	allowed := []string{"event_id", "kind", "occurred_at", "data"}
	if resultRoute {
		allowed = []string{"request_id", "action_id", "result", "details"}
	}
	for field := range m {
		if !allowedType(field, allowed) {
			return problem(400, "INVALID_DEVICE_EVENT", "Unknown device event field; legacy envelopes are unsupported")
		}
	}
	if resultRoute {
		kind = "device_action_result"
		requestID, actionID = str(m, "request_id"), str(m, "action_id")
		if !deviceEventIdentifier.MatchString(requestID) || !deviceEventIdentifier.MatchString(actionID) {
			return problem(400, "INVALID_ACTION_RESULT", "Valid request_id and action_id are required")
		}
		if !allowedType(str(m, "result"), []string{"success", "failed", "unsupported", "cancelled", "completed"}) {
			return problem(400, "INVALID_ACTION_RESULT", "A supported action result is required")
		}
		if details, exists := m["details"]; exists {
			if _, valid := details.(map[string]any); !valid || len(jsonText(details)) > 16<<10 {
				return problem(400, "INVALID_ACTION_RESULT", "details must be an object of at most 16 KiB")
			}
		}
		id = requestID + "\x00" + actionID
	} else {
		if !deviceEventIdentifier.MatchString(id) || !deviceEventKind.MatchString(str(m, "kind")) {
			return problem(400, "INVALID_DEVICE_EVENT", "Valid event_id and event kind are required")
		}
		stamp, parseErr := time.Parse(time.RFC3339Nano, str(m, "occurred_at"))
		if parseErr != nil || stamp.After(time.Now().Add(5*time.Minute)) {
			return problem(400, "INVALID_DEVICE_EVENT", "occurred_at must be a valid timestamp within five minutes of server time or earlier")
		}
		data, valid := m["data"].(map[string]any)
		if !valid || len(jsonText(data)) > 32<<10 {
			return problem(400, "INVALID_DEVICE_EVENT", "data must be an object of at most 32 KiB")
		}
	}
	key := hash(uid + "\x00" + did + "\x00" + id)
	fingerprint := hash(jsonText(m))
	privateResult := resultRoute && s.aiSettings(uid)["private_mode"] == true
	privateEvent := !resultRoute && s.aiSettings(uid)["private_mode"] == true
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var currentPrivate int
	err = tx.QueryRow("SELECT coalesce(json_extract(data,'$.private_mode'),0) FROM settings WHERE user_id=?", uid).Scan(&currentPrivate)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	privateResult = privateResult || (resultRoute && currentPrivate == 1)
	privateEvent = privateEvent || (!resultRoute && currentPrivate == 1)
	if privateEvent {
		// Keep only a privacy-safe idempotency projection. Hashing the original
		// payload would retain a guessable fingerprint of titles or locations.
		fingerprint = hash(jsonText(Object{"event_id": id, "kind": str(m, "kind"), "occurred_at": str(m, "occurred_at")}))
	}
	// Ownership and session revocation are checked again in the same transaction
	// as the receipt, closing admin reset/unlink races after HTTP authentication.
	var live int
	if err = tx.QueryRow("SELECT count(*) FROM sessions s JOIN devices d ON d.id=s.device_id WHERE s.id=? AND s.user_id=? AND d.user_id=? AND d.id=? AND s.revoked=0 AND s.access_expires>unixepoch()", principal.SessionID, uid, uid, did).Scan(&live); err != nil {
		return err
	}
	if live != 1 {
		return problem(401, "DEVICE_SESSION_REVOKED", "Device session or ownership changed")
	}
	var raw string
	err = tx.QueryRow("SELECT data FROM records WHERE kind=? AND id=? AND owner=?", kind, key, uid).Scan(&raw)
	if err == nil {
		var previous Object
		if err = json.Unmarshal([]byte(raw), &previous); err != nil {
			return err
		}
		if str(previous, "fingerprint") != fingerprint {
			return problem(409, "DEVICE_EVENT_CONFLICT", "Event identifier was already used for different data")
		}
		if integer(previous, "generation", 0) != cfg.Generation {
			return problem(409, "DEVICE_EVENT_RETIRED", "Event belongs to a retired runtime generation")
		}
		out := Object{"accepted": true, "replayed": true, "generation": cfg.Generation}
		if resultRoute {
			out["request_id"], out["action_id"] = requestID, actionID
		} else {
			out["event_id"] = id
		}
		send(w, 200, out)
		return nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if resultRoute {
		requestKey := hash(uid+"\x00"+did) + ":" + requestID
		if err = tx.QueryRow("SELECT data FROM records WHERE kind='assistant_request' AND id=? AND owner=?", requestKey, uid).Scan(&raw); err != nil {
			return problem(404, "ACTION_NOT_FOUND", "Action was not issued to this account and device")
		}
		var request Object
		if err = json.Unmarshal([]byte(raw), &request); err != nil {
			return err
		}
		if str(request, "status") != "complete" || integer(request, "runtime_generation", 0) != cfg.Generation {
			return problem(409, "ACTION_RUNTIME_RETIRED", "Action belongs to an incomplete or retired runtime request")
		}
		out, _ := request["result"].(map[string]any)
		found := false
		if request["private_mode"] == true {
			privateResult = true
		}
		issued, _ := request["issued_action_ids"].([]any)
		for _, value := range issued {
			if issuedID, ok := value.(string); ok && issuedID == actionID {
				found = true
				break
			}
		}
		for _, directive := range objectDirectives(out) {
			payload, _ := directive["payload"].(map[string]any)
			if str(directive, "action_id") == actionID || str(payload, "action_id") == actionID {
				found = true
				break
			}
		}
		if !found {
			return problem(404, "ACTION_NOT_FOUND", "Action was not issued to this account and device")
		}
	}
	entry := Object{"id": key, "owner": uid, "device_id": did, "fingerprint": fingerprint, "generation": cfg.Generation, "received_at": now(), "payload": m}
	if privateResult {
		entry["private_mode"] = true
		entry["payload"] = Object{"request_id": requestID, "action_id": actionID, "result": str(m, "result")}
	} else if privateEvent {
		entry["private_mode"] = true
		entry["payload"] = Object{"event_id": id, "kind": str(m, "kind"), "occurred_at": str(m, "occurred_at")}
	}
	if _, err = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES(?,?,?,?,?)", kind, key, uid, jsonText(entry), entry["received_at"]); err != nil {
		return err
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	out := Object{"accepted": true, "replayed": false, "generation": cfg.Generation}
	if resultRoute {
		out["request_id"], out["action_id"] = requestID, actionID
	} else {
		out["event_id"] = id
	}
	send(w, 200, out)
	return nil
}
