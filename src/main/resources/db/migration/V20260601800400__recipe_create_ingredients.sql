-- Recipe module — 01a recipe_ingredients (children of recipe_versions).
-- See lld/recipe.md §V20260601120400.

CREATE TABLE recipe_ingredients (
    id                       uuid          PRIMARY KEY,
    version_id               uuid          NOT NULL REFERENCES recipe_versions(id) ON DELETE CASCADE,
    line_order               integer       NOT NULL,
    ingredient_mapping_key   varchar(160)  NOT NULL,
    display_name             varchar(160)  NOT NULL,
    quantity                 numeric(10,3),
    unit                     varchar(16),
    preparation              varchar(80),
    optional                 boolean       NOT NULL DEFAULT false,
    needs_review             boolean       NOT NULL DEFAULT false,
    mapping_confidence       numeric(4,3),
    CONSTRAINT uq_recipe_ingredients_version_line UNIQUE (version_id, line_order)
);

CREATE INDEX idx_recipe_ingredients_version
    ON recipe_ingredients (version_id);

CREATE INDEX idx_recipe_ingredients_mapping_key
    ON recipe_ingredients (ingredient_mapping_key);

CREATE INDEX idx_recipe_ingredients_version_key
    ON recipe_ingredients (version_id, ingredient_mapping_key);
