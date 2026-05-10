-- Household module — 01b settings audit log (append-only).
-- One row per changed `fieldPath` per `PUT /settings`. JSONB before/after carries the
-- value at that path. No @Version, no updated_at. Index on (household_settings_id,
-- occurred_at DESC) for newest-first paginated reads.
-- See lld/household.md §V20260501130200, §Entities.

CREATE TABLE household_settings_audit (
    id                      uuid          PRIMARY KEY,
    household_settings_id   uuid          NOT NULL REFERENCES household_settings(id) ON DELETE CASCADE,
    actor_user_id           uuid          NOT NULL,
    field_path              varchar(128)  NOT NULL,
    previous_value_json     jsonb         NOT NULL,
    new_value_json          jsonb         NOT NULL,
    occurred_at             timestamptz   NOT NULL
);

CREATE INDEX idx_household_settings_audit_hs_time
    ON household_settings_audit (household_settings_id, occurred_at DESC);
