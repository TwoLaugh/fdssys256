-- Auth module — Pilot 2.
-- See lld/auth.md §Database.
--
-- Recording attempts for unknown usernames keeps the throttle service from
-- becoming a username-enumeration oracle: same rules apply whether or not
-- the username matches a real user.

CREATE TABLE auth_login_attempts (
    id                  uuid        PRIMARY KEY,
    username_normalised varchar(64) NOT NULL,
    user_id             uuid,                              -- null when username didn't match
    source_ip           varchar(45) NOT NULL, -- string-form IPv4/IPv6
    succeeded           boolean     NOT NULL,
    failure_reason      varchar(32),                       -- BAD_PASSWORD | UNKNOWN_USER | ACCOUNT_LOCKED | THROTTLED | INVALID_REQUEST
    attempted_at        timestamptz NOT NULL
);

-- Per-username throttle window query.
CREATE INDEX idx_auth_login_attempts_username_time
    ON auth_login_attempts (username_normalised, attempted_at DESC);

-- Per-IP throttle window query.
CREATE INDEX idx_auth_login_attempts_ip_time
    ON auth_login_attempts (source_ip, attempted_at DESC);
