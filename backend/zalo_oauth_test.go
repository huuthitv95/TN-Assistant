package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// These tests use an isolated HTTP fixture, never an Original identity or an
// upstream Zalo account. The real authorization origin stays fixed in start.
func zaloFixture(t *testing.T, s *Server, handler http.HandlerFunc) {
	t.Helper()
	t.Setenv("ZALO_OAUTH_TEST_SECRET", "fixture-zalo-secret")
	if err := s.saveProviders(Object{"zalo": Object{"mode": "generic", "secret_env": "ZALO_OAUTH_TEST_SECRET", "options": Object{"app_id": "123456789", "redirect_uri": "https://tn.example/v1/auth/zalo/callback"}}}); err != nil {
		t.Fatal(err)
	}
	fixture := httptest.NewServer(handler)
	tokenEndpoint, profileEndpoint := zaloTokenEndpoint, zaloProfileEndpoint
	zaloTokenEndpoint, zaloProfileEndpoint = fixture.URL+"/token", fixture.URL+"/me?fields=id,name"
	t.Cleanup(func() {
		zaloTokenEndpoint, zaloProfileEndpoint = tokenEndpoint, profileEndpoint
		fixture.Close()
	})
}

func zaloStartFixture(t *testing.T, s *Server, token, device string) Object {
	t.Helper()
	status, reply := call(t, s, "POST", "/v1/auth/zalo/start", token, Object{"device_id": device, "device_name": "Fixture device"})
	requireStatus(t, 200, status, reply)
	return reply
}

func zaloCompleteFixture(t *testing.T, s *Server, start Object, device string) (int, Object) {
	t.Helper()
	return call(t, s, "POST", "/v1/auth/zalo/complete", "", Object{"state": start["state"], "client_proof": start["client_proof"], "device_id": device, "code": "fixture-authorization-code"})
}

func TestZaloOAuthPKCEProofReplayAndSessionIssuance(t *testing.T) {
	s := testServer(t)
	var tokenCalls, profileCalls atomic.Int32
	var challenge string
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/token":
			tokenCalls.Add(1)
			r.ParseForm()
			verifier := r.FormValue("code_verifier")
			digest := sha256.Sum256([]byte(verifier))
			if r.Method != "POST" || r.Header.Get("secret_key") != "fixture-zalo-secret" || r.FormValue("grant_type") != "authorization_code" || r.FormValue("app_id") != "123456789" || r.FormValue("code") != "fixture-authorization-code" || len(verifier) != 43 || !strings.ContainsAny(verifier, "ABCDEFGHIJKLMNOPQRSTUVWXYZ") || !strings.ContainsAny(verifier, "abcdefghijklmnopqrstuvwxyz") || !strings.ContainsAny(verifier, "0123456789") || base64.RawURLEncoding.EncodeToString(digest[:]) != challenge {
				t.Error("OAuth exchange lost the registered app, secret header or PKCE proof")
			}
			send(w, 200, Object{"access_token": "fixture-upstream-access", "refresh_token": "fixture-upstream-refresh", "expires_in": 3600})
		case "/me":
			profileCalls.Add(1)
			proof := hmac.New(sha256.New, []byte("fixture-zalo-secret"))
			proof.Write([]byte("fixture-upstream-access"))
			if r.Method != "GET" || r.Header.Get("access_token") != "fixture-upstream-access" || r.URL.Query().Get("fields") != "id,name" || r.Header.Get("appsecret_proof") != hex.EncodeToString(proof.Sum(nil)) {
				t.Error("profile did not use the verified upstream token")
				send(w, 200, Object{"error": -242, "message": "Invalid appsecret_proof"})
				return
			}
			send(w, 200, Object{"error": 0, "id": "fixture-subject", "name": "Fixture account"})
		default:
			t.Error("unexpected upstream path")
		}
	})
	device := "fixture-fresh-device"
	start := zaloStartFixture(t, s, "", device)
	authURL, err := url.Parse(str(start, "url"))
	if err != nil || authURL.Scheme != "https" || authURL.Host != "oauth.zaloapp.com" || authURL.Path != "/v4/permission" {
		t.Fatal("authorization URL did not stay on the official Zalo origin")
	}
	query := authURL.Query()
	challenge = query.Get("code_challenge")
	if len(challenge) != 43 || query.Get("state") != str(start, "state") || query.Get("redirect_uri") != str(start, "callback_url") || len(str(start, "client_proof")) != 43 || strings.Contains(str(start, "url"), str(start, "client_proof")) || strings.Contains(jsonText(start), "fixture-zalo-secret") {
		t.Fatal("start response leaked credentials or omitted PKCE/state/client proof")
	}
	qrURL, err := url.Parse(str(start, "qr_payload"))
	pairCode := str(start, "user_code")
	if err != nil || qrURL.Scheme != "https" || qrURL.Host != "tn.example" || qrURL.Path != "/v1/auth/zalo/continue" || qrURL.Query().Get("state") != str(start, "state") || len(pairCode) != 6 {
		t.Fatal("start response did not provide a same-origin QR URL and six-digit confirmation code")
	}
	for _, c := range pairCode {
		if c < '0' || c > '9' {
			t.Fatal("confirmation code must contain digits only")
		}
	}
	status, rejected := call(t, s, "POST", "/v1/auth/zalo/complete", "", Object{"state": start["state"], "client_proof": "wrong-proof", "device_id": device, "code": "fixture-authorization-code"})
	requireStatus(t, 409, status, rejected)
	status, rejected = call(t, s, "POST", "/v1/auth/zalo/complete", "", Object{"state": start["state"], "client_proof": start["client_proof"], "device_id": "other-device", "code": "fixture-authorization-code"})
	requireStatus(t, 409, status, rejected)
	if tokenCalls.Load() != 0 {
		t.Fatal("invalid local proof reached upstream")
	}
	status, reply := zaloCompleteFixture(t, s, start, device)
	requireStatus(t, 200, status, reply)
	if str(reply, "access_token") == "" || str(reply, "refresh_token") == "" || str(reply["user"].(map[string]any), "role") != "user" || str(reply["user"].(map[string]any), "name") != "Fixture account" {
		t.Fatal("verified identity did not issue an ordinary TN session")
	}
	if strings.Contains(jsonText(reply), "fixture-upstream") || strings.Contains(jsonText(reply), "fixture-subject") || strings.Contains(jsonText(reply), "fixture-zalo-secret") {
		t.Fatal("upstream identity or credential escaped in the TN session response")
	}
	principal, err := s.authenticate(str(reply, "access_token"))
	if err != nil || str(principal.Device, "id") != device || len(s.entitlements(str(principal.User, "id"))) != 0 {
		t.Fatal("issued token is invalid or Zalo sign-in implicitly granted Premium")
	}
	var identityOwner, identityData string
	if err := s.db.QueryRow("SELECT owner,data FROM records WHERE kind='identities'").Scan(&identityOwner, &identityData); err != nil || identityOwner != str(principal.User, "id") || strings.Contains(identityData, "fixture-subject") || strings.Contains(identityData, "fixture-upstream") {
		t.Fatalf("identity record did not persist only its hashed association: %v", err)
	}
	status, rejected = zaloCompleteFixture(t, s, start, device)
	requireStatus(t, 409, status, rejected)
	if tokenCalls.Load() != 1 || profileCalls.Load() != 1 {
		t.Fatal("a replay repeated the upstream exchange")
	}
}

func TestZaloOAuthCallbackCannotIssueSessionAndPinsItsCode(t *testing.T) {
	s := testServer(t)
	var exchanges atomic.Int32
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		exchanges.Add(1)
		if r.URL.Path == "/token" {
			send(w, 200, Object{"access_token": "fixture-upstream"})
		} else {
			send(w, 200, Object{"error": 0, "id": "callback-subject"})
		}
	})
	start := zaloStartFixture(t, s, "", "fixture-callback-device")
	qr := httptest.NewRecorder()
	qrRequest := httptest.NewRequest("GET", str(start, "qr_payload"), nil)
	s.Handler().ServeHTTP(qr, qrRequest)
	if qr.Code != 200 || qr.Header().Get("Cache-Control") != "no-store" || !strings.Contains(qr.Header().Get("Content-Security-Policy"), "default-src 'none'") || !strings.Contains(qr.Body.String(), str(start, "user_code")) || !strings.Contains(qr.Body.String(), "oauth.zaloapp.com") || strings.Contains(qr.Body.String(), str(start, "client_proof")) {
		t.Fatal("QR landing did not show a guarded confirmation page with the official Zalo authorization link")
	}
	status, statusReply := call(t, s, "POST", "/v1/auth/zalo/status", "", Object{"state": start["state"], "client_proof": "wrong-proof", "device_id": "fixture-callback-device"})
	requireStatus(t, 409, status, statusReply)
	status, statusReply = call(t, s, "POST", "/v1/auth/zalo/status", "", Object{"state": start["state"], "client_proof": start["client_proof"], "device_id": "fixture-callback-device"})
	requireStatus(t, 200, status, statusReply)
	if str(statusReply, "status") != "pending" {
		t.Fatal("new QR attempt must begin in pending state")
	}
	callback := func(state, code string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("GET", "/v1/auth/zalo/callback?"+url.Values{"state": {state}, "code": {code}}.Encode(), nil)
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		return w
	}
	w := callback(str(start, "state"), "fixture-authorization-code")
	if w.Code != 200 || w.Header().Get("Cache-Control") != "no-store" || w.Header().Get("Content-Security-Policy") != "default-src 'none'" || strings.Contains(w.Body.String(), "fixture-authorization-code") || strings.Contains(w.Body.String(), str(start, "client_proof")) {
		t.Fatal("browser callback leaked a credential or omitted response guards")
	}
	var sessions int
	s.db.QueryRow("SELECT count(*) FROM sessions").Scan(&sessions)
	if exchanges.Load() != 0 || sessions != 0 {
		t.Fatal("browser callback alone issued a session or contacted upstream")
	}
	if callback("unknown-state", "fixture-authorization-code").Code != 409 || callback(str(start, "state"), "replacement-code").Code != 409 {
		t.Fatal("callback accepted an unknown state or a changed code")
	}
	status, statusReply = call(t, s, "POST", "/v1/auth/zalo/status", "", Object{"state": start["state"], "client_proof": start["client_proof"], "device_id": "fixture-callback-device"})
	requireStatus(t, 200, status, statusReply)
	if str(statusReply, "status") != "authorized" {
		t.Fatal("stored callback code did not advance the QR attempt")
	}
	status, reply := call(t, s, "POST", "/v1/auth/zalo/complete", "", Object{"state": start["state"], "client_proof": start["client_proof"], "device_id": "fixture-callback-device", "code": "replacement-code"})
	requireStatus(t, 409, status, reply)
	status, reply = call(t, s, "POST", "/v1/auth/zalo/complete", "", Object{"state": start["state"], "client_proof": start["client_proof"], "device_id": "fixture-callback-device"})
	requireStatus(t, 200, status, reply)
}

func TestZaloOAuthRegionRestrictionDoesNotIssueSessionOrIdentity(t *testing.T) {
	s := testServer(t)
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			send(w, 200, Object{"access_token": "fixture-upstream"})
			return
		}
		send(w, 200, Object{"error": -501, "message": "Personal information is limited due to IP address not inside Vietnam"})
	})
	start := zaloStartFixture(t, s, "", "fixture-region-device")
	status, result := zaloCompleteFixture(t, s, start, "fixture-region-device")
	requireStatus(t, 503, status, result)
	if !strings.Contains(jsonText(result), "ZALO_REGION_RESTRICTED") || str(result, "access_token") != "" {
		t.Fatal("region restriction did not stay an explicit service failure")
	}
	var count int
	if err := s.db.QueryRow("SELECT count(*) FROM records WHERE kind='identities'").Scan(&count); err != nil || count != 0 {
		t.Fatal("rejected profile created an identity")
	}
	status, result = zaloCompleteFixture(t, s, start, "fixture-region-device")
	requireStatus(t, 409, status, result)
}

func TestZaloOAuthProviderRefusalExpiresAttemptWithoutEchoingReason(t *testing.T) {
	s := testServer(t)
	var exchanges atomic.Int32
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		exchanges.Add(1)
		send(w, 200, Object{"access_token": "fixture-unexpected"})
	})
	start := zaloStartFixture(t, s, "", "fixture-refused-device")
	query := url.Values{"state": {str(start, "state")}, "error_reason": {"fixture-private-provider-message"}}
	r := httptest.NewRequest("GET", "/v1/auth/zalo/callback?"+query.Encode(), nil)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != 200 || w.Header().Get("Cache-Control") != "no-store" || strings.Contains(w.Body.String(), "fixture-private-provider-message") || exchanges.Load() != 0 {
		t.Fatal("provider refusal or private error text reached the login response/upstream")
	}
	status, rejected := zaloCompleteFixture(t, s, start, "fixture-refused-device")
	requireStatus(t, 401, status, rejected)
	status, rejected = call(t, s, "POST", "/v1/auth/zalo/status", "", Object{"state": start["state"], "client_proof": start["client_proof"], "device_id": "fixture-refused-device"})
	requireStatus(t, 200, status, rejected)
	if str(rejected, "status") != "denied" {
		t.Fatal("provider refusal was not reported to the paired device")
	}
}

func TestZaloOAuthGuestUpgradePreservesDataAndRevokesOldDeviceSession(t *testing.T) {
	s := testServer(t)
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			send(w, 200, Object{"access_token": "fixture-upstream"})
		} else {
			send(w, 200, Object{"error": 0, "id": "guest-upgrade-subject", "name": "Verified guest"})
		}
	})
	account := guest(t, s)
	uid := str(account["user"].(map[string]any), "id")
	device := str(account["device"].(map[string]any), "id")
	oldToken := str(account, "access_token")
	if err := s.put("history", "guest-history", uid, Object{"text": "Fixture history"}); err != nil {
		t.Fatal(err)
	}
	status, reply := call(t, s, "POST", "/v1/auth/zalo/start", "", Object{"device_id": device, "device_name": "Fixture device"})
	requireStatus(t, 409, status, reply)
	other := guest(t, s)
	status, reply = call(t, s, "POST", "/v1/auth/zalo/start", str(other, "access_token"), Object{"device_id": device, "device_name": "Fixture device"})
	requireStatus(t, 403, status, reply)
	start := zaloStartFixture(t, s, oldToken, device)
	status, reply = zaloCompleteFixture(t, s, start, device)
	requireStatus(t, 200, status, reply)
	if str(reply["user"].(map[string]any), "id") != uid || str(reply["user"].(map[string]any), "role") != "user" || len(s.entitlements(uid)) != 0 {
		t.Fatal("guest upgrade lost its user or implicitly granted Premium")
	}
	var owner string
	if err := s.db.QueryRow("SELECT owner FROM records WHERE kind='history' AND id='guest-history'").Scan(&owner); err != nil || owner != uid {
		t.Fatal("guest history no longer belongs to the upgraded account")
	}
	if _, err := s.authenticate(oldToken); err == nil {
		t.Fatal("old guest session survived device identity upgrade")
	}
	if _, err := s.authenticate(str(reply, "access_token")); err != nil {
		t.Fatal("new verified session is not usable")
	}
}

func TestZaloOAuthExpiryConfigChangeAndOwnershipRaceAreTerminal(t *testing.T) {
	s := testServer(t)
	var exchanges atomic.Int32
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		exchanges.Add(1)
		if r.URL.Path == "/token" {
			send(w, 200, Object{"access_token": "fixture-upstream"})
		} else {
			send(w, 200, Object{"error": 0, "id": "race-subject"})
		}
	})
	expired := zaloStartFixture(t, s, "", "expired-fixture-device")
	s.zaloPending[str(expired, "state")].Created = time.Now().Add(-11 * time.Minute)
	status, reply := zaloCompleteFixture(t, s, expired, "expired-fixture-device")
	requireStatus(t, 409, status, reply)
	changed := zaloStartFixture(t, s, "", "changed-fixture-device")
	t.Setenv("ZALO_OAUTH_TEST_SECRET", "replacement-fixture-secret")
	status, reply = zaloCompleteFixture(t, s, changed, "changed-fixture-device")
	requireStatus(t, 409, status, reply)
	t.Setenv("ZALO_OAUTH_TEST_SECRET", "fixture-zalo-secret")
	if exchanges.Load() != 0 {
		t.Fatal("expired or reconfigured attempts reached upstream")
	}
	raced := zaloStartFixture(t, s, "", "raced-fixture-device")
	owner := guest(t, s)
	uid := str(owner["user"].(map[string]any), "id")
	if _, err := s.db.Exec("INSERT INTO devices(id,user_id,name,created_at) VALUES(?,?,?,?)", "raced-fixture-device", uid, "Raced device", now()); err != nil {
		t.Fatal(err)
	}
	status, reply = zaloCompleteFixture(t, s, raced, "raced-fixture-device")
	requireStatus(t, 409, status, reply)
	var actual string
	s.db.QueryRow("SELECT user_id FROM devices WHERE id='raced-fixture-device'").Scan(&actual)
	if actual != uid {
		t.Fatal("OAuth completion stole a device registered after start")
	}
	status, reply = zaloCompleteFixture(t, s, raced, "raced-fixture-device")
	requireStatus(t, 409, status, reply)
	if exchanges.Load() != 2 {
		t.Fatal("ownership race replay repeated token/profile exchange")
	}
}

func TestZaloOAuthRejectsMalformedProfileAndUpstreamRedirects(t *testing.T) {
	s := testServer(t)
	var mode string
	var redirected atomic.Int32
	unexpected := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		redirected.Add(1)
		send(w, 200, Object{"access_token": "redirected-token"})
	}))
	defer unexpected.Close()
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			if mode == "redirect" {
				http.Redirect(w, r, unexpected.URL, http.StatusTemporaryRedirect)
				return
			}
			send(w, 200, Object{"access_token": "fixture-upstream"})
			return
		}
		switch mode {
		case "missing-error":
			send(w, 200, Object{"id": "unverified-subject"})
		case "error":
			send(w, 200, Object{"error": -201, "id": "unverified-subject"})
		case "fractional-error":
			send(w, 200, Object{"error": 0.5, "id": "unverified-subject"})
		case "numeric-id":
			send(w, 200, Object{"error": 0, "id": 123})
		case "empty-id":
			send(w, 200, Object{"error": 0, "id": ""})
		case "trailing-json":
			w.Write([]byte(`{"error":0,"id":"unverified-subject"} {"other":true}`))
		case "oversize":
			w.Write([]byte(`{"error":0,"id":"unverified-subject"}` + strings.Repeat(" ", (1<<20)+1)))
		default:
			t.Error("unexpected test mode")
		}
	})
	for _, testMode := range []string{"missing-error", "error", "fractional-error", "numeric-id", "empty-id", "trailing-json", "oversize", "redirect"} {
		t.Run(testMode, func(t *testing.T) {
			mode = testMode
			device := "fixture-invalid-" + mode
			start := zaloStartFixture(t, s, "", device)
			status, reply := zaloCompleteFixture(t, s, start, device)
			if status != 401 && status != 502 {
				t.Fatalf("unverified upstream response issued or mishandled a session: status=%d reply=%v", status, reply)
			}
			status, reply = zaloCompleteFixture(t, s, start, device)
			requireStatus(t, 409, status, reply)
		})
	}
	var sessions, users int
	s.db.QueryRow("SELECT count(*) FROM sessions").Scan(&sessions)
	s.db.QueryRow("SELECT count(*) FROM users WHERE role<>'admin'").Scan(&users)
	if sessions != 0 || users != 0 || redirected.Load() != 0 {
		t.Fatal("unverified or redirected provider data created a TN identity")
	}
}

func TestZaloOAuthExistingIdentityUsesAppScopedAccountAndDeviceLimit(t *testing.T) {
	s := testServer(t)
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			send(w, 200, Object{"access_token": "fixture-upstream"})
		} else {
			send(w, 200, Object{"error": 0, "id": "same-zalo-subject", "name": "Fixture name"})
		}
	})
	firstDevice := "fixture-first-device"
	first := zaloStartFixture(t, s, "", firstDevice)
	status, reply := zaloCompleteFixture(t, s, first, firstDevice)
	requireStatus(t, 200, status, reply)
	uid := str(reply["user"].(map[string]any), "id")
	if _, err := s.db.Exec("UPDATE users SET device_limit=1 WHERE id=?", uid); err != nil {
		t.Fatal(err)
	}
	second := zaloStartFixture(t, s, "", "fixture-second-device")
	status, rejected := zaloCompleteFixture(t, s, second, "fixture-second-device")
	requireStatus(t, 409, status, rejected)
	if str(rejected["error"].(map[string]any), "code") != "DEVICE_LIMIT" {
		t.Fatal("same subject did not resolve to the existing account's device limit")
	}
	var count int
	s.db.QueryRow("SELECT count(*) FROM devices WHERE id='fixture-second-device'").Scan(&count)
	if count != 0 {
		t.Fatal("rejected sign-in left a registered device")
	}
	if _, err := s.db.Exec("UPDATE users SET device_limit=10 WHERE id=?", uid); err != nil {
		t.Fatal(err)
	}
	second = zaloStartFixture(t, s, "", "fixture-second-device")
	status, secondReply := zaloCompleteFixture(t, s, second, "fixture-second-device")
	requireStatus(t, 200, status, secondReply)
	if str(secondReply["user"].(map[string]any), "id") != uid {
		t.Fatal("same app-scoped Zalo identity created a duplicate account")
	}
	providers, err := s.providers()
	if err != nil {
		t.Fatal(err)
	}
	p := providers["zalo"]
	p.Options["app_id"] = "987654321"
	if err := s.saveProviders(Object{"zalo": Object{"mode": p.Mode, "secret_env": p.SecretEnv, "options": p.Options}}); err != nil {
		t.Fatal(err)
	}
	third := zaloStartFixture(t, s, "", "fixture-third-device")
	status, thirdReply := zaloCompleteFixture(t, s, third, "fixture-third-device")
	requireStatus(t, 200, status, thirdReply)
	if str(thirdReply["user"].(map[string]any), "id") == uid {
		t.Fatal("different registered app IDs shared an unscoped Zalo identity")
	}
}

func TestZaloOAuthStrictBearerAndRegisteredRedirectConfiguration(t *testing.T) {
	s := testServer(t)
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("configuration checks must not contact upstream")
	})
	account := guest(t, s)
	device := str(account["device"].(map[string]any), "id")
	r := httptest.NewRequest("POST", "/v1/auth/zalo/start", strings.NewReader(jsonText(Object{"device_id": device, "device_name": "Fixture device"})))
	r.Header.Set("Authorization", str(account, "access_token"))
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("raw token without Bearer scheme authenticated an OAuth link request")
	}
	for _, redirect := range []string{"http://tn.example/callback", "https://user:password@tn.example/callback", "https://tn.example/callback?secret=value", "https://tn.example/callback#fragment"} {
		if err := s.saveProviders(Object{"zalo": Object{"mode": "generic", "secret_env": "ZALO_OAUTH_TEST_SECRET", "options": Object{"app_id": "123456789", "redirect_uri": redirect}}}); err != nil {
			t.Fatal(err)
		}
		status, reply := call(t, s, "POST", "/v1/auth/zalo/start", "", Object{"device_id": "fixture-invalid-redirect", "device_name": "Fixture device"})
		requireStatus(t, 503, status, reply)
	}
}

func TestZaloOAuthConcurrentCompletionAndSourceLogoutCannotCreateDuplicateSessions(t *testing.T) {
	s := testServer(t)
	var exchanges atomic.Int32
	entered, release := make(chan struct{}, 1), make(chan struct{})
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			exchanges.Add(1)
			entered <- struct{}{}
			<-release
			send(w, 200, Object{"access_token": "fixture-upstream"})
		} else {
			send(w, 200, Object{"error": 0, "id": "concurrent-subject"})
		}
	})
	account := guest(t, s)
	device := str(account["device"].(map[string]any), "id")
	start := zaloStartFixture(t, s, str(account, "access_token"), device)
	results := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		r := httptest.NewRequest("POST", "/v1/auth/zalo/complete", strings.NewReader(jsonText(Object{"state": start["state"], "client_proof": start["client_proof"], "device_id": device, "code": "fixture-authorization-code"})))
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		results <- w
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		close(release)
		t.Fatal("token exchange did not begin")
	}
	status, replay := zaloCompleteFixture(t, s, start, device)
	requireStatus(t, 409, status, replay)
	status, logout := call(t, s, "POST", "/v1/auth/logout", "", Object{"refresh_token": account["refresh_token"]})
	requireStatus(t, 200, status, logout)
	close(release)
	var response *httptest.ResponseRecorder
	select {
	case response = <-results:
	case <-time.After(5 * time.Second):
		t.Fatal("completion did not finish")
	}
	var result Object
	if json.Unmarshal(response.Body.Bytes(), &result) != nil || response.Code != 409 || str(result["error"].(map[string]any), "code") != "OAUTH_SOURCE_SESSION_CHANGED" {
		t.Fatal("sign-out during exchange still issued a new account session")
	}
	var identities, users, activeSessions int
	s.db.QueryRow("SELECT count(*) FROM records WHERE kind='identities'").Scan(&identities)
	s.db.QueryRow("SELECT count(*) FROM users WHERE role='user'").Scan(&users)
	s.db.QueryRow("SELECT count(*) FROM sessions WHERE revoked=0").Scan(&activeSessions)
	if identities != 0 || users != 0 || activeSessions != 0 || exchanges.Load() != 1 {
		t.Fatal("raced sign-in left an identity, active token or duplicated token exchange")
	}
}

func TestZaloOAuthRevokedSourceIsRejectedBeforeProviderAndFreshDeviceDoesNotMergeIt(t *testing.T) {
	s := testServer(t)
	var exchanges atomic.Int32
	zaloFixture(t, s, func(w http.ResponseWriter, r *http.Request) {
		exchanges.Add(1)
		if r.URL.Path == "/token" {
			send(w, 200, Object{"access_token": "fixture-upstream"})
		} else {
			send(w, 200, Object{"error": 0, "id": "fresh-device-subject"})
		}
	})
	account := guest(t, s)
	oldUID := str(account["user"].(map[string]any), "id")
	device := str(account["device"].(map[string]any), "id")
	start := zaloStartFixture(t, s, str(account, "access_token"), device)
	status, reply := call(t, s, "POST", "/v1/auth/logout", "", Object{"refresh_token": account["refresh_token"]})
	requireStatus(t, 200, status, reply)
	status, reply = zaloCompleteFixture(t, s, start, device)
	requireStatus(t, 409, status, reply)
	if exchanges.Load() != 0 {
		t.Fatal("revoked source session contacted Zalo")
	}
	other := guest(t, s)
	start = zaloStartFixture(t, s, str(other, "access_token"), "fresh-authenticated-device")
	status, reply = zaloCompleteFixture(t, s, start, "fresh-authenticated-device")
	requireStatus(t, 200, status, reply)
	newUID := str(reply["user"].(map[string]any), "id")
	if newUID == str(other["user"].(map[string]any), "id") || newUID == oldUID {
		t.Fatal("unregistered device merged another device's unrelated guest data")
	}
}
