-- Nutrition module — 01d ingredient mapping cache. Standalone aggregate; one row per (normalised)
-- search_term. Backs the cache-check → USDA → OFF pipeline (see IngredientMappingPipeline).
--
-- search_term is always lowercase + trimmed via IntakeKeyNormaliser. The partial index on
-- needs_review = true backs the /needs-review admin endpoint efficiently.

CREATE TABLE nutrition_ingredient_mapping (
    id                  uuid PRIMARY KEY,
    search_term         varchar(255) NOT NULL UNIQUE,        -- always lowercase + trimmed
    source              varchar(24) NOT NULL,                -- usda | open_food_facts | manual
    external_id         varchar(64),
    nutrition_per_100g  jsonb NOT NULL,
    default_piece_grams integer,
    confidence          numeric(4,3) NOT NULL,
    needs_review        boolean NOT NULL DEFAULT false,
    last_verified_at    timestamptz,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);

CREATE UNIQUE INDEX idx_nutr_ingredient_mapping_search_term
    ON nutrition_ingredient_mapping (search_term);

CREATE INDEX idx_nutr_ingredient_mapping_needs_review
    ON nutrition_ingredient_mapping (needs_review)
    WHERE needs_review = true;
