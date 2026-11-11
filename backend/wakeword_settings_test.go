package main

import "testing"

func TestWakewordSharingPreferenceIsOptInAndSeparateFromActivation(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	status, initial := call(t, s, "GET", "/v1/settings", token, nil)
	requireStatus(t, 200, status, initial)
	if initial["wake_word_data_sharing"] != false {
		t.Fatal("sharing must not be enabled by microphone activation defaults")
	}
	for _, enabled := range []bool{true, false} {
		status, saved := call(t, s, "PUT", "/v1/settings", token, Object{"wake_word_data_sharing": enabled})
		requireStatus(t, 200, status, saved)
		status, actual := call(t, s, "GET", "/v1/settings", token, nil)
		requireStatus(t, 200, status, actual)
		if actual["wake_word_data_sharing"] != enabled || actual["wake_word_enabled"] != initial["wake_word_enabled"] || actual["private_mode"] != initial["private_mode"] {
			t.Fatal("sharing preference must persist independently")
		}
	}
	for _, invalid := range []any{"true", 1, nil, []any{true}} {
		status, m := call(t, s, "PUT", "/v1/settings", token, Object{"wake_word_data_sharing": invalid})
		requireStatus(t, 400, status, m)
		status, actual := call(t, s, "GET", "/v1/settings", token, nil)
		requireStatus(t, 200, status, actual)
		if actual["wake_word_data_sharing"] != false {
			t.Fatal("invalid update changed the stored preference")
		}
	}
}
