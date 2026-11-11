package main

import "strings"

func repairCode(value string) (string, bool) {
	code := strings.ToUpper(strings.TrimSpace(value))
	if len(code) != 4 {
		return "", false
	}
	for _, c := range code {
		if !(c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
			return "", false
		}
	}
	return code, true
}

// A repair code maps only to a published release explicitly assigned that code.
// Never fall back to latest: a missing/ambiguous code cannot select another APK.
func (s *Server) repairRelease(value string) (Object, error) {
	code, valid := repairCode(value)
	if !valid {
		return nil, problem(404, "REPAIR_NOT_FOUND", "Repair release not found")
	}
	items, err := s.list("releases", "", "")
	if err != nil {
		return nil, err
	}
	var found Object
	for _, release := range items {
		assigned, ok := repairCode(str(release, "repair_code"))
		if !ok || assigned != code || release["published"] == false {
			continue
		}
		if found != nil {
			return nil, problem(409, "REPAIR_CODE_AMBIGUOUS", "Repair code has multiple published releases")
		}
		found = release
	}
	if found == nil {
		return nil, problem(404, "REPAIR_NOT_FOUND", "Repair release not found")
	}
	found["available"] = true
	return found, nil
}
