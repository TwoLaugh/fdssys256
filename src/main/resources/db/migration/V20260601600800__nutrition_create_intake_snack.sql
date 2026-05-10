-- Nutrition module — 01b intake snack child of intake_day.
-- No business-key uniqueness — multiple snacks per day allowed.

CREATE TABLE nutrition_intake_snack (
    id                      uuid          PRIMARY KEY,
    intake_day_id           uuid          NOT NULL REFERENCES nutrition_intake_day(id) ON DELETE CASCADE,
    ingredient_mapping_key  varchar(255),
    free_text               varchar(255)  NOT NULL,
    quantity_g              numeric(8,1)  NOT NULL,
    calories                integer       NOT NULL,
    protein_g               numeric(6,1)  NOT NULL,
    carbs_g                 numeric(6,1)  NOT NULL,
    fat_g                   numeric(6,1)  NOT NULL,
    fibre_g                 numeric(6,1),
    micros                  jsonb,
    source                  varchar(24)   NOT NULL,
    logged_at               timestamptz   NOT NULL
);

CREATE INDEX idx_nutr_intake_snack_day
    ON nutrition_intake_snack (intake_day_id);
