package main

import (
	"strings"
	"testing"
)

func TestRepairCodeDoesNotFallBackToLatestOrUnpublished(t *testing.T) {
	s := testServer(t)
	token := str(guest(t, s), "access_token")
	for id, m := range map[string]Object{
		"latest": {"version_code": 999, "version_name": "fixture-latest", "sha256": strings.Repeat("a", 64), "url": "/downloads/fixture.apk", "published": true},
		"hidden": {"version_code": 5, "repair_code": "HIDE", "published": false},
		"repair": {"version_code": 4, "version_name": "fixture-repair", "sha256": strings.Repeat("b", 64), "url": "/downloads/fixture-repair.apk", "repair_code": "AB12", "published": true},
	} {
		if err := s.put("releases", id, "", m); err != nil {
			t.Fatal(err)
		}
	}
	for _, code := range []string{"ZZZZ", "HIDE", "ABC", "ABCDE", "%2F%2F%2F%2F"} {
		status, out := call(t, s, "GET", "/v1/releases/repair/"+code, token, nil)
		requireStatus(t, 404, status, out)
		if str(out["error"].(map[string]any), "code") != "REPAIR_NOT_FOUND" {
			t.Fatal(out)
		}
	}
	status, out := call(t, s, "GET", "/v1/releases/repair/ab12", token, nil)
	requireStatus(t, 200, status, out)
	if integer(out, "version_code", 0) != 4 || str(out, "version_name") != "fixture-repair" {
		t.Fatal(out)
	}
	status, out = call(t, s, "GET", "/v1/releases/repair/AB12", "", nil)
	requireStatus(t, 401, status, out)
}
func TestAmbiguousPublishedRepairCodeCannotChooseAnApk(t *testing.T) {
	s := testServer(t)
	token := str(guest(t, s), "access_token")
	for _, id := range []string{"first", "second"} {
		if err := s.put("releases", id, "", Object{"repair_code": "AB12", "version_code": 4, "published": true}); err != nil {
			t.Fatal(err)
		}
	}
	status, out := call(t, s, "GET", "/v1/releases/repair/AB12", token, nil)
	requireStatus(t, 409, status, out)
}
