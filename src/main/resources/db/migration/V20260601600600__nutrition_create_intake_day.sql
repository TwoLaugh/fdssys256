-- Nutrition module — 01b intake day aggregate root. One row per (user_id, on_date).
-- Owns intake_slot, intake_snack, intake_audit children via FK with ON DELETE CASCADE.
-- @Version on the parent covers concurrency for the whole graph.

CREATE TABLE nutrition_intake_day (
    id          uuid          PRIMARY KEY,
    user_id     uuid          NOT NULL,
    on_date     date          NOT NULL,
    plan_id     uuid,
    version     bigint        NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    UNIQUE (user_id, on_date)
);

CREATE INDEX idx_nutr_intake_day_user_date
    ON nutrition_intake_day (user_id, on_date DESC);
