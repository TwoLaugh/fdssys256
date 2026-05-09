-- Recipe module — 01a recipe_metadata (one row per version).
-- See lld/recipe.md §V20260601120600.
--
-- equipment_required and meal_types stored as jsonb list-of-strings (not text[])
-- — Hibernate's text[] mapping is brittle on SB 3.2.5 / hypersistence-utils-63
-- (same workaround as preference_hard_constraints.allergies / nutrition).
-- GIN indexes on these JSONB columns deferred to recipe-01i (search ticket).

CREATE TABLE recipe_metadata (
    id                  uuid          PRIMARY KEY,
    version_id          uuid          NOT NULL UNIQUE REFERENCES recipe_versions(id) ON DELETE CASCADE,
    servings            integer       NOT NULL,
    prep_time_mins      integer       NOT NULL,
    cook_time_mins      integer       NOT NULL,
    total_time_mins     integer       NOT NULL,
    equipment_required  jsonb         NOT NULL DEFAULT '[]'::jsonb,
    fridge_days         integer,
    freezer_weeks       integer,
    packable            boolean       NOT NULL DEFAULT false,
    cuisine             varchar(64),
    meal_types          jsonb         NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX idx_recipe_metadata_total_time
    ON recipe_metadata (total_time_mins);

CREATE INDEX idx_recipe_metadata_cuisine
    ON recipe_metadata (cuisine);
