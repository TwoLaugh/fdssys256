# In-meal sides / additions — design (thread C)

Status: **designed, not yet built.** Scoped here rather than rushed because it spans the
planner composition pipeline, persistence, grocery, and the frontend, and the *nutritional*
substance it targets is already delivered another way (see "Why deferred").

## Goal
Let the recipe-changer AI bolt a small, appropriate side onto an existing meal — "a fruit
plate with dinner", "avocado on the taco salad" — so the plan can close micro gaps (vitamin
C/A/E/folate — the fresh-produce nutrients the recipe pool is thin on) without swapping the
main dish.

## Why this is non-trivial (the constraints found)
1. **One recipe per slot is DB-enforced**: `planner_scheduled_recipes.slot_id` is `UNIQUE`, and
   the API surfaces a single `scheduledRecipe` per `MealSlotDto`. A side is therefore NOT a
   second scheduled recipe — it must be an *addition* attached to the slot's one recipe.
2. **Coverage is computed on the composition path**: `DailyMacroAggregator` sums one serving
   per `SlotAssignment` from the in-memory `CandidatePlan`, and `RollupBuilder` runs *after*
   Stage-C/Phase-2. So for a side to move the coverage numbers it must be decided **during
   composition** (Phase-2) and carried on the `SlotAssignment` the aggregator walks — a
   post-hoc "attach to a persisted plan" path would need a separate rollup-recompute.
3. **Allergy safety is free if reused**: the existing `AugmentationVerifier` already runs the
   hard-constraint filter on any `Augmentation`, so an `ADD_SIDE` kind inherits allergy
   rejection for nothing.

## Proposed design
- **Model a side as a 1-ingredient recipe** (avocado, berries, spinach, Greek yogurt…) seeded
  from the already-loaded USDA whole-foods — reuses recipe/version/nutrition/grocery/allergy
  plumbing wholesale instead of a parallel "raw food" path. ~30–50 seed sides.
- **`additions` on the scheduled recipe**: `additions JSONB` on `planner_scheduled_recipes`
  (+ `ScheduledRecipe` entity + `ScheduledRecipeDto.additions: [{recipeId, name, servings,
  nutrition}]`). One recipe per slot stays true; additions ride alongside.
- **Aggregator**: `DailyMacroAggregator` sums each addition's per-serving nutrition into the
  day totals (and its `microSources` flow through the provenance blend already built).
- **Decision point**: a new `ADD_SIDE` `Augmentation` kind (sibling of `AddSnackAugmentation`)
  emitted by Phase-2 — the deterministic side picks the gap-filling candidate (by which short
  micro it covers), the LLM picks the culinarily-appropriate one for that dish + portion. The
  verifier allergy-checks it. Applied onto the chosen `CandidatePlan` BEFORE the rollup so
  coverage reflects it.
- **Grocery**: the shopping-list builder reads `scheduledRecipe.additions` as extra line items.
- **Frontend**: render "+ avocado" under the slot; the coverage panel already shows the
  resulting micro improvement + provenance.
- **Bounds**: 1–2 additions per meal; sane portions.

## Effort
~8–10 files across planner (entity, DTO, aggregator, augmentation kind + parser + verifier +
apply), a migration, grocery, recipe-seed, and frontend. A focused build of its own — not a
tail-end addition.

## Why deferred (and what already covers the need)
The *nutritional outcome* sides target — micro-dense items rounding out the day — is already
achieved by: the 4-meal fill fix (breakfast+snack now populate, +800 kcal, micros 16→26/28),
the real-recipe pool, USDA micro fill, and the honest met/short/NO_DATA + provenance coverage.
Sides add the *UX* ("avocado on the plate") and finer per-meal control. Recommend building it
as its own slice once the above is accepted.
