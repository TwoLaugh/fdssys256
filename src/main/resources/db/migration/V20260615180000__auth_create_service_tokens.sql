-- core-02b: service tokens for Pattern-B authentication (scheduled jobs / system callers that
-- have no originating user session). Per design/origin-tracking-pattern.md §Authentication
-- Pattern B + tickets/core/02b-origin-tracking-foundation.md §Database.
--
-- The plaintext token is shown ONCE at mint time (via a future ops/admin CLI, out of scope
-- here); only the hash lives in this table. Lookup happens by hash on every Bearer-token
-- request; the partial index covers the only "active" predicate the auth provider issues.
--
-- permitted_origins is a text[] whitelist of X-Origin values this token can claim. A token
-- tagged {SYSTEM_SCHEDULED} cannot make AI_FEEDBACK calls; mismatch is a 403 from the filter.
-- Enum values come from com.example.mealprep.core.origin.Origin (USER, AI_FEEDBACK, …).

CREATE TABLE auth_service_tokens (
    id                       uuid          PRIMARY KEY,
    token_hash               varchar(96)   NOT NULL UNIQUE,
    name                     varchar(128)  NOT NULL,
    permitted_origins        text[]        NOT NULL,
    enabled                  boolean       NOT NULL DEFAULT true,
    last_used_at             timestamptz,
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL,
    revoked_at               timestamptz,
    optimistic_version       bigint        NOT NULL DEFAULT 0
);

-- Hot read: ServiceTokenAuthenticationProvider.authenticate() on every Bearer-token request.
CREATE INDEX idx_auth_service_tokens_enabled
    ON auth_service_tokens (enabled, revoked_at)
    WHERE enabled = true AND revoked_at IS NULL;
