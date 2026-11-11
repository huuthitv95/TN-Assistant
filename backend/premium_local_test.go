package main

import (
	"testing"
	"time"
)

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
