package main

import (
	"testing"
	"time"
)

func TestLocalAdminSourceMustMatchTheEntitledUser(t *testing.T) {
	s := testServer(t)
	a, b := guest(t, s), guest(t, s)
	uid := str(a["user"].(map[string]any), "id")
	otherUID := str(b["user"].(map[string]any), "id")
	past := time.Now().Add(-time.Hour).UTC().Format(time.RFC3339Nano)
	future := time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano)
	if err := s.put("products", "premium-local", "", Object{"features": []any{"premium"}}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", ident(), uid, "premium-local", "local-admin:"+otherUID, past, future); err != nil {
		t.Fatal(err)
	}
	if len(s.entitlements(uid)) != 0 {
		t.Fatal("an active grant must belong to the requesting account")
	}
	for feature, enabled := range s.premiumFeatures(uid) {
		if enabled != false {
			t.Fatalf("mismatched local-admin source granted %s", feature)
		}
	}
	if s.accountPlan(uid)["kind"] != "basic" {
		t.Fatal("mismatched local-admin source must not present Premium account plan")
	}
	if _, err := s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", ident(), uid, "premium-local", "local-admin:"+uid, past, future); err != nil {
		t.Fatal(err)
	}
	for feature, enabled := range s.premiumFeatures(uid) {
		if enabled != true {
			t.Fatalf("matching local-admin source did not grant %s", feature)
		}
	}
}

func TestLocalPremiumRBACExpiryRevocationAndAccountIsolation(t *testing.T) {
	s := testServer(t)
	a, b := guest(t, s), guest(t, s)
	uid := str(a["user"].(map[string]any), "id")
	token := str(a, "access_token")
	path := "/v1/admin/premium-local/" + uid
	for _, method := range []string{"GET", "PUT", "DELETE"} {
		status, m := call(t, s, method, path, token, Object{"duration_days": 30})
		requireStatus(t, 403, status, m)
	}
	admin := adminToken(t, s)
	for _, value := range []any{0, -1, 366, 1.5, "30", nil} {
		status, m := call(t, s, "PUT", path, admin, Object{"duration_days": value})
		requireStatus(t, 400, status, m)
	}
	status, m := call(t, s, "PUT", path, admin, Object{"duration_days": 30, "user_id": "other"})
	requireStatus(t, 400, status, m)
	status, m = call(t, s, "PUT", "/v1/admin/premium-local/missing", admin, Object{"duration_days": 30})
	requireStatus(t, 404, status, m)
	status, m = call(t, s, "PUT", path, admin, Object{"duration_days": 30})
	requireStatus(t, 200, status, m)
	if m["enabled"] != true || m["upstream_granted"] != false {
		t.Fatal(m)
	}
	for feature, enabled := range s.premiumFeatures(uid) {
		if enabled != true {
			t.Fatalf("%s was not granted", feature)
		}
	}
	other := str(b["user"].(map[string]any), "id")
	if s.premiumFeatures(other)["ai_news"] != false {
		t.Fatal("local grant leaked across accounts")
	}
	// A conflicting source must not renew an entitlement owned by another account.
	if _, err := s.db.Exec("DELETE FROM entitlements WHERE user_id=? AND source=?", uid, "local-admin:"+uid); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", ident(), other, "premium-local", "local-admin:"+uid, time.Now().Add(-time.Hour).UTC().Format(time.RFC3339Nano), time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano)); err != nil {
		t.Fatal(err)
	}
	status, m = call(t, s, "PUT", path, admin, Object{"duration_days": 1})
	requireStatus(t, 409, status, m)
	var owner string
	if err := s.db.QueryRow("SELECT user_id FROM entitlements WHERE source=?", "local-admin:"+uid).Scan(&owner); err != nil || owner != other {
		t.Fatalf("conflicting local grant was modified: owner=%q err=%v", owner, err)
	}
	if _, err := s.db.Exec("DELETE FROM entitlements WHERE source=?", "local-admin:"+uid); err != nil {
		t.Fatal(err)
	}
	status, m = call(t, s, "PUT", path, admin, Object{"duration_days": 1})
	requireStatus(t, 200, status, m)
	var count int
	s.db.QueryRow("SELECT count(*) FROM entitlements WHERE source=?", "local-admin:"+uid).Scan(&count)
	if count != 1 {
		t.Fatal("renewal duplicated entitlement")
	}
	past := time.Now().Add(-time.Hour).UTC().Format(time.RFC3339Nano)
	s.db.Exec("UPDATE entitlements SET expires_at=? WHERE source=?", past, "local-admin:"+uid)
	if s.localPremium(uid)["enabled"] != false || s.premiumFeatures(uid)["ai_news"] != false {
		t.Fatal("expired local grant still enabled")
	}
	status, m = call(t, s, "PUT", path, admin, Object{"duration_days": 30})
	requireStatus(t, 200, status, m)
	s.put("products", "test-wake", "", Object{"id": "test-wake", "features": []any{"wake_word"}})
	s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", ident(), uid, "test-wake", "paid-test", past, time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano))
	status, m = call(t, s, "DELETE", path, admin, nil)
	requireStatus(t, 200, status, m)
	if m["enabled"] != false || s.premiumFeatures(uid)["ai_news"] != false || s.premiumFeatures(uid)["wake_word"] != true {
		t.Fatal("revocation removed another product's rights")
	}
	status, m = call(t, s, "GET", "/v1/me", token, nil)
	requireStatus(t, 200, status, m)
	if m["local_premium"].(map[string]any)["enabled"] != false {
		t.Fatal(m)
	}
	s.db.QueryRow("SELECT count(*) FROM records WHERE kind='history' AND owner=?", uid).Scan(&count)
	if count != 4 {
		t.Fatal("grant/renew/revoke must be audited", count)
	}
}
