CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE idempotency_records (
    actor VARCHAR(200) NOT NULL,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(512) NOT NULL,
    request_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    payload BYTEA,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(actor, method, path, request_key)
);
CREATE INDEX idempotency_expiry ON idempotency_records(expires_at);
CREATE TABLE cursor_snapshots (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(user_id),
    binding CHAR(64) NOT NULL,
    resource_ids JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX cursor_expiry ON cursor_snapshots(expires_at);
CREATE TABLE rate_limit_buckets (
    bucket VARCHAR(256) NOT NULL,
    window_start BIGINT NOT NULL,
    request_count INTEGER NOT NULL CHECK(request_count >= 0),
    PRIMARY KEY(bucket, window_start)
);
