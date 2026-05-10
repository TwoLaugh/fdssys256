-- Nutrition module — 01b intake audit child of intake_day. Append-only.
-- One row per write action (PREFILL, CONFIRM, OVERRIDE, EDIT, SKIP, SNACK_ADD, SNACK_REMOVE).
-- meal_slot null for SNACK_*; snack_id populated for SNACK_*.

CREATE TABLE nutrition_intake_audit (
    id                      uuid          PRIMARY KEY,
    intake_day_id           uuid          NOT NULL REFERENCES nutrition_intake_day(id) ON DELETE CASCADE,
    actor_user_id           uuid          NOT NULL,
    action                  varchar(32)   NOT NULL,
    meal_slot               varchar(24),
    snack_id                uuid,
    previous_value_json     jsonb,
    new_value_json          jsonb,
    occurred_at             timestamptz   NOT NULL
);

CREATE INDEX idx_nutr_intake_audit_day_time
    ON nutrition_intake_audit (intake_day_id, occurred_at DESC);
