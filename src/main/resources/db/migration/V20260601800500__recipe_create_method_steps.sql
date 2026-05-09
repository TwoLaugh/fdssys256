-- Recipe module — 01a recipe_method_steps.
-- See lld/recipe.md §V20260601120500.

CREATE TABLE recipe_method_steps (
    id                  uuid          PRIMARY KEY,
    version_id          uuid          NOT NULL REFERENCES recipe_versions(id) ON DELETE CASCADE,
    step_number         integer       NOT NULL,
    instruction         text          NOT NULL,
    duration_minutes    integer,
    CONSTRAINT uq_recipe_method_steps_version_step UNIQUE (version_id, step_number)
);

CREATE INDEX idx_recipe_method_steps_version
    ON recipe_method_steps (version_id);
