-- Nutrition module — 01a per-meal distribution child of nutrition_targets.
-- One row per (targets_id, meal_slot). meal_slot is the JPA enum stored as a string.

CREATE TABLE nutrition_per_meal_distribution (
    id                  uuid          PRIMARY KEY,
    targets_id          uuid          NOT NULL REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    meal_slot           varchar(16)   NOT NULL,
    calorie_target      integer       NOT NULL DEFAULT 0,
    protein_target_g    numeric(6,1)  NOT NULL DEFAULT 0,
    CONSTRAINT uq_nutrition_per_meal UNIQUE (targets_id, meal_slot)
);

CREATE INDEX idx_nutrition_per_meal_targets
    ON nutrition_per_meal_distribution (targets_id);
