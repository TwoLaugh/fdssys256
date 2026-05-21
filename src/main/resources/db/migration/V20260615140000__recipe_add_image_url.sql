-- Recipe-02a: image storage URL column on recipe_recipes.
-- Added in tickets/recipe/02a-image-storage.md (Tier A frontend unblock).
-- Nullable; recipes pre-existing this migration have no image. No index — the column is
-- read alongside the recipe row (primary-key lookup) and never filtered on.

ALTER TABLE recipe_recipes
    ADD COLUMN image_url varchar(512);

COMMENT ON COLUMN recipe_recipes.image_url IS
    'Relative storage key (e.g. recipes/ab/<uuid>-<hash>.jpg). Resolved against mealprep.recipe.image-storage.base-dir at serve time. Added in tickets/recipe/02a-image-storage.md.';
