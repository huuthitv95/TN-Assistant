package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func configEnv() Config {
	mode := os.Getenv("TN_RUNTIME_MODE")
	if mode == "" {
		mode = "independent"
	}
	return Config{DBPath: os.Getenv("TN_DB"), Addr: os.Getenv("TN_ADDR"), AdminUser: os.Getenv("TN_ADMIN_USER"), AdminPassword: os.Getenv("TN_ADMIN_PASSWORD"), ProviderFile: os.Getenv("TN_PROVIDER_FILE"), WebhookSecret: os.Getenv("TN_WEBHOOK_SECRET"), PublicURL: os.Getenv("TN_PUBLIC_URL"), StaticDir: os.Getenv("TN_STATIC_DIR"), DownloadDir: os.Getenv("TN_DOWNLOAD_DIR"), RuntimeMode: mode}
}
func main() {
	command := "serve"
	args := os.Args[1:]
	if len(args) > 0 {
		command = args[0]
		args = args[1:]
	}
	if command == "reset-admin" {
		if e := runResetAdmin(args, os.Stdin, os.Stdout); e != nil {
			log.Fatal(e)
		}
		return
	}
	fs := flag.NewFlagSet(command, flag.ExitOnError)
	c := configEnv()
	db := fs.String("db", c.DBPath, "SQLite path")
	out := fs.String("out", "", "New backup path")
	in := fs.String("in", "", "Backup source path")
	addr := fs.String("addr", c.Addr, "Listen address")
	fs.Parse(args)
	c.DBPath = *db
	c.Addr = *addr
	if c.DBPath == "" {
		c.DBPath = "data/tn-assistant.db"
	}
	if command == "restore" {
		if *in == "" {
			log.Fatal("restore requires --in backup --db NEW_DB")
		}
		if e := restoreDB(*in, c.DBPath); e != nil {
			log.Fatal(e)
		}
		fmt.Println("Restore integrity verified")
		return
	}
	s, e := openServer(c)
	if e != nil {
		log.Fatal(e)
	}
	defer s.db.Close()
	if command == "backup" {
		if *out == "" {
			log.Fatal("backup requires --out NEW_FILE")
		}
		if e = s.backup(*out); e != nil {
			log.Fatal(e)
		}
		fmt.Println("Consistent SQLite backup created")
		return
	}
	if command != "serve" {
		log.Fatal("commands: serve, backup, restore, reset-admin")
	}
	if c.AdminPassword == "" {
		var n int
		s.db.QueryRow("SELECT count(*) FROM users WHERE role='admin'").Scan(&n)
		if n == 0 {
			log.Print("No administrator exists: set TN_ADMIN_PASSWORD (at least 10 characters) and restart to initialize admin")
		}
	}
	if e = s.resumeManagedNewsJobs(); e != nil {
		log.Fatal("Unable to resume persisted TN news jobs")
	}
	srv := &http.Server{Addr: s.cfg.Addr, Handler: s.Handler(), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 32 << 10}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		srv.Shutdown(shutdown)
	}()
	log.Printf("TN Assistant backend %s; payment sandbox; provider availability at /v1/health", s.cfg.Addr)
	if e = srv.ListenAndServe(); e != nil && e != http.ErrServerClosed {
		log.Fatal(e)
	}
}
