package main

import (
	"context"
	"crypto/tls"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	logger := log.New(os.Stdout, "", log.LstdFlags|log.LUTC)
	cfg, err := LoadConfigFromEnv()
	if err != nil {
		logger.Fatalf("invalid gateway configuration: %v", err)
	}
	tlsConfig, err := cfg.TLSConfig()
	if err != nil {
		logger.Fatalf("invalid gateway TLS configuration: %v", err)
	}
	gateway, err := NewGateway(cfg, logger)
	if err != nil {
		logger.Fatalf("initialize gateway: %v", err)
	}
	listener, err := net.Listen("tcp", cfg.ListenAddress)
	if err != nil {
		logger.Fatalf("listen: %v", err)
	}
	server := &http.Server{
		Handler:           gateway,
		TLSConfig:         tlsConfig,
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    4 << 10,
	}
	tlsListener := tls.NewListener(listener, tlsConfig)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	logger.Printf("zalo_gateway_started listen=%s max_tunnels=%d", cfg.ListenAddress, cfg.MaxConcurrentTunnels)
	if err := server.Serve(tlsListener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Fatalf("serve: %v", err)
	}
}
