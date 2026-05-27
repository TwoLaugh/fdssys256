-- Grocery module — 01a Tier 3 per-user, per-provider session state.
-- See lld/grocery.md §V20260601120200 (lines 218-244). Cookies + navigation cursor live here so a
-- long-running session survives backend restarts without forcing a re-login. NO card / payment
-- data ever enters this table — we hold session cookies, not credentials.
--
-- DIVERGENCE (ticket 01a, locked): the LLD wants `session_state` encrypted-at-rest via an
-- EncryptedJsonConverter; that converter / core.crypto does not exist yet. 01a ships plaintext
-- JSONB (v1 FakeGroceryProvider holds no real cookies). See ProviderSessionState entity field's
-- TODO(grocery-crypto-followup). Encryption is a hard requirement before real Tesco automation.

CREATE TABLE grocery_provider_state (
    id                          uuid PRIMARY KEY,
    user_id                     uuid NOT NULL,
    provider_key                varchar(32) NOT NULL,
    enabled                     boolean NOT NULL DEFAULT true,
    session_state               jsonb,                            -- cookies + provider-side navigation cursor; encrypted at rest at app level (deferred)
    session_expires_at          timestamptz,
    last_login_at               timestamptz,
    last_failure_at             timestamptz,
    last_failure_reason         varchar(255),
    consecutive_failures        integer NOT NULL DEFAULT 0,
    scheduled_refresh_enabled   boolean NOT NULL DEFAULT false,
    refresh_top_n_ingredients   integer NOT NULL DEFAULT 50,
    version                     bigint NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    UNIQUE (user_id, provider_key)
);
-- single read path: "load my state for this provider"
CREATE INDEX idx_grocery_provider_state_user_provider
    ON grocery_provider_state (user_id, provider_key);
