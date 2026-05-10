-- Recipe module — 01b recipe_imports.
-- See lld/recipe.md §V20260601120800 (renumbered to the recipe timestamp range).
--
-- One row per recipe (UNIQUE recipe_id). Stores import provenance — source URL,
-- the extraction strategy that won, and the raw JSONB payload (lazy-loaded so
-- hot getById reads do not pull a full HTML excerpt).

CREATE TABLE recipe_imports (
    id                       uuid          PRIMARY KEY,
    recipe_id                uuid          NOT NULL UNIQUE REFERENCES recipe_recipes(id) ON DELETE CASCADE,
    source_type              varchar(16)   NOT NULL,
    source_url               text,
    source_payload           jsonb,
    extraction_method        varchar(32),
    duplicate_of_recipe_id   uuid,                                  -- soft FK; dedupe lands later
    imported_at              timestamptz   NOT NULL,
    imported_by_user_id      uuid          NOT NULL
);

CREATE INDEX idx_recipe_imports_url
    ON recipe_imports (source_url)
    WHERE source_url IS NOT NULL;

CREATE INDEX idx_recipe_imports_dedupe
    ON recipe_imports (duplicate_of_recipe_id)
    WHERE duplicate_of_recipe_id IS NOT NULL;
