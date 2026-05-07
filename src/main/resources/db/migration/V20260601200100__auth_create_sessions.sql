-- Auth module — Pilot 2.
-- See lld/auth.md §Database.
--
-- Per the auth-01 ticket, last_seen_at is set at issue time and is NOT
-- updated per request — sessions are 30-day absolute TTL, no sliding
-- window. The column is kept so future tickets can reintroduce sliding
-- expiry without a migration round trip.

CREATE TABLE auth_sessions (
    id            uuid        PRIMARY KEY,
    user_id       uuid        NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
    token_hash    varchar(64) NOT NULL, -- SHA-256 hex of the raw token; raw token never stored
    issued_at     timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL,
    revoked_at    timestamptz,
    issuing_ip    varchar(45), -- string-form IPv4/IPv6
    user_agent    varchar(255),
    version       bigint      NOT NULL DEFAULT 0
);

-- Hot read: every authenticated request hits this index.
CREATE UNIQUE INDEX idx_auth_sessions_token_hash
    ON auth_sessions (token_hash);

-- "Revoke all my other sessions" + admin views.
CREATE INDEX idx_auth_sessions_user_active
    ON auth_sessions (user_id)
    WHERE revoked_at IS NULL;

-- Reaper job: delete expired/revoked rows.
CREATE INDEX idx_auth_sessions_expires_at
    ON auth_sessions (expires_at);
