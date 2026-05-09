-- Recipe module — 01a recipe_versions append-only history.
-- See lld/recipe.md §V20260601120100.
--
-- 01a omits the embedding vector(1536) column and the partial HNSW index — both
-- ship in recipe-01h alongside CREATE EXTENSION vector. embedding_status is the
-- target column the 01h listener writes when an embed completes.
--
-- branch_id has no FK yet — recipe_branches table is created in V…800200; the FK
-- lands in V…800201.

CREATE TABLE recipe_versions (
    id                          uuid          PRIMARY KEY,
    recipe_id                   uuid          NOT NULL REFERENCES recipe_recipes(id) ON DELETE CASCADE,
    branch_id                   uuid          NOT NULL,
    version_number              integer       NOT NULL,
    parent_version_id           uuid,
    change_diff                 jsonb         NOT NULL DEFAULT '{}'::jsonb,
    change_reason               text,
    trigger                     varchar(32)   NOT NULL,
    character_fingerprint       jsonb,
    nutrition_per_serving       jsonb,
    embedding_status            varchar(16)   NOT NULL DEFAULT 'pending',
    created_at                  timestamptz   NOT NULL,
    created_by_actor            varchar(64)   NOT NULL,  -- holds 'user:<uuid>' (41 chars)
    adapter_trace_id            uuid,
    CONSTRAINT uq_recipe_versions_recipe_branch_ver
        UNIQUE (recipe_id, branch_id, version_number)
);

CREATE INDEX idx_recipe_versions_recipe_branch_ver
    ON recipe_versions (recipe_id, branch_id, version_number);

CREATE INDEX idx_recipe_versions_trace_id
    ON recipe_versions (adapter_trace_id)
    WHERE adapter_trace_id IS NOT NULL;
