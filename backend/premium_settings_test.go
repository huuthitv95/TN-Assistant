package main

import (
	"testing"
	"time"
)

func TestTrafficSettingsModesThresholdAndAtomicValidation(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	for _, mode := range []string{"none", "display", "sound", "voice"} {
		status, m := call(t, s, "PUT", "/v1/settings", token, Object{"traffic_warning_mode": mode, "traffic_overspeed_offset": -5, "traffic_always_on_map": true})
		requireStatus(t, 200, status, m)
		status, m = call(t, s, "GET", "/v1/settings", token, nil)
		requireStatus(t, 200, status, m)
		if m["traffic_warning_mode"] != mode || m["traffic_enabled"] != (mode != "none") || m["traffic_always_on_map"] != true || m["traffic_overspeed_offset"] != float64(-5) {
			t.Fatal("mode/offset/map setting not persisted consistently")
		}
	}
	for _, invalid := range []Object{{"traffic_warning_mode": "garbage"}, {"traffic_overspeed_offset": 1}, {"traffic_overspeed_offset": "5"}, {"traffic_always_on_map": "true"}} {
		status, m := call(t, s, "PUT", "/v1/settings", token, invalid)
		requireStatus(t, 400, status, m)
		status, m = call(t, s, "GET", "/v1/settings", token, nil)
		requireStatus(t, 200, status, m)
		if m["traffic_warning_mode"] != "voice" || m["traffic_overspeed_offset"] != float64(-5) {
			t.Fatal("invalid update changed saved settings")
		}
	}
	status, m := call(t, s, "PUT", "/v1/settings", token, Object{"traffic_enabled": false})
	requireStatus(t, 200, status, m)
	if m["traffic_warning_mode"] != "none" {
		t.Fatal("legacy switch must disable mode")
	}
	status, m = call(t, s, "PUT", "/v1/settings", token, Object{"traffic_enabled": true})
	requireStatus(t, 200, status, m)
	if m["traffic_warning_mode"] != "sound" {
		t.Fatal("legacy switch must enable sound")
	}
}

func TestPremiumFeaturesFollowCatalogStartAndExpiry(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	status, me := call(t, s, "GET", "/v1/me", token, nil)
	requireStatus(t, 200, status, me)
	uid := str(me["user"].(map[string]any), "id")
	s.put("products", "traffic-only", "", Object{"id": "traffic-only", "features": []any{"driving_info"}})
	past := time.Now().Add(-time.Hour).UTC().Format(time.RFC3339Nano)
	future := time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano)
	s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", "fixture-right", uid, "traffic-only", "fixture", past, future)
	if f := s.premiumFeatures(uid); f["driving_info"] != true || f["wake_word"] != false || f["ai_news"] != false {
		t.Fatal("a traffic-only product must not grant unrelated features")
	}
	s.db.Exec("UPDATE entitlements SET starts_at=? WHERE id=?", future, "fixture-right")
	if s.premiumFeatures(uid)["driving_info"] != false {
		t.Fatal("future entitlement granted early")
	}
	s.db.Exec("UPDATE entitlements SET starts_at=?, expires_at=? WHERE id=?", past, past, "fixture-right")
	if s.premiumFeatures(uid)["driving_info"] != false {
		t.Fatal("expired entitlement granted features")
	}
	s.db.Exec("UPDATE entitlements SET expires_at=? WHERE id=?", future, "fixture-right")
	s.put("products", "traffic-only", "", Object{"id": "traffic-only", "features": []any{"wake_word"}})
	if f := s.premiumFeatures(uid); f["driving_info"] != false || f["wake_word"] != true {
		t.Fatal("catalog changes not reflected")
	}
}

func TestWakeWordIsPremiumOnlyAndExpiredRightsDisableSavedSetting(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	status, settings := call(t, s, "GET", "/v1/settings", token, nil)
	requireStatus(t, 200, status, settings)
	if settings["wake_word_enabled"] != false {
		t.Fatal("Basic must default to wake-word disabled")
	}
	status, denied := call(t, s, "PUT", "/v1/settings", token, Object{"wake_word_enabled": true})
	requireStatus(t, 403, status, denied)
	if str(denied["error"].(map[string]any), "code") != "FEATURE_NOT_AVAILABLE" {
		t.Fatal("Basic wake-word enablement must fail with a feature gate", denied)
	}

	user := g["user"].(map[string]any)
	uid := str(user, "id")
	s.put("products", "wake-word-premium", "", Object{"id": "wake-word-premium", "features": []any{"wake_word"}})
	starts := time.Now().Add(-time.Minute).UTC().Format(time.RFC3339Nano)
	expires := time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano)
	s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", "wake-word-entitlement", uid, "wake-word-premium", "fixture", starts, expires)
	status, enabled := call(t, s, "PUT", "/v1/settings", token, Object{"wake_word_enabled": true})
	requireStatus(t, 200, status, enabled)
	if enabled["wake_word_enabled"] != true {
		t.Fatal("active Premium wake-word entitlement should allow the setting")
	}

	past := time.Now().Add(-time.Minute).UTC().Format(time.RFC3339Nano)
	s.db.Exec("UPDATE entitlements SET expires_at=? WHERE id=?", past, "wake-word-entitlement")
	status, settings = call(t, s, "GET", "/v1/settings", token, nil)
	requireStatus(t, 200, status, settings)
	if settings["wake_word_enabled"] != false {
		t.Fatal("expired entitlement must not retain an enabled Premium setting")
	}
}
