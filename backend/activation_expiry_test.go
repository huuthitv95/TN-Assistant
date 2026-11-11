package main

import (
	"testing"
	"time"
)

// These tests use disposable in-memory testServer state. No external provider or
// running backend is contacted, and generated activation codes stay in memory.
func activationExpiryCreate(t *testing.T, s *Server, admin, expiry string) string {
	t.Helper()
	status, result := call(t, s, "POST", "/v1/admin/activation-codes", admin, Object{
		"product_id": "premium-month", "count": 1, "uses": 1, "expires_at": expiry,
	})
	requireStatus(t, 201, status, result)
	return str(result["items"].([]any)[0].(map[string]any), "code")
}

func activationExpiryState(t *testing.T, s *Server, code string) (int, string) {
	t.Helper()
	var remaining int
	var expiry string
	if err := s.db.QueryRow("SELECT remaining,expires_at FROM activation_codes WHERE code=?", code).Scan(&remaining, &expiry); err != nil {
		t.Fatal(err)
	}
	return remaining, expiry
}

func activationExpiryRequireNoGrant(t *testing.T, s *Server, uid, code string) {
	t.Helper()
	for _, query := range []string{
		"SELECT count(*) FROM entitlements WHERE user_id=?",
		"SELECT count(*) FROM redemptions WHERE user_id=?",
	} {
		var count int
		if err := s.db.QueryRow(query, uid).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if count != 0 {
			t.Fatal("rejected activation changed account rights or redemption state")
		}
	}
	if remaining, _ := activationExpiryState(t, s, code); remaining != 1 {
		t.Fatal("rejected activation consumed a use")
	}
}

func TestActivationExpiryPOSTComparesInstantsAcrossOffsets(t *testing.T) {
	for _, tc := range []struct {
		name   string
		delta  time.Duration
		offset int
		status int
	}{
		{"past_in_positive_offset", -time.Hour, 14 * 60 * 60, 409},
		{"future_in_negative_offset", time.Hour, -12 * 60 * 60, 200},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := testServer(t)
			g := guest(t, s)
			uid := str(g["user"].(map[string]any), "id")
			expires := time.Now().UTC().Add(tc.delta).In(time.FixedZone("offset", tc.offset))
			code := activationExpiryCreate(t, s, adminToken(t, s), expires.Format(time.RFC3339Nano))
			status, result := call(t, s, "POST", "/v1/activation/redeem", str(g, "access_token"), Object{"code": code})
			requireStatus(t, tc.status, status, result)
			if tc.status == 409 {
				if str(result["error"].(map[string]any), "code") != "CODE_EXHAUSTED" {
					t.Fatal("expired code did not use the existing expiry error contract")
				}
				activationExpiryRequireNoGrant(t, s, uid, code)
			}
		})
	}
}

func TestActivationExpiryPUTRejectsInvalidTimestampAtomically(t *testing.T) {
	s := testServer(t)
	admin := adminToken(t, s)
	code := activationExpiryCreate(t, s, admin, time.Now().UTC().Add(time.Hour).Format(time.RFC3339Nano))
	beforeUses, beforeExpiry := activationExpiryState(t, s, code)
	for _, invalid := range []string{"not-a-time", "2030-01-01", "2030-01-01T00:00:00", "2030-13-01T00:00:00Z"} {
		status, result := call(t, s, "PUT", "/v1/admin/activation-codes/"+code, admin, Object{"uses": 5, "expires_at": invalid})
		requireStatus(t, 400, status, result)
		if str(result["error"].(map[string]any), "code") != "INVALID_EXPIRY" {
			t.Fatal("invalid expiry must have the established validation error")
		}
		uses, expiry := activationExpiryState(t, s, code)
		if uses != beforeUses || expiry != beforeExpiry {
			t.Fatal("invalid expiry modified the activation code")
		}
	}
}

func TestActivationExpiryLegacyStoredOffsetsAndInvalidTimesFailClosed(t *testing.T) {
	for _, tc := range []struct {
		name   string
		expiry func() string
		valid  bool
	}{
		{"legacy_past_positive_offset", func() string {
			return time.Now().Add(-time.Hour).In(time.FixedZone("positive", 14*60*60)).Format(time.RFC3339Nano)
		}, false},
		{"legacy_future_negative_offset", func() string {
			return time.Now().Add(time.Hour).In(time.FixedZone("negative", -12*60*60)).Format(time.RFC3339Nano)
		}, true},
		{"invalid_stored_time", func() string { return "zz-invalid-time" }, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := testServer(t)
			g := guest(t, s)
			uid := str(g["user"].(map[string]any), "id")
			code := "TEST_LEGACY_OFFSET"
			if _, err := s.db.Exec("INSERT INTO activation_codes VALUES(?,?,?,?,?)", code, "premium-month", 1, tc.expiry(), now()); err != nil {
				t.Fatal(err)
			}
			status, result := call(t, s, "POST", "/v1/activation/redeem", str(g, "access_token"), Object{"code": code})
			if tc.valid {
				requireStatus(t, 200, status, result)
			} else {
				if status >= 200 && status < 300 {
					t.Fatal("expired or malformed stored expiry was redeemed")
				}
				activationExpiryRequireNoGrant(t, s, uid, code)
			}
		})
	}
}

func TestActivationExpiryEquivalentRFC3339NanoValuesAreNormalized(t *testing.T) {
	s := testServer(t)
	admin := adminToken(t, s)
	expires := time.Now().UTC().Truncate(time.Second).Add(time.Hour + 123456789*time.Nanosecond)
	canonical := expires.Format(time.RFC3339Nano)
	code := activationExpiryCreate(t, s, admin, expires.In(time.FixedZone("negative", -12*60*60)).Format(time.RFC3339Nano))
	if _, stored := activationExpiryState(t, s, code); stored != canonical {
		t.Fatal("POST must normalize an equivalent fractional offset timestamp to UTC")
	}
	status, result := call(t, s, "PUT", "/v1/admin/activation-codes/"+code, admin, Object{
		"uses": 1, "expires_at": expires.In(time.FixedZone("positive", 14*60*60)).Format(time.RFC3339Nano),
	})
	requireStatus(t, 200, status, result)
	if _, stored := activationExpiryState(t, s, code); stored != canonical {
		t.Fatal("PUT changed the instant represented by an equivalent fractional timestamp")
	}
}

func TestActivationExpiryDuplicateSameAccountRemainsIdempotentAndOtherAccountCannotReuse(t *testing.T) {
	s := testServer(t)
	admin := adminToken(t, s)
	a, b := guest(t, s), guest(t, s)
	uid := str(a["user"].(map[string]any), "id")
	code := activationExpiryCreate(t, s, admin, time.Now().UTC().Add(time.Hour).Format(time.RFC3339Nano))
	status, first := call(t, s, "POST", "/v1/activation/redeem", str(a, "access_token"), Object{"code": code})
	requireStatus(t, 200, status, first)
	if first["duplicate"] != false {
		t.Fatal("first redemption was marked duplicate")
	}
	// Revoking the code blocks new uses without undoing an already committed
	// entitlement. Retrying that same account remains a receipt, not a renewal.
	status, result := call(t, s, "DELETE", "/v1/admin/activation-codes/"+code, admin, nil)
	requireStatus(t, 200, status, result)
	status, duplicate := call(t, s, "POST", "/v1/activation/redeem", str(a, "access_token"), Object{"code": code})
	requireStatus(t, 200, status, duplicate)
	if duplicate["duplicate"] != true || str(duplicate, "id") != str(first, "id") {
		t.Fatal("same-account duplicate must return the original receipt")
	}
	var count int
	if err := s.db.QueryRow("SELECT count(*) FROM entitlements WHERE user_id=?", uid).Scan(&count); err != nil || count != 1 {
		t.Fatal("duplicate redemption created another entitlement", err)
	}
	if remaining, _ := activationExpiryState(t, s, code); remaining != 0 {
		t.Fatal("duplicate redemption changed remaining uses")
	}
	status, result = call(t, s, "POST", "/v1/activation/redeem", str(b, "access_token"), Object{"code": code})
	requireStatus(t, 409, status, result)
	var otherCount int
	otherUID := str(b["user"].(map[string]any), "id")
	if err := s.db.QueryRow("SELECT count(*) FROM entitlements WHERE user_id=?", otherUID).Scan(&otherCount); err != nil || otherCount != 0 {
		t.Fatal("exhausted code granted the other account", err)
	}
}
