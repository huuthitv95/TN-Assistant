package main

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestParseDestinationStrictAllowlist(t *testing.T) {
	for _, tc := range []struct {
		authority, want string
		ok              bool
	}{
		{"graph.zalo.me:443", "graph.zalo.me", true},
		{"OAUTH.ZALOAPP.COM:443", "oauth.zaloapp.com", true},
		{"graph.zalo.me:80", "", false},
		{"example.com:443", "", false},
		{"127.0.0.1:443", "", false},
		{"graph.zalo.me.:443", "", false},
		{"graph.zalo.me", "", false},
		{" graph.zalo.me:443", "", false},
	} {
		got, err := parseDestination(tc.authority)
		if (err == nil) != tc.ok || got != tc.want {
			t.Errorf("parseDestination(%q) = (%q, %v), want (%q, ok=%v)", tc.authority, got, err, tc.want, tc.ok)
		}
	}
}

func TestPublicDestinationIPRejectsNonRoutableRanges(t *testing.T) {
	for _, raw := range []string{
		"8.8.8.8", "1.1.1.1", "2001:4860:4860::8888",
	} {
		if ip := netip.MustParseAddr(raw); !isPublicDestinationIP(ip) {
			t.Errorf("%s should be accepted", raw)
		}
	}
	for _, raw := range []string{
		"0.1.2.3", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254",
		"172.20.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1", "203.0.113.8",
		"224.0.0.1", "240.0.0.1", "::", "::1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1",
	} {
		if ip := netip.MustParseAddr(raw); isPublicDestinationIP(ip) {
			t.Errorf("%s should be rejected", raw)
		}
	}
}

func TestParseFingerprintsSupportsRotationWithoutWeakeningExactMatch(t *testing.T) {
	a := strings.Repeat("a1", sha256.Size)
	b := strings.ToUpper(strings.Repeat("b2", sha256.Size/2) + ":" + strings.Repeat("b2", sha256.Size/2))
	got, err := parseFingerprints(a + "," + b)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d fingerprints, want 2", len(got))
	}
	for _, value := range []string{a, strings.ReplaceAll(b, ":", "")} {
		bytes, _ := hex.DecodeString(value)
		var fp [sha256.Size]byte
		copy(fp[:], bytes)
		if _, ok := got[fp]; !ok {
			t.Errorf("fingerprint %q not retained", value)
		}
	}
	if _, err := parseFingerprints("not-a-fingerprint"); err == nil {
		t.Fatal("malformed fingerprint accepted")
	}
}

func TestGatewayRequiresExactVerifiedClientCertificate(t *testing.T) {
	rawCert := []byte("client-cert-der-fixture")
	fingerprint := sha256.Sum256(rawCert)
	cfg := Config{
		ListenAddress: "127.0.0.1:0", ServerCertFile: "server.pem", ServerKeyFile: "server.key", ClientCAFile: "client-ca.pem",
		AllowedClientSHA256:  map[[sha256.Size]byte]struct{}{fingerprint: {}},
		MaxConcurrentTunnels: 1, ConnectTimeout: time.Second, IdleTimeout: time.Second, MaxTunnelDuration: time.Second,
	}
	gateway, err := NewGateway(cfg, nil)
	if err != nil {
		t.Fatal(err)
	}
	state := &tls.ConnectionState{PeerCertificates: []*x509.Certificate{{Raw: rawCert}}, VerifiedChains: [][]*x509.Certificate{{{}}}}
	if !gateway.authorizedClient(state) {
		t.Fatal("authorized and verified fingerprint rejected")
	}
	state.VerifiedChains = nil
	if gateway.authorizedClient(state) {
		t.Fatal("unverified certificate accepted")
	}
	state.VerifiedChains = [][]*x509.Certificate{{{}}}
	state.PeerCertificates[0].Raw = []byte("other-client")
	if gateway.authorizedClient(state) {
		t.Fatal("unlisted client certificate accepted")
	}
}

func TestHandlerRejectsNonCONNECTAndUnauthorizedHealth(t *testing.T) {
	certDER := []byte("allowed-cert")
	fp := sha256.Sum256(certDER)
	cfg := Config{
		ListenAddress: "127.0.0.1:0", ServerCertFile: "server.pem", ServerKeyFile: "server.key", ClientCAFile: "client-ca.pem",
		AllowedClientSHA256:  map[[sha256.Size]byte]struct{}{fp: {}},
		MaxConcurrentTunnels: 1, ConnectTimeout: time.Second, IdleTimeout: time.Second, MaxTunnelDuration: time.Second,
	}
	gateway, err := NewGateway(cfg, nil)
	if err != nil {
		t.Fatal(err)
	}
	unauthorized := httptest.NewRequest(http.MethodGet, "https://gateway/_tn/health", nil)
	unauthorizedResult := httptest.NewRecorder()
	gateway.ServeHTTP(unauthorizedResult, unauthorized)
	if unauthorizedResult.Code != http.StatusForbidden {
		t.Fatalf("unauthorized health status = %d", unauthorizedResult.Code)
	}
	authorized := httptest.NewRequest(http.MethodGet, "https://gateway/unexpected", nil)
	authorized.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{{Raw: certDER}}, VerifiedChains: [][]*x509.Certificate{{{}}}}
	authorizedResult := httptest.NewRecorder()
	gateway.ServeHTTP(authorizedResult, authorized)
	if authorizedResult.Code != http.StatusMethodNotAllowed {
		t.Fatalf("unexpected method status = %d", authorizedResult.Code)
	}
}
