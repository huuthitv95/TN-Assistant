 CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,username TEXT UNIQUE,name TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('admin','user','guest')),password_hash TEXT NOT NULL DEFAULT '',device_limit INTEGER NOT NULL DEFAULT 10,created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),name TEXT NOT NULL,created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),device_id TEXT REFERENCES devices(id),access_hash TEXT UNIQUE NOT NULL,refresh_hash TEXT UNIQUE NOT NULL,family TEXT NOT NULL,access_expires INTEGER NOT NULL,refresh_expires INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0);
 CREATE TABLE IF NOT EXISTS settings(user_id TEXT PRIMARY KEY REFERENCES users(id),data TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS records(kind TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL DEFAULT '',data TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(kind,id));
 CREATE TABLE IF NOT EXISTS activation_codes(code TEXT PRIMARY KEY,product_id TEXT NOT NULL,remaining INTEGER NOT NULL CHECK(remaining>=0),expires_at TEXT,created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS redemptions(code TEXT NOT NULL,user_id TEXT NOT NULL REFERENCES users(id),entitlement_id TEXT NOT NULL,PRIMARY KEY(code,user_id));
 CREATE TABLE IF NOT EXISTS payments(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),product_id TEXT NOT NULL,status TEXT NOT NULL,amount INTEGER NOT NULL,currency TEXT NOT NULL,duration_days INTEGER NOT NULL,created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS payment_events(event_id TEXT PRIMARY KEY,order_id TEXT NOT NULL REFERENCES payments(id),status TEXT NOT NULL,created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS entitlements(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),product_id TEXT NOT NULL,source TEXT UNIQUE NOT NULL,starts_at TEXT NOT NULL,expires_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS link_codes(code TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),device_id TEXT,status TEXT NOT NULL,expires_at TEXT NOT NULL,linked_user_id TEXT,source TEXT);
 CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id); CREATE INDEX IF NOT EXISTS records_owner ON records(kind,owner); CREATE INDEX IF NOT EXISTS entitlements_user ON entitlements(user_id);

