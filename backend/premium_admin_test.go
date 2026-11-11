package main

import "testing"

func TestAdminPremiumComesFromStoredRoleAndIsRevokedOnDemotion(t *testing.T) {
	s := testServer(t)
	admin := adminToken(t, s)
	status, me := call(t, s, "GET", "/v1/me", admin, nil)
	requireStatus(t, 200, status, me)
	uid := str(me["user"].(map[string]any), "id")
	if len(me["entitlements"].([]any)) != 0 {
		t.Fatal("The role policy must work without a purchased or simulated entitlement")
	}
	for name, enabled := range me["premium_features"].(map[string]any) {
		if enabled != true {
			t.Fatalf("Admin lacks %s", name)
		}
	}
	status, settings := call(t, s, "PUT", "/v1/settings", admin, Object{"wake_word_enabled": true, "ai_mode": "tools"})
	requireStatus(t, 200, status, settings)
	if settings["wake_word_enabled"] != true || settings["ai_mode"] != "tools" {
		t.Fatal("Premium settings were not saved")
	}
	if _, err := s.db.Exec("UPDATE users SET role='user' WHERE id=?", uid); err != nil {
		t.Fatal(err)
	}
	// The same session must lose the role rights immediately, without token rotation.
	status, me = call(t, s, "GET", "/v1/me", admin, nil)
	requireStatus(t, 200, status, me)
	for name, enabled := range me["premium_features"].(map[string]any) {
		if enabled != false {
			t.Fatalf("Demoted account retained %s", name)
		}
	}
	status, denied := call(t, s, "PUT", "/v1/settings", admin, Object{"wake_word_enabled": true})
	requireStatus(t, 403, status, denied)
	status, settings = call(t, s, "GET", "/v1/settings", admin, nil)
	requireStatus(t, 200, status, settings)
	if settings["wake_word_enabled"] != false {
		t.Fatal("Demoted account retained wake activation")
	}
}

func TestRegistrationCannotClaimAdminPremium(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	status, denied := call(t, s, "PUT", "/v1/settings", token, Object{"role": "admin", "wake_word_enabled": true})
	if status != 400 && status != 403 {
		t.Fatalf("Client role field accepted: %d %v", status, denied)
	}
	status, me := call(t, s, "GET", "/v1/me", token, nil)
	requireStatus(t, 200, status, me)
	for name, enabled := range me["premium_features"].(map[string]any) {
		if enabled != false {
			t.Fatalf("Client elevated %s", name)
		}
	}
}
