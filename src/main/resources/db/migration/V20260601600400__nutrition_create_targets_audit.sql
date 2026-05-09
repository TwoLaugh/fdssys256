-- Nutrition module — 01a append-only audit log for nutrition_targets changes.
-- One row per changed field. actor_kind enum: USER (01a), HEALTH_DIRECTIVE (01e), FEEDBACK (01h).
-- source_directive_id is nullable — set when actor_kind = HEALTH_DIRECTIVE; null otherwise.

CREATE TABLE nutrition_targets_audit (
    id                       uuid         PRIMARY KEY,
    targets_id               uuid         NOT NULL REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    actor_user_id            uuid         NOT NULL,
    actor_kind               varchar(24)  NOT NULL,
    source_directive_id      uuid,
    field_path               varchar(96)  NOT NULL,
    previous_value_json      jsonb        NOT NULL,
    new_value_json           jsonb        NOT NULL,
    occurred_at              timestamptz  NOT NULL
);

CREATE INDEX idx_nutrition_targets_audit_time
    ON nutrition_targets_audit (targets_id, occurred_at DESC);
