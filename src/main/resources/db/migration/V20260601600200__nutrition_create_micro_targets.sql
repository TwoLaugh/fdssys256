-- Nutrition module — 01a micro-target child of nutrition_targets.
-- One row per (targets_id, nutrient_key). nutrient_key is opaque (e.g. "iron_mg",
-- "vitamin_d_iu"); seeded with DRI defaults in 01c.

CREATE TABLE nutrition_micro_target (
    id                  uuid           PRIMARY KEY,
    targets_id          uuid           NOT NULL REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    nutrient_key        varchar(48)    NOT NULL,
    target_value        numeric(10,3),
    upper_limit         numeric(10,3),
    source_preference   varchar(24),
    notes               varchar(255),
    CONSTRAINT uq_nutrition_micro_targets_key UNIQUE (targets_id, nutrient_key)
);

CREATE INDEX idx_nutrition_micro_targets_targets
    ON nutrition_micro_target (targets_id);
