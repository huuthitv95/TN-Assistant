package main

import (
	"testing"
	"time"
)

func TestAccountPlanUsesRoleAndActiveFullPremiumNotPartialEntitlements(t *testing.T) {
	s := testServer(t)
	admin := adminToken(t, s)
	status, me := call(t, s, "GET", "/v1/me", admin, nil)
	requireStatus(t, 200, status, me)
	uid := str(me["user"].(map[string]any), "id")
	plan := me["account_plan"].(map[string]any)
	if plan["kind"] != "admin" || plan["expires_at"] != nil {
		t.Fatal(plan)
	}
	if _, err := time.Parse(time.RFC3339Nano, str(plan, "server_time")); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("UPDATE users SET role='user' WHERE id=?", uid); err != nil {
		t.Fatal(err)
	}
	if s.accountPlan(uid)["kind"] != "basic" {
		t.Fatal("demotion must remove Admin badge")
	}
	past := time.Now().Add(-time.Hour).UTC().Format(time.RFC3339Nano)
	future := time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano)
	if err := s.put("products", "partial", "", Object{"features": []any{"wake_word"}}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", "partial", uid, "partial", "partial", past, future); err != nil {
		t.Fatal(err)
	}
	if s.accountPlan(uid)["kind"] != "basic" {
		t.Fatal("partial capability is not a full Premium subscription")
	}
	if _, err := s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", "full", uid, "premium-local", "local-admin:"+uid, past, future); err != nil {
		t.Fatal(err)
	}
	if p := s.accountPlan(uid); p["kind"] != "premium" || p["expires_at"] != future {
		t.Fatal(p)
	}
	if _, err := s.db.Exec("UPDATE entitlements SET expires_at=? WHERE id='full'", past); err != nil {
		t.Fatal(err)
	}
	if s.accountPlan(uid)["kind"] != "basic" {
		t.Fatal("expired subscription must not survive in presentation")
	}
}
