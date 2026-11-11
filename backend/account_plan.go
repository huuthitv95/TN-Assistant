package main

import (
	"strings"
	"time"
)

// Presentation only. Authorization continues to use premiumFeatures on every request.
func (s *Server) accountPlan(uid string) Object {
	clock := time.Now().UTC()
	result := Object{"kind": "basic", "expires_at": nil, "server_time": clock.Format(time.RFC3339Nano)}
	var role string
	if err := s.db.QueryRow("SELECT role FROM users WHERE id=?", uid).Scan(&role); err == nil && role == "admin" {
		result["kind"] = "admin"
		return result
	}
	var end time.Time
	for _, e := range s.entitlements(uid) {
		qualifies := strings.HasPrefix(str(e, "source"), "local-admin:")
		if !qualifies {
			product, err := s.record("products", str(e, "product_id"))
			if err == nil {
				values, _ := product["features"].([]any)
				for _, value := range values {
					if value == "premium" {
						qualifies = true
					}
				}
			}
		}
		if !qualifies {
			continue
		}
		expiry, err := time.Parse(time.RFC3339Nano, str(e, "expires_at"))
		if err == nil && expiry.After(clock) && expiry.After(end) {
			end = expiry
		}
	}
	if !end.IsZero() {
		result["kind"] = "premium"
		result["expires_at"] = end.UTC().Format(time.RFC3339Nano)
	}
	return result
}
