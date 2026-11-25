ALTER TABLE users ADD COLUMN email varchar(254),
 ADD COLUMN roles text[] NOT NULL DEFAULT ARRAY['TENANT']::text[],
 ADD COLUMN auth_providers text[] NOT NULL DEFAULT ARRAY['EMAIL']::text[],
 ADD COLUMN phone varchar(32), ADD COLUMN is_phone_public boolean NOT NULL DEFAULT false,
 ADD COLUMN resource_version bigint NOT NULL DEFAULT 1 CHECK(resource_version > 0);
CREATE TABLE credentials (
 user_id uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
 email varchar(254) NOT NULL UNIQUE, password_hash text NOT NULL
);
CREATE TABLE refresh_tokens (
 digest char(64) PRIMARY KEY, session_id uuid NOT NULL REFERENCES sessions ON DELETE CASCADE,
 expires_at timestamptz NOT NULL, consumed boolean NOT NULL DEFAULT false
);
CREATE INDEX refresh_tokens_session ON refresh_tokens(session_id);
CREATE TABLE oauth_identities (
 provider varchar(10) NOT NULL CHECK(provider IN ('KAKAO','GOOGLE')), subject varchar(255) NOT NULL,
 user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE, PRIMARY KEY(provider,subject)
);
