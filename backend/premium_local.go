package main

import (
	"math"
	"net/http"
	"time"
)

// Local rights belong to one rebuilt account. They never change provider tokens
// or paid upstream authorization. The existing expiry checks remain authoritative.
func (s *Server) localPremium(uid string) Object {
	out := Object{"enabled": false, "local": true, "user_id": uid, "upstream_granted": false}
	for _, e := range s.entitlements(uid) {
		if str(e, "source") == "local-admin:"+uid {
			out["enabled"], out["expires_at"], out["starts_at"] = true, e["expires_at"], e["starts_at"]
		}
	}
	return out
}

func (s *Server) adminLocalPremium(w http.ResponseWriter, r *http.Request, actor *Principal, uid string) error {
	if _, err := s.user(uid); err != nil {
		return err
	}
	if r.Method == "GET" {
		send(w, 200, s.localPremium(uid))
		return nil
	}
	if r.Method != "PUT" && r.Method != "DELETE" {
		return problem(405, "METHOD_NOT_ALLOWED", "GET/PUT/DELETE required")
	}
	days := 0
	if r.Method == "PUT" {
		m, err := body(r)
		if err != nil {
			return err
		}
		value, ok := m["duration_days"].(float64)
		if !ok || value < 1 || value > 365 || math.Trunc(value) != value {
			return problem(400, "INVALID_DURATION", "duration_days must be an integer from 1 to 365")
		}
		for key := range m {
			if key != "duration_days" {
				return problem(400, "INVALID_FIELD", "Only duration_days is accepted")
			}
		}
		days = int(value)
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// Recheck the account inside the transaction in case it was deleted meanwhile.
	var exists string
	if err = tx.QueryRow("SELECT id FROM users WHERE id=?", uid).Scan(&exists); err != nil {
		return err
	}
	clock := time.Now().UTC()
	source := "local-admin:" + uid
	if days > 0 {
		_, err = tx.Exec(`INSERT INTO entitlements VALUES(?,?,?,?,?,?)
			ON CONFLICT(source) DO UPDATE SET starts_at=excluded.starts_at, expires_at=excluded.expires_at`,
			ident(), uid, "premium-local", source, clock.Format(time.RFC3339Nano), clock.AddDate(0, 0, days).Format(time.RFC3339Nano))
	} else {
		_, err = tx.Exec("UPDATE entitlements SET expires_at=? WHERE user_id=? AND source=?", clock.Format(time.RFC3339Nano), uid, source)
	}
	if err != nil {
		return err
	}
	auditID := ident()
	audit := Object{"id": auditID, "kind": "premium-local", "text": "Admin cập nhật Premium local", "created_at": clock.Format(time.RFC3339Nano),
		"result": Object{"actor_id": str(actor.User, "id"), "enabled": days > 0, "duration_days": days, "local": true, "upstream_granted": false}}
	_, err = tx.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES('history',?,?,?,?)", auditID, uid, jsonText(audit), now())
	if err != nil {
		return err
	}
	if err = tx.Commit(); err != nil {
		return err
	}
	send(w, 200, s.localPremium(uid))
	return nil
}
