-- Planner module — 01a child tables: planner_days, planner_meal_slots, planner_scheduled_recipes.
-- See lld/planner.md §V20260507120100.
--
-- planner_meal_slots.eaters and the slot/scheduled_recipe FKs to other modules (recipes,
-- recipe_versions, recipe_branches) are DELIBERATELY soft refs only — no DB-level FK is declared
-- for cross-module references per LLD §Database lines 261-263. Cross-module integrity is enforced
-- at service layer.

CREATE TABLE planner_days (
    id                       uuid          PRIMARY KEY,
    plan_id                  uuid          NOT NULL REFERENCES planner_plans(id) ON DELETE CASCADE,
    on_date                  date          NOT NULL,
    notes                    varchar(255),
    UNIQUE (plan_id, on_date)
);
-- Iterate days for a plan in order.
CREATE INDEX idx_planner_days_plan_date
    ON planner_days (plan_id, on_date);

CREATE TABLE planner_meal_slots (
    id                       uuid          PRIMARY KEY,
    day_id                   uuid          NOT NULL REFERENCES planner_days(id) ON DELETE CASCADE,
    plan_id                  uuid          NOT NULL REFERENCES planner_plans(id) ON DELETE CASCADE,
    slot_index               integer       NOT NULL,
    kind                     varchar(16)   NOT NULL,
    label                    varchar(64)   NOT NULL,
    time_budget_min          integer       NOT NULL,
    shared                   boolean       NOT NULL,
    eaters                   uuid[]        NOT NULL DEFAULT '{}',
    state                    varchar(16)   NOT NULL DEFAULT 'PLANNED',
    pinned_reason            varchar(32),
    UNIQUE (day_id, slot_index)
);
-- Hot reads: state filters during re-opt scope build, kind filter for analytics.
CREATE INDEX idx_planner_meal_slots_plan_state
    ON planner_meal_slots (plan_id, state);
CREATE INDEX idx_planner_meal_slots_day
    ON planner_meal_slots (day_id);

CREATE TABLE planner_scheduled_recipes (
    id                       uuid          PRIMARY KEY,
    slot_id                  uuid          NOT NULL UNIQUE REFERENCES planner_meal_slots(id) ON DELETE CASCADE,
    recipe_id                uuid          NOT NULL,
    recipe_version_id        uuid          NOT NULL,
    recipe_branch_id         uuid          NOT NULL,
    servings                 integer       NOT NULL,
    batch_cook_session_id    uuid,
    augmentation_notes       varchar(512),
    augmentation_source      varchar(16),
    phase2_addition          boolean       NOT NULL DEFAULT false
);
-- The grocery module aggregates ingredients per batch-cook session.
CREATE INDEX idx_planner_scheduled_recipes_batch
    ON planner_scheduled_recipes (batch_cook_session_id)
    WHERE batch_cook_session_id IS NOT NULL;
-- Reverse lookup: which plans currently use this recipe? Used by the recipe deletion guard.
CREATE INDEX idx_planner_scheduled_recipes_recipe
    ON planner_scheduled_recipes (recipe_id);
