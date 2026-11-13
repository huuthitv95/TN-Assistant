package main

import (
	"fmt"
	"testing"
)

func segmentFixture() Object {
	m := managedFixtureRoad("directed-road", 50.0)
	m["latitude"], m["longitude"], m["bearing"] = 10.0, 106.0, 0.0
	m["start_latitude"], m["start_longitude"] = 9.9998, 106.0
	m["end_latitude"], m["end_longitude"] = 10.0002, 106.0
	m["road_group"] = "owned-north"
	return m
}

func TestManagedSegmentsRejectIncompleteAndMisleadingGeometry(t *testing.T) {
	if err := validateManagedRoad(segmentFixture()); err != nil {
		t.Fatal(err)
	}
	for _, change := range []func(Object){
		func(m Object) { delete(m, "end_longitude") },
		func(m Object) { m["end_latitude"] = 11.0 },
		func(m Object) { m["bearing"] = 180.0 },
		func(m Object) { m["latitude"] = 10.01 },
		func(m Object) { m["road_group"] = "" },
		func(m Object) { m["kind"] = "camera" },
		func(m Object) { m["start_latitude"] = "9.9998" },
	} {
		m := segmentFixture()
		change(m)
		if validateManagedRoad(m) == nil {
			t.Fatal("Invalid segment accepted", m)
		}
	}
	if err := validateManagedRoad(managedFixtureRoad("legacy", 50.0)); err != nil {
		t.Fatal("Point-only data must remain compatible", err)
	}
}

func TestManagedRoadCandidatesRetainUnknownLimitsAndDoNotExposeRawTags(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	admin := adminToken(t, s)
	status, m := call(t, s, "PUT", "/v1/admin/managed/config", admin, Object{"driving_enabled": true, "local_tts_url": "http://127.0.0.1:8092", "tts_voice": "test"})
	requireStatus(t, 200, status, m)
	known := segmentFixture()
	known["osm_tags"] = Object{"note": "not a public field"}
	unknown := segmentFixture()
	unknown["id"] = "unknown"
	unknown["max_speed"] = nil
	unknown["road_group"] = "other-road"
	status, m = call(t, s, "POST", "/v1/admin/managed/roads/import", admin, Object{"items": []Object{known, unknown}})
	requireStatus(t, 200, status, m)
	query := Object{"lat": 10.0, "lon": 106.0, "bearing": 0.0}
	status, m = call(t, s, "POST", "/v1/driving/area/speedlimit", token, query)
	requireStatus(t, 403, status, m)
	grantDrivingFixture(t, s, g)
	for _, radius := range []any{nil, "150", 99.0, 2501.0} {
		status, m = call(t, s, "POST", "/v1/driving/area/speedlimit", token, Object{"lat": 10.0, "lon": 106.0, "bearing": 0.0, "radius_m": radius})
		requireStatus(t, 400, status, m)
	}
	query["radius_m"] = 150.0
	status, m = call(t, s, "POST", "/v1/driving/area/speedlimit", token, query)
	requireStatus(t, 200, status, m)
	if m["radius_m"] != 150.0 {
		t.Fatal("Managed query ignored its explicit radius")
	}
	if len(m["data"].([]any)) != 1 || len(m["road_candidates"].([]any)) != 2 || m["road_candidates_complete"] != true || m["road_candidates_version"] != float64(1) {
		t.Fatal("Unsafe/incompatible candidate response", m)
	}
	for _, raw := range m["road_candidates"].([]any) {
		row := raw.(map[string]any)
		if row["osm_tags"] != nil || row["road_group"] == nil || row["start_latitude"] == nil {
			t.Fatal("Bad candidate contract", row)
		}
	}
	if _, err := s.db.Exec("UPDATE managed_roads SET data='{}' WHERE id='unknown'"); err != nil {
		t.Fatal(err)
	}
	status, m = call(t, s, "POST", "/v1/driving/area/speedlimit", token, query)
	requireStatus(t, 200, status, m)
	if m["road_candidates_complete"] != false {
		t.Fatal("Corrupt records were silently treated as complete coverage")
	}
}

func TestManagedRoadScanBoundNeverClaimsCompleteCandidates(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	admin := adminToken(t, s)
	status, reply := call(t, s, "PUT", "/v1/admin/managed/config", admin, Object{"driving_enabled": true, "local_tts_url": "http://127.0.0.1:8092", "tts_voice": "test"})
	requireStatus(t, 200, status, reply)
	grantDrivingFixture(t, s, g)
	tx, err := s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	for i := 0; i < 10001; i++ {
		row := segmentFixture()
		row["id"] = fmt.Sprintf("bounded-%d", i)
		if err := managedPutRoad(tx, row); err != nil {
			t.Fatal(err)
		}
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	status, reply = call(t, s, "POST", "/v1/driving/area/speedlimit", token, Object{"lat": 10.0, "lon": 106.0, "bearing": 0.0})
	requireStatus(t, 200, status, reply)
	if reply["road_candidates_complete"] != false || len(reply["road_candidates"].([]any)) != 10000 {
		t.Fatal("Truncated candidate query was not marked incomplete")
	}
}
