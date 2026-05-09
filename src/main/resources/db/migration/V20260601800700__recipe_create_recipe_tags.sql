-- Recipe module — 01a recipe_tags (one row per version).
-- See lld/recipe.md §V20260601120700.
--
-- flavour_profile and dietary_flags stored as jsonb list-of-strings (text[] workaround
-- — same as recipe_metadata). GIN indexes defer to recipe-01i alongside search.

CREATE TABLE recipe_tags (
    id                  uuid          PRIMARY KEY,
    version_id          uuid          NOT NULL UNIQUE REFERENCES recipe_versions(id) ON DELETE CASCADE,
    protein             varchar(64),
    cooking_method      varchar(64),
    complexity          varchar(24),
    flavour_profile     jsonb         NOT NULL DEFAULT '[]'::jsonb,
    dietary_flags       jsonb         NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX idx_recipe_tags_protein
    ON recipe_tags (protein);

CREATE INDEX idx_recipe_tags_cooking_method
    ON recipe_tags (cooking_method);

CREATE INDEX idx_recipe_tags_complexity
    ON recipe_tags (complexity);
