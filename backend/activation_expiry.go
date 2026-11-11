package main

import "time"

// Store new expiries in UTC, while accepting the RFC3339 offsets already
// supported by the activation API. Empty means that the code has no expiry.
func normalizeActivationExpiry(raw string) (string, error) {
	if raw == "" {
		return "", nil
	}
	expires, err := time.Parse(time.RFC3339Nano, raw)
	if err != nil {
		return "", problem(400, "INVALID_EXPIRY", "RFC3339 expires_at required")
	}
	return expires.UTC().Format(time.RFC3339Nano), nil
}

// Parse legacy rows too: comparing timestamp strings gives incorrect results
// across offsets, and malformed persisted values must not grant access.
func activationExpiryElapsed(raw string, current time.Time) bool {
	if raw == "" {
		return false
	}
	expires, err := time.Parse(time.RFC3339Nano, raw)
	return err != nil || !expires.After(current)
}
