-- Phase 2 (portion-scaling + additions design): in-meal additions riding on a slot's main recipe.
-- A JSONB list of Addition records (kind, name, ingredientMappingKey|recipeId, quantity/unit/grams,
-- own per-portion nutrition incl. micros + provenance, reasoning). Defaults to an empty array so
-- every pre-existing scheduled recipe is valid and Hibernate's NOT NULL mapping agrees.
ALTER TABLE planner_scheduled_recipes
    ADD COLUMN additions jsonb NOT NULL DEFAULT '[]'::jsonb;
