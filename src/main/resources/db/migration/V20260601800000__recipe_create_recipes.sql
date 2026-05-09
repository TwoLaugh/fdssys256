-- Recipe module — 01a aggregate root: recipe_recipes.
-- See lld/recipe.md §V20260601120000. Renumbered to V20260601800000 to sequence
-- after provisions (V…1700xxx).
--
-- The current_branch_id FK is added separately in V…800201 once recipe_branches exists.
-- forkedFromRecipeId / archivedAt / deletedAt / lastUsedInPlanAt columns ship now
-- (cheap; the flows that mutate them defer to recipe-01g) to avoid future migration churn.

CREATE TABLE recipe_recipes (
    id                          uuid          PRIMARY KEY,
    user_id                     uuid          NOT NULL,
    catalogue                   varchar(16)   NOT NULL DEFAULT 'USER',
    name                        varchar(160)  NOT NULL,
    description                 text,
    current_version             integer       NOT NULL DEFAULT 1,
    current_branch_id           uuid,
    data_quality                varchar(16)   NOT NULL DEFAULT 'USER_VERIFIED',
    nutrition_status            varchar(16)   NOT NULL DEFAULT 'PENDING',
    forked_from_recipe_id       uuid,
    last_used_in_plan_at        timestamptz,
    archived_at                 timestamptz,
    deleted_at                  timestamptz,
    optimistic_version          bigint        NOT NULL DEFAULT 0,
    created_at                  timestamptz   NOT NULL,
    updated_at                  timestamptz   NOT NULL
);

CREATE INDEX idx_recipe_recipes_catalogue_active
    ON recipe_recipes (catalogue)
    WHERE deleted_at IS NULL AND archived_at IS NULL;

CREATE INDEX idx_recipe_recipes_user_catalogue
    ON recipe_recipes (user_id, catalogue)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_recipe_recipes_system_last_used
    ON recipe_recipes (last_used_in_plan_at)
    WHERE catalogue = 'SYSTEM' AND deleted_at IS NULL;

CREATE INDEX idx_recipe_recipes_name_lower
    ON recipe_recipes (lower(name));

CREATE INDEX idx_recipe_recipes_forked_from
    ON recipe_recipes (forked_from_recipe_id)
    WHERE forked_from_recipe_id IS NOT NULL;
