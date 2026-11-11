package main

import (
	"strings"
	"testing"
	"time"
)

func TestSavedAddressBookIsPremiumScopedValidatesAndSupportsDeletionAfterExpiry(t *testing.T) {
	s := testServer(t)
	a, b := guest(t, s), guest(t, s)
	tokenA, tokenB := str(a, "access_token"), str(b, "access_token")
	status, me := call(t, s, "GET", "/v1/me", tokenA, nil)
	requireStatus(t, 200, status, me)
	uid := str(me["user"].(map[string]any), "id")
	if me["premium_features"].(map[string]any)["address_book"] != false {
		t.Fatal("Basic account unexpectedly has saved destinations")
	}
	status, denied := call(t, s, "POST", "/v1/addresses", tokenA, Object{"name": "Nhà", "address": "12 Đường Hoa"})
	requireStatus(t, 403, status, denied)

	grantTestAI(t, s, a)
	status, me = call(t, s, "GET", "/v1/me", tokenA, nil)
	requireStatus(t, 200, status, me)
	if me["premium_features"].(map[string]any)["address_book"] != true {
		t.Fatal("local Premium did not grant address_book")
	}
	for _, input := range []Object{
		{"name": "", "address": "123 Đường A"},
		{"name": "Nhà", "address": "x"},
		{"name": "Nhà", "address": "12 Đường Hoa", "lat": 10.7},
		{"name": "Nhà", "address": "12 Đường Hoa", "lat": 91.0, "lon": 106.0},
	} {
		status, invalid := call(t, s, "POST", "/v1/addresses", tokenA, input)
		requireStatus(t, 400, status, invalid)
	}

	status, added := call(t, s, "POST", "/v1/addresses", tokenA, Object{"name": "Nhà", "address": "12 Đường Hoa, Quận 1", "lat": 10.762, "lon": 106.66})
	requireStatus(t, 200, status, added)
	item := added["item"].(map[string]any)
	id := str(item, "id")
	if len(id) != 48 || str(item, "name") != "Nhà" || item["lat"] != 10.762 || item["lon"] != 106.66 {
		t.Fatal("saved place shape or coordinates are incorrect", item)
	}
	created := str(item, "created_at")
	status, updated := call(t, s, "POST", "/v1/addresses", tokenA, Object{"name": "  Nhà  ", "address": "25 Đường Hoa, Quận 1"})
	requireStatus(t, 200, status, updated)
	if str(updated["item"].(map[string]any), "id") != id || str(updated["item"].(map[string]any), "created_at") != created {
		t.Fatal("saving the same label should update one entry in place")
	}
	status, list := call(t, s, "GET", "/v1/addresses", tokenA, nil)
	requireStatus(t, 200, status, list)
	items := list["items"].([]any)
	if len(items) != 1 || str(items[0].(map[string]any), "address") != "25 Đường Hoa, Quận 1" {
		t.Fatal("same label created a duplicate or did not update", list)
	}
	status, otherList := call(t, s, "GET", "/v1/addresses", tokenB, nil)
	requireStatus(t, 200, status, otherList)
	if len(otherList["items"].([]any)) != 0 {
		t.Fatal("saved destination leaked across accounts")
	}
	status, crossDelete := call(t, s, "DELETE", "/v1/addresses/"+id, tokenB, nil)
	requireStatus(t, 404, status, crossDelete)

	past := time.Now().Add(-time.Minute).UTC().Format(time.RFC3339Nano)
	s.db.Exec("UPDATE entitlements SET expires_at=? WHERE source=?", past, "local-admin:"+uid)
	status, deletion := call(t, s, "DELETE", "/v1/addresses/"+id, tokenA, nil)
	requireStatus(t, 200, status, deletion)
	status, list = call(t, s, "GET", "/v1/addresses", tokenA, nil)
	requireStatus(t, 200, status, list)
	if len(list["items"].([]any)) != 0 {
		t.Fatal("deleted address remained visible")
	}
}

func TestSavedAddressBookCapsEntriesAndRejectsUnknownOwners(t *testing.T) {
	s := testServer(t)
	g := guest(t, s)
	token := str(g, "access_token")
	grantTestAI(t, s, g)
	for i := 0; i < savedAddressLimit; i++ {
		status, item := call(t, s, "POST", "/v1/addresses", token, Object{"name": "Địa điểm " + string(rune('A'+i)), "address": "Địa chỉ số " + string(rune('A'+i))})
		requireStatus(t, 200, status, item)
	}
	status, full := call(t, s, "POST", "/v1/addresses", token, Object{"name": "Địa điểm vượt giới hạn", "address": "Địa chỉ bổ sung"})
	requireStatus(t, 409, status, full)
	if str(full["error"].(map[string]any), "code") != "ADDRESS_BOOK_FULL" {
		t.Fatal(full)
	}
	status, malformed := call(t, s, "DELETE", "/v1/addresses/"+strings.Repeat("z", 48), token, nil)
	requireStatus(t, 404, status, malformed)
}

func TestSavedDestinationVoiceCommandUsesOnlyTheAuthenticatedOwnersPremiumAddress(t *testing.T) {
	s := testServer(t)
	a, b := guest(t, s), guest(t, s)
	tokenA, tokenB := str(a, "access_token"), str(b, "access_token")
	uidA := str(a["user"].(map[string]any), "id")
	uidB := str(b["user"].(map[string]any), "id")
	s.put("products", "address-book-only", "", Object{"id": "address-book-only", "features": []any{"address_book"}})
	future := time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano)
	past := time.Now().Add(-time.Minute).UTC().Format(time.RFC3339Nano)
	s.db.Exec("INSERT INTO entitlements VALUES(?,?,?,?,?,?)", "saved-address-premium", uidA, "address-book-only", "fixture", past, future)
	status, saved := call(t, s, "POST", "/v1/addresses", tokenA, Object{"name": "Nhà", "address": "25 Đường Hoa, Quận 1", "lat": 10.762, "lon": 106.66})
	requireStatus(t, 200, status, saved)
	status, route := call(t, s, "POST", "/v1/assistant/text", tokenA, Object{"request_id": "saved-home-route", "mode": "commands", "text": "Chỉ đường về nhà"})
	requireStatus(t, 200, status, route)
	if str(route, "source") != "saved_address" || str(route, "utterance") != "Đang chỉ đường đến Nhà." {
		t.Fatal("saved destination was not resolved locally", route)
	}
	directives := route["directives"].([]any)
	if len(directives) != 1 {
		t.Fatal("expected one navigation directive", route)
	}
	directive := directives[0].(map[string]any)
	payload := directive["payload"].(map[string]any)
	point := payload["address"].(map[string]any)
	if directive["type"] != "MapDirection" || str(payload, "query") != "25 Đường Hoa, Quận 1" || point["lat"] != 10.762 || point["lon"] != 106.66 {
		t.Fatal("saved destination lost its route data", route)
	}
	status, other := call(t, s, "POST", "/v1/assistant/text", tokenB, Object{"request_id": "other-home-route", "mode": "commands", "text": "Chỉ đường về nhà"})
	if status != 503 || str(other["error"].(map[string]any), "code") != "PROVIDER_NOT_CONFIGURED" {
		t.Fatal("Basic account should continue through its configured command provider", status, other)
	}
	if str(other, "source") == "saved_address" {
		t.Fatal("another account used the saved destination")
	}
	s.db.Exec("UPDATE entitlements SET expires_at=? WHERE user_id=?", past, uidA)
	status, expired := call(t, s, "POST", "/v1/assistant/text", tokenA, Object{"request_id": "expired-home-route", "mode": "commands", "text": "Chỉ đường về nhà"})
	requireStatus(t, 403, status, expired)
	if str(expired["error"].(map[string]any), "code") != "FEATURE_NOT_AVAILABLE" || uidB == uidA {
		t.Fatal("expired or isolated address-book feature was not enforced")
	}
}
