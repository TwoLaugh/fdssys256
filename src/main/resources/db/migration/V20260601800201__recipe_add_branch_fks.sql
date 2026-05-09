-- Recipe module — 01a FKs that must wait until recipe_branches exists.
-- See lld/recipe.md §V20260601120201.

ALTER TABLE recipe_recipes
    ADD CONSTRAINT fk_recipe_recipes_current_branch
    FOREIGN KEY (current_branch_id) REFERENCES recipe_branches(id);

ALTER TABLE recipe_versions
    ADD CONSTRAINT fk_recipe_versions_branch
    FOREIGN KEY (branch_id) REFERENCES recipe_branches(id);
