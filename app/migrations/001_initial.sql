CREATE TABLE IF NOT EXISTS resources (
    kind TEXT NOT NULL, id TEXT NOT NULL, owner TEXT NOT NULL,
    parent TEXT, data TEXT NOT NULL CHECK(json_valid(data)),
    deleted_at REAL, PRIMARY KEY(kind,id)
);
CREATE INDEX IF NOT EXISTS resource_owner ON resources(kind,owner,deleted_at);
CREATE INDEX IF NOT EXISTS resource_parent ON resources(kind,parent,deleted_at);
CREATE TABLE IF NOT EXISTS credentials (
    user_id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, expires_at REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS refresh_tokens (
    digest TEXT PRIMARY KEY, session_id TEXT NOT NULL, expires_at REAL NOT NULL, consumed INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS idempotency (
    actor TEXT NOT NULL, operation TEXT NOT NULL, key TEXT NOT NULL, digest TEXT NOT NULL,
    response TEXT NOT NULL, expires_at REAL NOT NULL, PRIMARY KEY(actor,operation,key)
);
CREATE TABLE IF NOT EXISTS outbox (
    event_id TEXT PRIMARY KEY, job_id TEXT NOT NULL, available_at REAL NOT NULL, delivered_at REAL
);
CREATE INDEX IF NOT EXISTS outbox_available ON outbox(delivered_at,available_at);
CREATE TABLE IF NOT EXISTS queue (
    job_id TEXT PRIMARY KEY, available_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS rate_limits (
    bucket TEXT NOT NULL, window INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(bucket,window)
);
CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);
INSERT OR IGNORE INTO schema_migrations VALUES(1,strftime('%Y-%m-%dT%H:%M:%fZ','now'));

