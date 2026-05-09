-- Recipe module — 01a recipe_branches.
-- See lld/recipe.md §V20260601120200.
--
-- 01a only ever inserts the auto-created 'main' branch (one per recipe). Non-main
-- branches and the user-facing branches[] DTO/endpoint defer to recipe-01b/01d.

CREATE TABLE recipe_branches (
    id                          uuid          PRIMARY KEY,
    recipe_id                   uuid          NOT NULL REFERENCES recipe_recipes(id) ON DELETE CASCADE,
    parent_branch_id            uuid,
    branch_point_version_id     uuid,
    name                        varchar(64)   NOT NULL,
    label                       varchar(120),
    reason                      text,
    current_version             integer       NOT NULL DEFAULT 1,
    divergence_score            numeric(4,3)  NOT NULL DEFAULT 0.000,
    created_at                  timestamptz   NOT NULL,
    created_by_actor            varchar(64)   NOT NULL,  -- holds 'user:<uuid>' (41 chars)
    adapter_trace_id            uuid,
    version                     bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_recipe_branches_recipe_name UNIQUE (recipe_id, name)
);

CREATE INDEX idx_recipe_branches_recipe
    ON recipe_branches (recipe_id);

CREATE INDEX idx_recipe_branches_divergence
    ON recipe_branches (recipe_id, divergence_score DESC);
