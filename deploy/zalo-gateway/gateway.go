package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync/atomic"
	"time"
)

const healthPath = "/_tn/health"

var allowedDestinations = map[string]struct{}{
	"oauth.zaloapp.com": {},
	"graph.zalo.me":     {},
}

var deniedIPPrefixes = mustPrefixes(
	"0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
	"172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
	"198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
	"::/128", "::1/128", "64:ff9b:1::/48", "100::/64", "2001:db8::/32", "2002::/16",
	"fc00::/7", "fe80::/10", "ff00::/8",
)

type ipResolver interface {
	LookupNetIP(context.Context, string, string) ([]netip.Addr, error)
}

type tunnelDialer interface {
	DialContext(context.Context, string, string) (net.Conn, error)
}

type Gateway struct {
	config    Config
	resolver  ipResolver
	dialer    tunnelDialer
	logger    *log.Logger
	semaphore chan struct{}
}

func NewGateway(config Config, logger *log.Logger) (*Gateway, error) {
	if err := config.Validate(); err != nil {
		return nil, err
	}
	if logger == nil {
		logger = log.Default()
	}
	return &Gateway{
		config:    config,
		resolver:  net.DefaultResolver,
		dialer:    &net.Dialer{Timeout: config.ConnectTimeout, KeepAlive: 30 * time.Second},
		logger:    logger,
		semaphore: make(chan struct{}, config.MaxConcurrentTunnels),
	}, nil
}

func (g *Gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !g.authorizedClient(r.TLS) {
		http.Error(w, "client certificate is not authorized", http.StatusForbidden)
		return
	}
	if r.Method == http.MethodGet && r.URL.Path == healthPath {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"status":"ok"}`)
		return
	}
	if r.Method != http.MethodConnect {
		http.Error(w, "CONNECT is required", http.StatusMethodNotAllowed)
		return
	}
	if r.ContentLength > 0 || len(r.TransferEncoding) != 0 || r.Header.Get("Proxy-Authorization") != "" {
		http.Error(w, "request body and proxy credentials are unsupported", http.StatusBadRequest)
		return
	}
	host, err := parseDestination(r.Host)
	if err != nil {
		http.Error(w, "destination is not allowed", http.StatusForbidden)
		return
	}
	select {
	case g.semaphore <- struct{}{}:
		defer func() { <-g.semaphore }()
	default:
		http.Error(w, "gateway capacity is full", http.StatusServiceUnavailable)
		return
	}

	tunnelCtx, cancel := context.WithTimeout(r.Context(), g.config.MaxTunnelDuration)
	defer cancel()
	connectCtx, connectCancel := context.WithTimeout(tunnelCtx, g.config.ConnectTimeout)
	addresses, err := g.resolver.LookupNetIP(connectCtx, "ip", host)
	if err != nil || len(addresses) == 0 {
		connectCancel()
		http.Error(w, "destination resolution failed", http.StatusBadGateway)
		return
	}
	for _, address := range addresses {
		if !isPublicDestinationIP(address) {
			connectCancel()
			http.Error(w, "destination resolution is not allowed", http.StatusForbidden)
			return
		}
	}
	var upstream net.Conn
	for _, address := range addresses {
		upstreamAddress := net.JoinHostPort(address.Unmap().String(), "443")
		candidate, dialErr := g.dialer.DialContext(connectCtx, "tcp", upstreamAddress)
		if dialErr == nil {
			upstream = candidate
			break
		}
		if candidate != nil {
			_ = candidate.Close()
		}
		if connectCtx.Err() != nil {
			break
		}
	}
	connectCancel()
	if upstream == nil {
		http.Error(w, "destination connection failed", http.StatusBadGateway)
		return
	}
	defer upstream.Close()

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "CONNECT is unavailable", http.StatusInternalServerError)
		return
	}
	client, rw, err := hijacker.Hijack()
	if err != nil {
		return
	}
	started := time.Now()
	defer client.Close()
	if _, err = rw.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}
	if err = rw.Flush(); err != nil {
		return
	}
	inbound, outbound, tunnelErr := tunnel(tunnelCtx, client, rw.Reader, upstream, g.config.IdleTimeout)
	result := "closed"
	if tunnelErr != nil && !errors.Is(tunnelErr, context.Canceled) && !errors.Is(tunnelErr, context.DeadlineExceeded) {
		result = "io_error"
	}
	g.logger.Printf("zalo_tunnel host=%s result=%s duration_ms=%d client_to_upstream=%d upstream_to_client=%d",
		host, result, time.Since(started).Milliseconds(), inbound, outbound)
}

func (g *Gateway) authorizedClient(state *tls.ConnectionState) bool {
	if state == nil || len(state.PeerCertificates) == 0 || len(state.VerifiedChains) == 0 {
		return false
	}
	fingerprint := sha256.Sum256(state.PeerCertificates[0].Raw)
	_, ok := g.config.AllowedClientSHA256[fingerprint]
	return ok
}

func parseDestination(authority string) (string, error) {
	host, port, err := net.SplitHostPort(authority)
	if err != nil || port != "443" || host == "" {
		return "", errors.New("CONNECT must use an explicit :443 authority")
	}
	if strings.TrimSpace(host) != host || strings.HasSuffix(host, ".") || net.ParseIP(host) != nil {
		return "", errors.New("hostname form is required")
	}
	host = strings.ToLower(host)
	if _, ok := allowedDestinations[host]; !ok {
		return "", errors.New("destination hostname is not allowlisted")
	}
	return host, nil
}

func isPublicDestinationIP(ip netip.Addr) bool {
	if !ip.IsValid() {
		return false
	}
	ip = ip.Unmap()
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsMulticast() || ip.IsUnspecified() {
		return false
	}
	for _, prefix := range deniedIPPrefixes {
		if prefix.Contains(ip) {
			return false
		}
	}
	return true
}

func tunnel(ctx context.Context, client net.Conn, clientReader io.Reader, upstream net.Conn, idle time.Duration) (int64, int64, error) {
	clientConn := newIdleConn(client, idle)
	upstreamConn := newIdleConn(upstream, idle)
	var clientToUpstream, upstreamToClient atomic.Int64
	errs := make(chan error, 2)
	copyHalf := func(dst net.Conn, src io.Reader, count *atomic.Int64, halfClose net.Conn) {
		_, err := io.Copy(dst, io.TeeReader(src, countWriter{count}))
		if closer, ok := halfClose.(interface{ CloseWrite() error }); ok {
			_ = closer.CloseWrite()
		}
		errs <- err
	}
	go copyHalf(upstreamConn, idleBufferedReader{reader: clientReader, conn: client, idle: idle}, &clientToUpstream, upstream)
	go copyHalf(clientConn, upstreamConn, &upstreamToClient, client)
	var firstErr error
	select {
	case firstErr = <-errs:
	case <-ctx.Done():
		firstErr = ctx.Err()
	}
	if firstErr != nil {
		_ = client.SetDeadline(time.Now())
		_ = upstream.SetDeadline(time.Now())
	}
	select {
	case secondErr := <-errs:
		if firstErr == nil {
			firstErr = secondErr
		}
	case <-ctx.Done():
		if firstErr == nil {
			firstErr = ctx.Err()
		}
	}
	_ = client.Close()
	_ = upstream.Close()
	return clientToUpstream.Load(), upstreamToClient.Load(), firstErr
}

type countWriter struct{ count *atomic.Int64 }

func (w countWriter) Write(p []byte) (int, error) { n := len(p); w.count.Add(int64(n)); return n, nil }

type idleConn struct {
	net.Conn
	idle time.Duration
}

func newIdleConn(conn net.Conn, idle time.Duration) idleConn { return idleConn{Conn: conn, idle: idle} }
func (c idleConn) Read(p []byte) (int, error) {
	_ = c.Conn.SetReadDeadline(time.Now().Add(c.idle))
	return c.Conn.Read(p)
}
func (c idleConn) Write(p []byte) (int, error) {
	_ = c.Conn.SetWriteDeadline(time.Now().Add(c.idle))
	return c.Conn.Write(p)
}

type idleBufferedReader struct {
	reader io.Reader
	conn   net.Conn
	idle   time.Duration
}

func (r idleBufferedReader) Read(p []byte) (int, error) {
	_ = r.conn.SetReadDeadline(time.Now().Add(r.idle))
	return r.reader.Read(p)
}

func mustPrefixes(values ...string) []netip.Prefix {
	prefixes := make([]netip.Prefix, 0, len(values))
	for _, value := range values {
		prefix, err := netip.ParsePrefix(value)
		if err != nil {
			panic(fmt.Sprintf("invalid built-in network prefix %q", value))
		}
		prefixes = append(prefixes, prefix)
	}
	return prefixes
}
