package main

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	ListenAddress        string
	ServerCertFile       string
	ServerKeyFile        string
	ClientCAFile         string
	AllowedClientSHA256  map[[sha256.Size]byte]struct{}
	MaxConcurrentTunnels int
	ConnectTimeout       time.Duration
	IdleTimeout          time.Duration
	MaxTunnelDuration    time.Duration
}

func LoadConfigFromEnv() (Config, error) {
	max, err := envInt("TN_ZALO_GATEWAY_MAX_TUNNELS", 32)
	if err != nil {
		return Config{}, err
	}
	connect, err := envDuration("TN_ZALO_GATEWAY_CONNECT_TIMEOUT", 5*time.Second)
	if err != nil {
		return Config{}, err
	}
	idle, err := envDuration("TN_ZALO_GATEWAY_IDLE_TIMEOUT", 30*time.Second)
	if err != nil {
		return Config{}, err
	}
	maxDuration, err := envDuration("TN_ZALO_GATEWAY_MAX_TUNNEL_DURATION", 2*time.Minute)
	if err != nil {
		return Config{}, err
	}
	fingerprints, err := parseFingerprints(os.Getenv("TN_ZALO_GATEWAY_ALLOWED_CLIENT_CERT_SHA256"))
	if err != nil {
		return Config{}, err
	}
	cfg := Config{
		ListenAddress:        envDefault("TN_ZALO_GATEWAY_LISTEN", "0.0.0.0:443"),
		ServerCertFile:       os.Getenv("TN_ZALO_GATEWAY_TLS_CERT_FILE"),
		ServerKeyFile:        os.Getenv("TN_ZALO_GATEWAY_TLS_KEY_FILE"),
		ClientCAFile:         os.Getenv("TN_ZALO_GATEWAY_CLIENT_CA_FILE"),
		AllowedClientSHA256:  fingerprints,
		MaxConcurrentTunnels: max,
		ConnectTimeout:       connect,
		IdleTimeout:          idle,
		MaxTunnelDuration:    maxDuration,
	}
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func (c Config) Validate() error {
	if strings.TrimSpace(c.ListenAddress) == "" {
		return errors.New("gateway listen address is required")
	}
	if c.ServerCertFile == "" || c.ServerKeyFile == "" || c.ClientCAFile == "" {
		return errors.New("server certificate, server key and client CA files are required")
	}
	if len(c.AllowedClientSHA256) == 0 {
		return errors.New("at least one authorized client certificate SHA-256 fingerprint is required")
	}
	if c.MaxConcurrentTunnels < 1 || c.MaxConcurrentTunnels > 1024 {
		return errors.New("max concurrent tunnels must be between 1 and 1024")
	}
	if c.ConnectTimeout <= 0 || c.ConnectTimeout > 30*time.Second {
		return errors.New("connect timeout must be between 0 and 30 seconds")
	}
	if c.IdleTimeout <= 0 || c.IdleTimeout > 10*time.Minute {
		return errors.New("idle timeout must be between 0 and 10 minutes")
	}
	if c.MaxTunnelDuration <= 0 || c.MaxTunnelDuration > 15*time.Minute {
		return errors.New("maximum tunnel duration must be between 0 and 15 minutes")
	}
	return nil
}

func (c Config) TLSConfig() (*tls.Config, error) {
	server, err := tls.LoadX509KeyPair(c.ServerCertFile, c.ServerKeyFile)
	if err != nil {
		return nil, fmt.Errorf("load gateway server key pair: %w", err)
	}
	caPEM, err := os.ReadFile(c.ClientCAFile)
	if err != nil {
		return nil, fmt.Errorf("read gateway client CA: %w", err)
	}
	clientCAs := x509.NewCertPool()
	if !clientCAs.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("client CA file contains no certificates")
	}
	return &tls.Config{
		MinVersion:   tls.VersionTLS12,
		Certificates: []tls.Certificate{server},
		ClientAuth:   tls.RequireAndVerifyClientCert,
		ClientCAs:    clientCAs,
		NextProtos:   []string{"http/1.1"},
	}, nil
}

func parseFingerprints(raw string) (map[[sha256.Size]byte]struct{}, error) {
	out := make(map[[sha256.Size]byte]struct{})
	for _, item := range strings.Split(raw, ",") {
		item = strings.TrimSpace(strings.ReplaceAll(item, ":", ""))
		if item == "" {
			continue
		}
		if len(item) != sha256.Size*2 {
			return nil, errors.New("client certificate fingerprint must be a 32-byte SHA-256 hex value")
		}
		decoded, err := hex.DecodeString(item)
		if err != nil {
			return nil, errors.New("client certificate fingerprint must be hexadecimal")
		}
		var fingerprint [sha256.Size]byte
		copy(fingerprint[:], decoded)
		out[fingerprint] = struct{}{}
	}
	if len(out) == 0 {
		return nil, errors.New("TN_ZALO_GATEWAY_CLIENT_CERT_SHA256 is required")
	}
	return out, nil
}

func envDefault(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envInt(name string, fallback int) (int, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer", name)
	}
	return value, nil
}

func envDuration(name string, fallback time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be a duration such as 5s", name)
	}
	return value, nil
}
