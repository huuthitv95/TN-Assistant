package main

import "strings"

// TN administrators have every TN Premium capability. Other accounts require
// active, started entitlements and their catalog records. This policy does not
// grant paid upstream services or bypass provider readiness checks.
func (s *Server) premiumFeatures(uid string) Object {
	features := Object{"driving_info": false, "ai_news": false, "wake_word": false, "ai_assistant": false, "ai_tools": false, "address_book": false}
	var role string
	if err := s.db.QueryRow("SELECT role FROM users WHERE id=?", uid).Scan(&role); err == nil && role == "admin" {
		for k := range features {
			features[k] = true
		}
		return features
	}
	for _, entitlement := range s.entitlements(uid) {
		if strings.HasPrefix(str(entitlement, "source"), "local-admin:") {
			for k := range features {
				features[k] = true
			}
			continue
		}
		product, err := s.record("products", str(entitlement, "product_id"))
		if err != nil || product == nil {
			continue
		}
		values, _ := product["features"].([]any)
		for _, value := range values {
			name, ok := value.(string)
			if !ok {
				continue
			}
			if name == "premium" {
				for k := range features {
					features[k] = true
				}
			} else if _, known := features[name]; known {
				features[name] = true
			}
		}
	}
	return features
}
