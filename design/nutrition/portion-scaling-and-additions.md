# Portion scaling + additions — requirements

The two levers that let a plan actually *hit* per-meal targets, once the slots are filled
(the 4-meal fix) with a real, micro-bearing pool (datahive + USDA fill + provenance).

## Why (the measured gap)
Per-meal targets are 900 / 1000 / 1100 / 600 kcal (= 3,600). The pool's median recipe is
**439 kcal/serving** (only ~8% ≥ 900), and the planner schedules **exactly one per-person
serving per slot** (`DailyMacroAggregator` counts 1 serving; `ScheduledRecipe.servings` is
*head-count*, deliberately not used for per-person intake). So a meal = one ~440-kcal serving
against a ~1,000-kcal target → the day caps near **2,600**, never 3,600. Two levers fix it:

- **Portion scaling** — eat *N servings* of the main → hits the calorie/protein **magnitude**.
- **Additions** — bolt small items onto the meal → fine-tunes **micros** + tops up residual cal.

They compose: scale the main to get most of the calories, then add to round out micros + close
the remainder.

---

## Lever 1 — Portion scaling

### Model
- New per-slot field **`portionFactor`** (decimal, default 1.0) on `ScheduledRecipe` (+ migration,
  + `ScheduledRecipeDto`). **Distinct from `servings`** (head-count). Semantics: the primary
  eater consumes `portionFactor` servings of the main recipe.
- `DailyMacroAggregator` multiplies the recipe's per-serving nutrition by `portionFactor` for the
  per-person daily total (today it hardcodes 1).
- Grocery `ShoppingListCalculator` already scales by `servings / recipeBaseServings`; multiply
  that by `portionFactor` so you buy enough food.

### Who sets it (recommend: deterministic, not AI)
In the planner, after a slot's main recipe is chosen, set
`portionFactor = clamp(round_to_step(perMealCalorieTarget / recipe.caloriesPerServing), MIN, MAX)`.
- Step = 0.25 (so "1.75 servings", not 1.732…). MIN = 0.5, MAX = 3.0.
- Protein-aware option: `factor = clamp(max(cal_factor, protein_factor), …)` so a low-cal but
  also low-protein pick scales toward whichever target is bindng — but bounded so it never
  fabricates a 5× plate.
- Deterministic > AI here: cheap, predictable, no token cost, no latency (matters given the
  ~3-min generation problem).

### Honesty / bounds
- Cap at 3×. If 3× still misses the target, leave the meal **short** (don't fabricate) — the
  coverage panel already shows the gap honestly.
- Don't scale below 0.5 (a slot is at least half a serving).
- Recipe with no computed nutrition → `portionFactor = 1` (can't size it).

### Surfaces
- Scoring: `NutritionSubScore` / floor gate read the *scaled* values (so the beam prefers
  recipes that scale into the target sensibly, not just raw per-serving).
- Coverage: flows automatically through the aggregator.
- Frontend: show "× 2 servings" on the slot; grocery quantities reflect it.

### Files (~6–8)
migration; `ScheduledRecipe` (+field); `ScheduledRecipeDto` (+field); `DailyMacroAggregator`
(× factor); the planner slot-assembly step that computes the factor; `ShoppingListCalculator`
(× factor); `Plan.tsx` slot render; tests.

---

## Lever 2 — Additions (the "adding things" idea, refined)

A slot's main recipe stays one recipe (the DB enforces one scheduled recipe per slot); additions
are a **separate per-slot list**, not a second scheduled recipe. Two *kinds* behind one model:

### Kind A — ingredient additions (recommended first)
Raw whole foods: a drizzle of olive oil, ½ avocado, a cup of berries, a side salad, a handful of
nuts/seeds, a spoon of yogurt. **Nutrition from USDA** (reuse the ingredient→grams matcher +
`food_nutrient` already built for the importer) → tagged `source="derived"`. Best lever for:
- **Micros** the pool is thin on — citrus/berries → vit C/folate; greens → folate/K/A; nuts/seeds
  → vit E + the trace minerals; avocado → E/K/potassium.
- **Small calorie/fat top-ups** — oil/nuts add dense calories to close a residual gap precisely.

Finer-grained than a whole side, reuses USDA, and directly attacks the produce-micro gap. Build
this first.

### Kind B — side-dish recipes (later)
Pool recipes tagged `dishType = side` (a grain side, roasted veg, a parfait). Nutrition from the
recipe. Richer/larger than an ingredient; overlaps somewhat with portion-scaling + snack slots.
Second priority.

### Unified shape
`additions: List<Addition>` on the scheduled recipe (JSONB), each:
`{ kind: INGREDIENT|SIDE_RECIPE, name, ref (usdaFoodKey|recipeId), quantity, unit, grams,
   nutrition:{calories, proteinG, carbsG, fatG, fibreG, micros}, microSources, microConfidence }`.

### Who decides (recommend: deterministic candidate-rank + LLM appropriateness gate)
1. **Deterministic** computes the day's residual after portion-scaling: short micros + calorie
   remainder, and ranks candidate additions by how well each fills them (gap → food map, e.g.
   low-C → citrus/berries). Cheap, no tokens.
2. **LLM** (gpt-5.4-mini) picks the *culinarily appropriate* one for THIS dish + the portion, and
   writes the note ("½ avocado on the taco salad"). Its strength; the math can't judge pairing.
   This is the recipe-changer AI you described. Skippable (deterministic-only) if cost/latency
   matters.

### Safety / bounds
- Every addition runs the **hard-constraint (allergy/diet) filter** — reuse `AugmentationVerifier`,
  so a nut/dairy addition that violates an allergy is rejected for free.
- 0–3 additions per meal; sane portions; no absurd stacks.

### Surfaces
- `DailyMacroAggregator` sums each addition's nutrition into the day (provenance flows through the
  worst-source blend already built).
- Grocery: additions = extra shopping line items.
- Frontend: "+ ½ avocado", "+ side salad" under the slot.

---

## How the two compose (per-slot meal-fill, in Phase-2)
1. Stage-C picks the main recipe (scored).
2. **Scale**: `portionFactor` → main hits most of the per-meal calorie/protein target.
3. Compute **residual**: calorie remainder (if main capped at 3×) + the day's short micros.
4. **Add**: pick ≤3 ingredient additions (deterministic gap-rank → LLM appropriateness) to close
   the residual + micros, allergy-checked.
5. Re-aggregate → coverage reflects scaled main + additions. **Target the meal TOTAL** so step 2
   and step 4 don't double-count calories.

Decided during Phase-2 (after Stage-C, before `RollupBuilder`) so coverage reflects it — the
composition-timing constraint from the in-meal-sides design.

---

## Phasing
- **Phase 1 — portion scaling.** Smallest, highest impact (the actual 3,600 blocker). Ship alone.
- **Phase 2 — ingredient additions** (USDA-derived) + deterministic gap-fill. Closes micros + the
  residual calorie gap. LLM appropriateness gate optional.
- **Phase 3 — side-dish recipes** + the full LLM recipe-changer pass.

## Open decisions (for sign-off before build)
1. **portionFactor**: step 0.25 & max 3.0 OK? Calorie-only or calorie-OR-protein binding?
2. **Additions kind**: ingredient-only first (recommended), or both kinds together?
3. **Decision engine**: deterministic-only (cheap, no tokens) or deterministic + LLM
   appropriateness (better pairings, uses gpt-5.4-mini, adds latency)?
4. Cap on additions/meal (default 3) and whether the snack slot should prefer additions vs a recipe.
