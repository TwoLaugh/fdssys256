-- Preference module — 01d lifestyle config audit log (section-level diffs).
-- See lld/preference.md §V20260501120200.
--
-- Append-only. One row per top-level section that changed on a PUT — see
-- LifestyleConfigServiceImpl#update for the diff algorithm. ON DELETE CASCADE
-- mirrors preference_hard_constraints_audit; orphan retention is not required
-- for v1 since the parent row is one-per-user and has no delete path yet.

CREATE TABLE preference_lifestyle_config_audit (
    id                       uuid         PRIMARY KEY,
    lifestyle_config_id      uuid         NOT NULL REFERENCES preference_lifestyle_config(id) ON DELETE CASCADE,
    actor_user_id            uuid         NOT NULL,
    field_path               varchar(128) NOT NULL,
    previous_value_json      jsonb        NOT NULL,
    new_value_json           jsonb        NOT NULL,
    occurred_at              timestamptz  NOT NULL
);
-- Audit-log query endpoint orders by (parent, occurred_at DESC).
CREATE INDEX idx_pref_lc_audit_lc_time
    ON preference_lifestyle_config_audit (lifestyle_config_id, occurred_at DESC);
-- Reverse-actor index used by future cross-tenant safety review tooling.
CREATE INDEX idx_pref_lc_audit_actor
    ON preference_lifestyle_config_audit (actor_user_id, occurred_at DESC);
