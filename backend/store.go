package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

//go:embed migrations/001_initial.sql
var initialMigration string

type Object = map[string]any
type Config struct {
	DBPath, Addr, AdminUser, AdminPassword, ProviderFile, WebhookSecret, PublicURL, StaticDir, DownloadDir string
	RuntimeMode                                                                                            string
	AccessTTL                                                                                              time.Duration
}
type Server struct {
	db                     *sql.DB
	cfg                    Config
	aiMu                   sync.Mutex
	locationMu             sync.Mutex
	privateLocations       map[string]privateLocationRequest
	privateLocationReplies map[string]privateLocationReply
	callAPIWorkers         chan struct{}
	zaloMu                 sync.Mutex
	zaloPending            map[string]*zaloAttempt
	weatherMu              sync.Mutex
	weatherCache           map[string]weatherCacheEntry
	weatherBase            string
}

func ident() string {
	b := make([]byte, 24)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func hash(s string) string          { b := sha256.Sum256([]byte(s)); return hex.EncodeToString(b[:]) }
func now() string                   { return time.Now().UTC().Format(time.RFC3339Nano) }
func str(m Object, k string) string { s, _ := m[k].(string); return s }
func integer(m Object, k string, def int) int {
	if v, ok := m[k].(float64); ok {
		return int(v)
	}
	if v, ok := m[k].(int); ok {
		return v
	}
	return def
}
func jsonText(v any) string { b, _ := json.Marshal(v); return string(b) }
func openServer(c Config) (*Server, error) {
	if c.RuntimeMode != "" && c.RuntimeMode != "independent" {
		return nil, errors.New("TN_RUNTIME_MODE must be independent")
	}
	if c.DBPath == "" {
		c.DBPath = "data/tn-assistant.db"
	}
	if c.AccessTTL == 0 {
		c.AccessTTL = 15 * time.Minute
	}
	if c.AdminUser == "" {
		c.AdminUser = "admin"
	}
	if c.Addr == "" {
		c.Addr = "127.0.0.1:8080"
	}
	if c.PublicURL == "" {
		c.PublicURL = "http://" + c.Addr
	}
	if e := os.MkdirAll(filepath.Dir(c.DBPath), 0700); e != nil {
		return nil, e
	}
	db, e := sql.Open("sqlite", c.DBPath)
	if e != nil {
		return nil, e
	}
	db.SetMaxOpenConns(1)
	s := &Server{db: db, cfg: c, weatherCache: map[string]weatherCacheEntry{}, weatherBase: "https://api.met.no/weatherapi/locationforecast/2.0/compact"}
	if e = s.migrate(); e != nil {
		db.Close()
		return nil, e
	}
	if e = s.initializeIndependentRuntime(); e != nil {
		db.Close()
		return nil, e
	}
	return s, nil
}
func (s *Server) migrate() error {
	_, e := s.db.Exec("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=10000; CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL)")
	if e != nil {
		return e
	}
	var version int
	if e = s.db.QueryRow("SELECT coalesce(max(version),0) FROM schema_migrations").Scan(&version); e != nil {
		return e
	}
	if version > 1 {
		return errors.New("database schema is newer than this server")
	}
	if version < 1 {
		tx, e := s.db.Begin()
		if e != nil {
			return e
		}
		defer tx.Rollback()
		if _, e = tx.Exec(initialMigration); e != nil {
			return e
		}
		if _, e = tx.Exec("INSERT INTO schema_migrations VALUES(1,?)", now()); e != nil {
			return e
		}
		if e = tx.Commit(); e != nil {
			return e
		}
	}

	if s.cfg.AdminPassword != "" {
		var n int
		s.db.QueryRow("SELECT count(*) FROM users WHERE username=?", s.cfg.AdminUser).Scan(&n)
		if n == 0 {
			if !validPasswordLength(s.cfg.AdminPassword) {
				return errors.New("TN_ADMIN_PASSWORD must contain at least 10 characters")
			}
			p, e := bcrypt.GenerateFromPassword([]byte(s.cfg.AdminPassword), 12)
			if e != nil {
				return e
			}
			_, e = s.db.Exec("INSERT INTO users(id,username,name,role,password_hash,created_at) VALUES(?,?,?,?,?,?)", ident(), s.cfg.AdminUser, "Administrator", "admin", string(p), now())
			if e != nil {
				return e
			}
		}
	}
	s.db.Exec(`INSERT OR IGNORE INTO records VALUES('products','premium-month','','{"id":"premium-month","name":"Premium — sandbox","description":"Sản phẩm thử nghiệm, không thu tiền thật","price":49000,"currency":"VND","duration_days":30,"features":["premium"]}',?)`, now())
	return nil
}
func (s *Server) record(kind, id string) (Object, error) {
	var b string
	e := s.db.QueryRow("SELECT data FROM records WHERE kind=? AND id=?", kind, id).Scan(&b)
	if e != nil {
		return nil, e
	}
	var m Object
	e = json.Unmarshal([]byte(b), &m)
	return m, e
}
func (s *Server) put(kind, id, owner string, m Object) error {
	m["id"] = id
	_, e := s.db.Exec("INSERT INTO records(kind,id,owner,data,created_at) VALUES(?,?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET owner=excluded.owner,data=excluded.data", kind, id, owner, jsonText(m), now())
	return e
}
func (s *Server) list(kind, owner, q string) ([]Object, error) {
	query := "SELECT data FROM records WHERE kind=?"
	args := []any{kind}
	if owner != "" {
		query += " AND owner=?"
		args = append(args, owner)
	}
	query += " ORDER BY created_at DESC LIMIT 500"
	rows, e := s.db.Query(query, args...)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Object{}
	for rows.Next() {
		var b string
		rows.Scan(&b)
		if q != "" && !strings.Contains(strings.ToLower(b), strings.ToLower(q)) {
			continue
		}
		var m Object
		if json.Unmarshal([]byte(b), &m) == nil {
			out = append(out, m)
		}
	}
	return out, rows.Err()
}

type Principal struct {
	User      Object
	Device    Object
	SessionID string
}

func (s *Server) user(id string) (Object, error) {
	m := Object{}
	var username sql.NullString
	var name, role string
	var limit int
	e := s.db.QueryRow("SELECT username,name,role,device_limit FROM users WHERE id=?", id).Scan(&username, &name, &role, &limit)
	if e != nil {
		return nil, e
	}
	m["id"] = id
	m["username"] = username.String
	m["name"] = name
	m["role"] = role
	m["device_limit"] = limit
	return m, nil
}
func (s *Server) tokens(tx *sql.Tx, uid, did, family string) (Object, error) {
	a, r := ident(), ident()
	if family == "" {
		family = ident()
	}
	id := ident()
	var dev any
	if did != "" {
		dev = did
	}
	_, e := tx.Exec("INSERT INTO sessions(id,user_id,device_id,access_hash,refresh_hash,family,access_expires,refresh_expires) VALUES(?,?,?,?,?,?,?,?)", id, uid, dev, hash(a), hash(r), family, time.Now().Add(s.cfg.AccessTTL).Unix(), time.Now().Add(30*24*time.Hour).Unix())
	if e != nil {
		return nil, e
	}
	return Object{"access_token": a, "refresh_token": r, "expires_in": int(s.cfg.AccessTTL.Seconds()), "user": Object{"id": uid}, "device": Object{"id": did}}, nil
}
func (s *Server) hydrateTokens(m Object) Object {
	uid := str(m["user"].(Object), "id")
	u, _ := s.user(uid)
	m["user"] = u
	did := str(m["device"].(Object), "id")
	if did != "" {
		var name string
		s.db.QueryRow("SELECT name FROM devices WHERE id=?", did).Scan(&name)
		m["device"] = Object{"id": did, "name": name}
	}
	return m
}
func (s *Server) authenticate(token string) (*Principal, error) {
	var id, uid, did string
	var dev sql.NullString
	e := s.db.QueryRow("SELECT id,user_id,device_id FROM sessions WHERE access_hash=? AND revoked=0 AND access_expires>?", hash(token), time.Now().Unix()).Scan(&id, &uid, &dev)
	if e != nil {
		return nil, e
	}
	u, e := s.user(uid)
	if e != nil {
		return nil, e
	}
	did = dev.String
	d := Object{"id": did}
	if did != "" {
		var name string
		if e = s.db.QueryRow("SELECT name FROM devices WHERE id=? AND user_id=?", did, uid).Scan(&name); e != nil {
			return nil, e
		}
		d["name"] = name
	}
	return &Principal{u, d, id}, nil
}
func (s *Server) entitlements(uid string) []Object {
	current := now()
	rows, e := s.db.Query("SELECT id,product_id,source,starts_at,expires_at FROM entitlements WHERE user_id=? AND starts_at<=? AND expires_at>?", uid, current, current)
	if e != nil {
		return []Object{}
	}
	defer rows.Close()
	out := []Object{}
	for rows.Next() {
		var id, product, source, start, end string
		rows.Scan(&id, &product, &source, &start, &end)
		out = append(out, Object{"id": id, "product_id": product, "source": source, "starts_at": start, "expires_at": end})
	}
	return out
}
func (s *Server) backup(path string) error {
	if _, e := os.Stat(path); e == nil {
		return errors.New("backup destination exists")
	}
	abs, e := filepath.Abs(path)
	if e != nil {
		return e
	}
	if e = os.MkdirAll(filepath.Dir(abs), 0700); e != nil {
		return e
	}
	_, e = s.db.ExecContext(context.Background(), "VACUUM INTO ?", abs)
	return e
}
func restoreDB(source, dest string) error {
	if _, e := os.Stat(dest); !os.IsNotExist(e) {
		return fmt.Errorf("restore requires a new destination; existing DB is never overwritten")
	}
	db, e := sql.Open("sqlite", "file:"+filepath.ToSlash(source)+"?mode=ro")
	if e != nil {
		return e
	}
	defer db.Close()
	var check string
	if e = db.QueryRow("PRAGMA integrity_check").Scan(&check); e != nil || check != "ok" {
		return errors.New("backup integrity check failed")
	}
	if e = os.MkdirAll(filepath.Dir(dest), 0700); e != nil {
		return e
	}
	_, e = db.Exec("VACUUM INTO ?", dest)
	return e
}
