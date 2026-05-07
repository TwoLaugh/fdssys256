-- Auth module — Pilot 2.
-- See lld/auth.md §Database.

CREATE TABLE auth_users (
    id                  uuid        PRIMARY KEY,
    username            varchar(64) NOT NULL,
    username_normalised varchar(64) NOT NULL, -- lowercase + trim, used for uniqueness and lookup
    password_hash       varchar(72) NOT NULL, -- BCrypt $2a/$2b output, 60 chars + headroom
    password_updated_at timestamptz NOT NULL,
    failed_login_count  integer     NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    last_login_at       timestamptz,
    last_login_ip       varchar(45), -- string-form IPv4/IPv6 (LLD specced inet; downgraded to varchar to avoid a Hibernate UserType)
    deleted_at          timestamptz, -- soft-delete; preserves userId references
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);

-- Hot read: every login lookup, every CurrentUserResolver call.
CREATE UNIQUE INDEX idx_auth_users_username_normalised
    ON auth_users (username_normalised);

-- Operational query: list locked accounts.
CREATE INDEX idx_auth_users_locked_until
    ON auth_users (locked_until)
    WHERE locked_until IS NOT NULL;
