-- Nutrition module — 01b intake slot child of intake_day.
-- UNIQUE (intake_day_id, meal_slot) — one slot row per meal slot per day.
-- needs_ai_parse is the 01b extension flagging override rows for the deferred (01k) AI parse.

CREATE TABLE nutrition_intake_slot (
    id                  uuid          PRIMARY KEY,
    intake_day_id       uuid          NOT NULL REFERENCES nutrition_intake_day(id) ON DELETE CASCADE,
    meal_slot           varchar(24)   NOT NULL,
    planned_recipe_id   uuid,
    planned_calories    integer,
    planned_protein_g   numeric(6,1),
    planned_carbs_g     numeric(6,1),
    planned_fat_g       numeric(6,1),
    planned_fibre_g     numeric(6,1),
    planned_micros      jsonb,
    actual_status       varchar(24)   NOT NULL DEFAULT 'PENDING',
    actual_calories     integer,
    actual_protein_g    numeric(6,1),
    actual_carbs_g      numeric(6,1),
    actual_fat_g        numeric(6,1),
    actual_fibre_g      numeric(6,1),
    actual_micros       jsonb,
    override_free_text  varchar(512),
    overridden_at       timestamptz,
    needs_ai_parse      boolean       NOT NULL DEFAULT false,
    UNIQUE (intake_day_id, meal_slot)
);

CREATE INDEX idx_nutr_intake_slot_day
    ON nutrition_intake_slot (intake_day_id);
