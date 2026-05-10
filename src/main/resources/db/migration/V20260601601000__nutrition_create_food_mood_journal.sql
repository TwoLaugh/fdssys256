-- Nutrition module — 01c food/mood journal. Standalone aggregate, one row per
-- (user_id, on_date, meal_slot). meal_slot is nullable to allow untied entries; Postgres treats
-- NULLs as not-equal in unique constraints, so multiple null-slot entries per (user_id, on_date)
-- are intentionally permitted.

CREATE TABLE nutrition_food_mood_journal (
    id                  uuid PRIMARY KEY,
    user_id             uuid NOT NULL,
    on_date             date NOT NULL,
    meal_slot           varchar(24),
    journal_entry       text NOT NULL,
    logged_at           timestamptz NOT NULL,
    optimistic_version  bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    UNIQUE (user_id, on_date, meal_slot)
);

CREATE INDEX idx_nutr_food_mood_user_date
    ON nutrition_food_mood_journal (user_id, on_date DESC);

CREATE INDEX idx_nutr_food_mood_user_logged_at
    ON nutrition_food_mood_journal (user_id, logged_at DESC);
