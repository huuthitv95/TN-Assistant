package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// Only the Zalo OAuth exchange and profile lookup use this client. The
// gateway's private CA authenticates the outer TLS connection; the inner
// connection to Zalo uses the system trust store and the original hostname.
func zaloHTTPClient() (*http.Client, string, error) {
	mode := strings.TrimSpace(os.Getenv("TN_ZALO_EGRESS_MODE"))
	client := &http.Client{
		Timeout:       20 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	switch mode {
	case "", "direct":
		// Preserve the existing direct path while keeping this client's idle
		// connections separate from unrelated provider requests.
		client.Transport = http.DefaultTransport.(*http.Transport).Clone()
		return client, "direct", nil
	case "gateway":
		transport, err := newZaloGatewayTransport()
		if err != nil {
			return nil, mode, problem(503, "ZALO_EGRESS_CONFIG_INVALID", "Zalo gateway configuration is unavailable")
		}
		client.Transport = transport
		return client, mode, nil
	default:
		return nil, mode, problem(503, "ZALO_EGRESS_CONFIG_INVALID", "Zalo egress mode is invalid")
	}
}

func newZaloGatewayTransport() (*http.Transport, error) {
	address := strings.TrimSpace(os.Getenv("TN_ZALO_GATEWAY_ADDR"))
	serverName := strings.TrimSpace(os.Getenv("TN_ZALO_GATEWAY_SERVER_NAME"))
	caFile := strings.TrimSpace(os.Getenv("TN_ZALO_GATEWAY_CA_FILE"))
	certFile := strings.TrimSpace(os.Getenv("TN_ZALO_GATEWAY_CLIENT_CERT_FILE"))
	keyFile := strings.TrimSpace(os.Getenv("TN_ZALO_GATEWAY_CLIENT_KEY_FILE"))
	host, port, err := net.SplitHostPort(address)
	if err != nil || host == "" || serverName == "" || caFile == "" || certFile == "" || keyFile == "" {
		return nil, errors.New("incomplete Zalo gateway configuration")
	}
	portNumber, err := strconv.Atoi(port)
	if err != nil || portNumber < 1 || portNumber > 65535 || strings.ContainsAny(serverName, "/: \t\r\n") {
		return nil, errors.New("invalid Zalo gateway address or server name")
	}
	caPEM, err := os.ReadFile(caFile)
	if err != nil {
		return nil, err
	}
	rootCAs := x509.NewCertPool()
	if !rootCAs.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("invalid Zalo gateway CA")
	}
	clientCertificate, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, err
	}
	outerTLS := &tls.Config{
		MinVersion:   tls.VersionTLS12,
		RootCAs:      rootCAs,
		Certificates: []tls.Certificate{clientCertificate},
		ServerName:   serverName,
		NextProtos:   []string{"http/1.1"},
	}
	dialer := &tls.Dialer{
		NetDialer: &net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second},
		Config:    outerTLS,
	}
	return &http.Transport{
		Proxy:                 nil,
		DialContext:           zaloGatewayDialContext(address, dialer),
		TLSClientConfig:       &tls.Config{MinVersion: tls.VersionTLS12},
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: 10 * time.Second,
		IdleConnTimeout:       30 * time.Second,
		MaxIdleConnsPerHost:   2,
		MaxConnsPerHost:       8,
	}, nil
}

func zaloGatewayDialContext(gatewayAddress string, dialer *tls.Dialer) func(context.Context, string, string) (net.Conn, error) {
	return func(ctx context.Context, network, target string) (net.Conn, error) {
		if network != "tcp" || !zaloGatewayTargetAllowed(target) {
			return nil, errors.New("Zalo gateway destination is not allowed")
		}
		conn, err := dialer.DialContext(ctx, "tcp", gatewayAddress)
		if err != nil {
			return nil, err
		}
		keep := false
		defer func() {
			if !keep {
				_ = conn.Close()
			}
		}()
		if err := conn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
			return nil, err
		}
		stopCancel := context.AfterFunc(ctx, func() { _ = conn.Close() })
		defer stopCancel()
		if _, err := io.WriteString(conn, fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", target, target)); err != nil {
			return nil, err
		}
		reader := bufio.NewReader(conn)
		request := &http.Request{Method: http.MethodConnect, URL: &url.URL{Opaque: target}, Host: target}
		response, err := http.ReadResponse(reader, request)
		if err != nil {
			return nil, err
		}
		if response.StatusCode != http.StatusOK || response.ContentLength > 0 || len(response.TransferEncoding) != 0 {
			return nil, fmt.Errorf("Zalo gateway rejected CONNECT: status %d", response.StatusCode)
		}
		if err := conn.SetDeadline(time.Time{}); err != nil {
			return nil, err
		}
		keep = true
		return &zaloBufferedConn{Conn: conn, reader: reader}, nil
	}
}

func zaloGatewayTargetAllowed(target string) bool {
	host, port, err := net.SplitHostPort(target)
	if err != nil || port != "443" {
		return false
	}
	switch strings.ToLower(host) {
	case "oauth.zaloapp.com", "graph.zalo.me":
		return true
	default:
		return false
	}
}

func zaloGatewayURLAllowed(rawURL, expectedHost string) bool {
	endpoint, err := url.Parse(rawURL)
	if err != nil || endpoint.Scheme != "https" || endpoint.User != nil || endpoint.Opaque != "" || endpoint.Fragment != "" {
		return false
	}
	if !strings.EqualFold(endpoint.Hostname(), expectedHost) || (endpoint.Port() != "" && endpoint.Port() != "443") {
		return false
	}
	return true
}

type zaloBufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *zaloBufferedConn) Read(p []byte) (int, error) { return c.reader.Read(p) }
