-- Phase 1b (portion-scaling + additions design): persist the per-person portion factor on each
-- scheduled recipe so grocery quantities scale with it and the UI can show "× N servings".
-- Distinct from `servings` (household head-count). Defaults to 1.0 for every existing row.
ALTER TABLE planner_scheduled_recipes
    ADD COLUMN portion_factor numeric(4,2) NOT NULL DEFAULT 1.0;
