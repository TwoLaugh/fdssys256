# Ticket: planner — 01e Scoring Engine + 7 Sub-Score Calculators + Multiplicative Gates

## Summary

Replace 01d's `StubScoringEngine` with the **real `ScoringEngineImpl`** composing the seven `SubScoreCalculator` implementations (`PreferenceSubScore`, `NutritionSubScore`, `CostSubScore`, `VarietySubScore`, `TimeSubScore`, `BatchSubScore`, `ProvisionsSubScore`) and the two multiplicative gates (`NutritionFloorGate`, `VarietyGate`). Adds the corresponding `ScoringWeights` block to `PlannerProperties` (`mealprep.planner.weights.*`) and the variety/cost/provisions tuning constants. Per `lld/planner.md` §`ScoringEngine`, §Sub-score formulas (LOCKED 2026-05-07), §`NutritionFloorGate`, §`VarietyGate`, §Configuration.

**Important content gap — `weightSchemeVersion`**: The LLD's seven formulas are LOCKED per the document. **The composite weights** (`w_preference, w_nutrition, …, w_provisions`) are v1-uniform `1/7 ≈ 0.143` per HLD §Initial Weights v1 — also locked. **There is no remaining "TBD by user" content for 01e** despite the parent prompt suggesting scoring math was TBD. The LLD has committed every per-sub-score formula (PreferenceSubScore: cosine on embeddings; NutritionSubScore: goal-driven asymmetric penalty; CostSubScore: confidence-weighted; VarietySubScore: target-based distinct count; BatchSubScore: 1 - distinct sessions / slot count; ProvisionsSubScore: covered_value / max_value; TimeSubScore: linear overshoot penalty). **01e ships the locked formulas verbatim**. Numerical constants (variety targets 5/4/3; expiry waste-value tiers; cost-confidence threshold) are tunable via `PlannerProperties` without redeploy. **Worth flagging to the user**: if calibration shows v1 uniform weights are wrong, the override is a properties change, not a code change.

**Defers**:
- `RollupBuilder` (Stage B) → **planner-01f** (consumes the score breakdown but doesn't compute it)
- Stage C / Phase 2 LLM stages → **planner-01g, 01h**
- Composer wiring → **planner-01j**
- Per-household weight tuning + calibration UI → out of scope v1 per LLD §Out of Scope

## LLD divergence — none of note

LLD §Sub-score formulas explicitly states **"Locked (2026-05-07)"**. 01e implements them verbatim. The only divergences are mechanical:
- Sub-score names match `PlannerProperties.weights.*` keys (`preference`, `nutrition`, `cost`, `variety`, `time`, `batch`, `provisions`).
- The seven calculators are wired via Spring `List<SubScoreCalculator>` injection; `ScoringEngineImpl` indexes by `.name()` and weights via the properties.

## Behavioural spec

### `ScoringEngineImpl` (replaces 01d's stub)

1. New `@Component` `ScoringEngineImpl implements ScoringEngine` under `domain/service/internal/scoring/`. Package-private. Dependencies via constructor: `List<SubScoreCalculator>` (Spring auto-wires all impls), `NutritionFloorGate`, `VarietyGate`, `PlannerProperties`.
2. **Composite formula** per LLD §`ScoringEngine`:
   ```
   composite = (Σ weight_i × sub_score_i) × Π gate_i
   ```
   where `gate_i ∈ {0, 1}`. If either gate returns `false`, `composite = 0`.
3. **`score(CandidatePlan plan, PlanCompositionContext ctx)`**:
   - Compute each sub-score: iterate `subScoreCalculators`, for each call `.compute(plan, ctx)` → `BigDecimal` in `[0, 1]`. Store by `calculator.name()`.
   - Weight: `weighted_i = weight[name] × sub_score_i`. Sum to get the unweighted composite.
   - Evaluate gates: `floorPassed = nutritionFloorGate.passes(plan, ctx)`, `varietyPassed = varietyGate.passes(plan, ctx)`. Multiply: `composite = unweighted × (floorPassed ? 1 : 0) × (varietyPassed ? 1 : 0)`.
   - Build `ScoreBreakdownDocument(preference, nutrition, cost, variety, time, batch, provisions, composite, floorPassed, varietyPassed, "v1-uniform")`.
   - Return `ScoreResult(composite, breakdown)`.
4. **`@Profile` annotation**: NONE. Real impl is the default; 01d's `StubScoringEngine` has `@Profile("test")` so the real impl wins in default profile. **Verify** by `@Autowired ScoringEngine` inside a non-test-profile IT and asserting `instanceof ScoringEngineImpl`.
5. **Validation**: assert each `sub_score_i ∈ [0, 1]` (defensive — a buggy calculator returning negative or > 1 would distort the composite). Throw `IllegalStateException` with message `"sub-score <name> returned " + value + ", expected [0, 1]"` on violation. **Worth user review** — alternative is to `clamp(0, 1)` silently. Rejected because a out-of-range value is a bug, not a recoverable condition.
6. **Determinism**: every calculator must be a pure function of `(plan, ctx)`. No DB calls inside `compute`. All data the calculator needs comes from `ctx` (which the composer pre-loads). **The exception**: `PreferenceSubScore` needs recipe embeddings (cosine sim against taste vectors); these are carried on `RecipeDto.versions[current].embedding` via the recipe-01h pgvector column. **01e treats `ctx.recipePool().recipes()[i].embedding` as pre-loaded**.

### `PreferenceSubScore`

7. New `@Component` `PreferenceSubScore implements SubScoreCalculator`. Package-private. Name: `"preference"`.
8. Algorithm per LLD §`PreferenceSubScore` (LOCKED):
   ```
   per_recipe_score(recipe, taste_vector):
     if recipe.embedding is null OR taste_vector is null:
       return 0.5                                       // neutral fallback
     cos = cosine_similarity(recipe.embedding, taste_vector)
     return (cos + 1) / 2                                // map [-1, 1] → [0, 1]

   PreferenceSubScore(plan):
     return mean(per_recipe_score(slot.recipe, taste_vector_for(slot.eaters)) for slot in plan.slots)
   ```
9. **Taste vector resolution**:
   - Per-person slot: `taste_vector_for(eaters)` = `ctx.softPrefsByUserId().get(eater.first()).tasteVector()`. For multi-eater per-person slots (unusual), average the vectors element-wise. **Worth user review**: alternative is to skip mixed-eater slots; rejected because `eaters` can carry multiple ids for fractional-share slots.
   - Shared slot: `ctx.mergedHouseholdPrefs().tasteVector()` — pre-merged by `HouseholdMergeService` (called by composer 01j; 01e reads).
10. **Cold-start behaviour**: if `taste_vector_status = 'pending'` OR `'failed'` on the user's taste profile, the bundle returns a null `tasteVector`. The calculator handles null by returning `0.5` neutral.
11. **Embedding shape**: `RecipeVersionDto.embedding` is `float[]` of length 1536 (per recipe-01h). The `tasteVector` is the same shape (per preference-01g embedding ticket — verify when wiring; preference-01g may not be merged yet). If preference doesn't expose `tasteVector` yet, **01e returns 0.5 universally** with a TODO. **Worth user review** — if the preference-01g embedding ticket is not yet shipped, 01e's preference sub-score effectively contributes neutral. Document this in `PreferenceSubScore`'s class javadoc.
12. **Cosine sim impl**: `dot(a, b) / (norm(a) × norm(b))`. Hand-rolled (3 lines). No external linear-algebra dep.

### `NutritionSubScore`

13. New `@Component` `NutritionSubScore implements SubScoreCalculator`. Name: `"nutrition"`.
14. Algorithm per LLD §`NutritionSubScore` (LOCKED):
    ```
    direction_score(actual, target, direction):
      if target is null OR target == 0:
        return 1.0
      deviation = (actual - target) / target
      match direction:
        UPPER_LIMIT:    penalty = max(0, deviation)
        LOWER_FLOOR:    penalty = max(0, -deviation)
        BOTH_BOUNDED:   penalty = abs(deviation)
      return max(0, 1 - penalty)

    NutritionSubScore(plan, targets):
      macros = [calories, protein, fat, carbs, fibre, saturated_fat (if set)]
      return mean(direction_score(plan.weekly[m], targets[m].targetG, targets[m].direction)
                  for m in macros if targets[m] is configured)
    ```
15. **`MacroTargetDto.direction`** comes from `nutrition-01a`'s `MacroTarget` aggregate. The bundle DTO field is `ctx.nutritionByUserId().get(userId).macroTargets()`. **01e divergence**: `PlanCompositionContext` from 01d does NOT carry `nutritionByUserId` (deferred to 01j's composer extension). **01e EXTENDS the `PlanCompositionContext` record** to add the `nutritionByUserId` field:
    ```java
    Map<UUID, NutritionForPlannerBundleDto> nutritionByUserId
    ```
    Add as a `@Nullable` field with empty-map default. **Worth user review** — alternative is to delay 01e until 01j extends the record. Rejected because 01e is the natural owner of nutrition-related scoring; it owns the extension. Tests pass `Map.of()` for fresh-generation cases.
16. **Plan-level weekly aggregates**: 01e does NOT compute weekly aggregates itself. Instead, it consumes `plan.assignments` and sums `recipe.nutritionPerServing` × `servings` across slots. `nutritionPerServing` is JSONB from recipe-01f (`RecipeNutritionResultDto`); shape is `{ caloriesPerServing, proteinG, carbsG, fatG, fibreG, saturatedFatG?, micros? }`. **01e ships a small `WeeklyNutritionAccumulator` helper** inside the calculator that walks `plan.assignments`, multiplies servings, sums per macro.
17. **For per-person slots**: macro contribution attributed only to the slot's eater. **01e divergence**: aggregation per eater is the same shape applied N times. **For 01e v1, aggregate against the household's primary user only** (`ctx.householdSettings().primaryUserId()` — verify exists; else fall back to first member). **Worth user review** — alternative is to score against each eater and average. Defer the per-eater nuance to a calibration pass; 01e ships the simpler "score against primary" form per the HLD's "household-default" mode.
18. **Missing nutrition data**: if `recipe.nutritionPerServing` is null (recipe-01h pending), treat that macro as 0. The macro's direction-score then reflects the under-count. **Worth user review** — alternative is to return `0.5` neutral when nutrition data is missing entirely. Rejected because cold-start nutrition is a real failure mode; surfacing it via lower scores nudges the planner toward recipes with computed nutrition.

### `CostSubScore`

19. New `@Component` `CostSubScore implements SubScoreCalculator`. Name: `"cost"`.
20. Algorithm per LLD §`CostSubScore` (LOCKED):
    ```
    raw_cost_fit = clamp(1 - (estimated_weekly_cost / weekly_budget), 0, 1)
    mean_confidence = confidence_weighted_mean(price_history.confidence for ingredient in plan.ingredients)
    CostSubScore(plan) = 0.5 + (raw_cost_fit - 0.5) × mean_confidence
    ```
21. **`weekly_budget`** = `ctx.provisions().budget().weeklyTargetGbp()`. **`null` budget**: return `0.5` neutral (no budget gate). This case is what 01f's bundle ticket flagged: `budget` can be null for users without a budget row.
22. **`estimated_weekly_cost`**: for each `plan.assignment`, walk `recipe.versions[current].ingredients`, look up the supplier-price entry in `ctx.provisions().supplierPricesByMappingKey().get(ingredient.mappingKey)`, multiply `(price × quantity × servings)`, sum. Currency: GBP, minor units (pence) → convert to `BigDecimal` GBP at output. **Worth user review** — alternative is to use the recipe-stored `costEstimate` if available (recipe-01a doesn't compute cost; recipe-01h+ doesn't either). 01e computes from supplier-price-times-quantity.
23. **`mean_confidence`** = `confidence_weighted_mean` of `supplierProduct.confidence` (in `[0, 1]`) across all ingredients in the plan that have a supplier-product entry. Ingredients without a supplier-product (key not in `supplierPricesByMappingKey`) contribute `confidence = 0` (low data → regression to neutral).
24. **Cold-start**: when `mean_confidence` is near 0 (most ingredients have no supplier price), the formula collapses `score → 0.5 + (raw - 0.5) × 0 = 0.5` — neutral. By design per LLD.
25. **Tunable**: `mealprep.planner.scoring.cost.confidence-threshold` (default 0.1) — confidences below this are clamped to 0. Avoids tiny-confidence noise pulling scores away from neutral.

### `VarietySubScore`

26. New `@Component` `VarietySubScore implements SubScoreCalculator`. Name: `"variety"`.
27. Algorithm per LLD §`VarietySubScore` (LOCKED):
    ```
    per_dimension_score(plan, dimension, target):
      distinct = count_distinct(slot.recipe.tags[dimension] for slot in plan.slots)
      return min(1.0, distinct / target)

    VarietySubScore(plan):
      return mean(
        per_dimension_score(plan, "cuisine", target=5),
        per_dimension_score(plan, "protein", target=4),
        per_dimension_score(plan, "cooking_method", target=3))
    ```
28. **Dimension field mapping**:
    - `cuisine` = `recipe.metadata().cuisine()` (from recipe-01a's `RecipeMetadataDto`)
    - `protein` = `recipe.tags().protein()` (from recipe-01a's `RecipeTagsDto`)
    - `cooking_method` = `recipe.tags().cookingMethod()`
    - Nulls are excluded from distinct count (a slot with `cuisine=null` doesn't contribute a distinct value, but doesn't increment the denominator either — the formula counts distinct non-null values).
29. **Tunable**: `mealprep.planner.scoring.variety.targets.cuisine` (default 5), `.protein` (default 4), `.cooking-method` (default 3). All `@Min(1)`.

### `BatchSubScore`

30. New `@Component` `BatchSubScore implements SubScoreCalculator`. Name: `"batch"`.
31. Algorithm per LLD §`BatchSubScore` (LOCKED):
    ```
    BatchSubScore(plan) = 1 - (count_distinct(slot.batch_cook_session_id) / len(plan.slots))
    ```
32. **Edge cases**:
    - Empty slots → score 1.0 (vacuous).
    - All slots have null `batchCookSessionId` (no batching) → distinct count is 0 (null excluded) → score `1 - 0/N = 1.0`. **Worth user review** — alternative is "all distinct (every slot its own session)" → score 0. The LLD's formula treats null as "no batch coordination" which DOES count distinctly. **01e treats null as a single bucket** ("no-batch") — so 21 slots with null `batchCookSessionId` gives distinct=1, score = `1 - 1/21 ≈ 0.95`. **Worth user review** — alternative is null → unique bucket per slot (score → 0). 01e's "null = single bucket" interpretation rewards consistency over fragmentation; document in the calculator's javadoc.

### `ProvisionsSubScore`

33. New `@Component` `ProvisionsSubScore implements SubScoreCalculator`. Name: `"provisions"`.
34. Algorithm per LLD §`ProvisionsSubScore` (LOCKED):
    ```
    if pantry_tracking_enabled is false:
      return 0.5                                       // neutral when feature disabled

    covered_value = sum(min(demand[i], inventory[i].quantity) × waste_value(inventory[i])
                        for i in plan.ingredients if i in inventory)
    max_value     = sum(demand[i] × max_waste_value for i in plan.ingredients)

    return clamp(covered_value / max_value, 0, 1)
    ```
35. **`pantry_tracking_enabled`** = `ctx.softPrefsByUserId().get(primaryUserId).lifestyleConfig().pantryTrackingEnabled()`. **Verify** lifestyle config DTO field name in preference-01a.
36. **`waste_value`** per LLD: 1.0 for >7d-to-expiry, 2.0 for ≤3d, 3.0 for ≤1d. **`max_waste_value` = 3.0**. Tunable via `mealprep.planner.scoring.provisions.waste-value-tiers.*`.
37. **`demand[i]`** = sum of `ingredient.quantity × servings` across all slots using ingredient `i`.
38. **`inventory[i]`** = `ctx.provisions().activeInventory().stream().filter(item -> item.ingredientMappingKey().equals(i)).findFirst()`.
39. **Disabled-pantry**: returns `0.5` neutral, NO inventory access. Cold start friendly.

### `TimeSubScore`

40. New `@Component` `TimeSubScore implements SubScoreCalculator`. Name: `"time"`.
41. Algorithm per LLD §`TimeSubScore` (LOCKED):
    ```
    slot_score(slot):
      if slot.recipe.total_time_mins <= slot.time_budget_min:
        return 1.0
      overshoot_ratio = (slot.recipe.total_time_mins - slot.time_budget_min) / slot.time_budget_min
      return max(0, 1 - overshoot_ratio)

    TimeSubScore(plan):
      return mean(slot_score(slot) for slot in plan.slots)
    ```
42. **Hard filter already applied**: 01d's `HardFilterRunner` drops recipes with `totalTimeMins > timeBudgetMin × maxTimeOvershootRatio` (1.5×). So `overshoot_ratio` is bounded to `≤ 0.5` at scoring time, and `slot_score` is in `[0.5, 1.0]`. The gradient applies within the surviving range per LLD.
43. **Edge case**: zero slots → return 1.0 (vacuous). Slot with null recipe (empty slot from a failed pool) → contributes 1.0 (vacuous, doesn't penalise empty slots — the qualityWarning flag tracks that separately).

### `NutritionFloorGate`

44. New `@Component` `NutritionFloorGate` under `internal/scoring/`. Package-private.
45. Method: `boolean passes(CandidatePlan plan, PlanCompositionContext ctx)`.
46. **Delegates to `NutritionFloorGateService.evaluate(primaryUserId, candidateRollup)`** — the service is already shipped in nutrition-01g. The gate reads `result.passed()`.
47. **Building the candidate rollup**: convert `plan` to a `CandidatePlanRollupDto` per nutrition-01g's shape (`startDate`, `endDate`, `perDay[]`). Each `CandidateDailyRollupDto` aggregates the macros per day from `plan.assignments` grouped by `onDate`. **Worth user review** — alternative is to delegate to `RollupBuilder` (01f); rejected because 01e doesn't depend on 01f. The gate builds its own ad-hoc rollup; the cosmetic duplication is fine because `RollupBuilder` produces a richer document for Stage C display, while the gate needs only macro totals per day.
48. **Disabled targets**: `NutritionFloorGateService.evaluate` returns `passed=true` when the user has no targets row (per nutrition-01g's spec). So the gate passes; no special-case logic in 01e.

### `VarietyGate`

49. New `@Component` `VarietyGate` under `internal/scoring/`. Package-private.
50. Method: `boolean passes(CandidatePlan plan, PlanCompositionContext ctx)`.
51. Algorithm per LLD §scoring: `max_repeat = 2` per recipe. Count `slot.recipeId` frequency across `plan.assignments`. If any recipe appears more than `maxRepeat` times → `false`. Tunable via `mealprep.planner.scoring.variety.max-repeat` (default 2; `@Min(1)`).
52. **Empty plan**: passes (vacuous).

### `PlannerProperties` extensions

53. **Append to existing `PlannerProperties`** (from 01d):
    ```java
    @NotNull ScoringWeights weights,
    @NotNull ScoringTuning scoring
    ```

    With new nested records:
    ```java
    public record ScoringWeights(
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal preference,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal nutrition,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal cost,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal variety,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal time,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal batch,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal provisions
    ) {}

    public record ScoringTuning(
        @NotNull VarietyTargets variety,
        @NotNull ProvisionsTuning provisions,
        @NotNull CostTuning cost
    ) {
      public record VarietyTargets(
          @Min(1) int cuisine,            // default 5
          @Min(1) int protein,            // default 4
          @Min(1) int cookingMethod,      // default 3
          @Min(1) int maxRepeat) {}       // default 2 (used by VarietyGate)

      public record ProvisionsTuning(
          @NotNull WasteValueTiers wasteValueTiers) {

        public record WasteValueTiers(
            @DecimalMin("0.0") BigDecimal aboveSevenDays,    // 1.0
            @DecimalMin("0.0") BigDecimal threeDaysOrLess,   // 2.0
            @DecimalMin("0.0") BigDecimal oneDayOrLess) {}   // 3.0
      }

      public record CostTuning(
          @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidenceThreshold) {}  // 0.1
    }
    ```
54. **`application.yml`** defaults appended:
    ```yaml
    mealprep:
      planner:
        weights:
          preference:  0.143
          nutrition:   0.143
          cost:        0.143
          variety:     0.143
          time:        0.143
          batch:       0.143
          provisions:  0.143
        scoring:
          variety:
            cuisine: 5
            protein: 4
            cooking-method: 3
            max-repeat: 2
          provisions:
            waste-value-tiers:
              above-seven-days: 1.0
              three-days-or-less: 2.0
              one-day-or-less: 3.0
          cost:
            confidence-threshold: 0.1
    ```
55. **Weight sum is NOT enforced to be 1.0** — the composite is `Σ weight × sub_score`, which is meaningful for any non-negative weight vector. Documenting in PlannerProperties javadoc. **Worth user review** — alternative is a `@AssertTrue` validator. Rejected because per-sub-score tuning may want non-uniform sums (e.g. boost preference by 2×).

## Database

**Zero migrations.** Pure code + properties.

## OpenAPI updates

**No new endpoints.** 01e adds no controller surface.

## Verbatim shape snippets

### `ScoringEngineImpl`

```java
@Component
@RequiredArgsConstructor
class ScoringEngineImpl implements ScoringEngine {

  private final List<SubScoreCalculator> calculators;
  private final NutritionFloorGate nutritionFloorGate;
  private final VarietyGate varietyGate;
  private final PlannerProperties properties;

  @Override
  public ScoreResult score(CandidatePlan plan, PlanCompositionContext ctx) {
    Map<String, BigDecimal> raw = new HashMap<>();
    for (SubScoreCalculator c : calculators) {
      BigDecimal s = c.compute(plan, ctx);
      assertInRange(c.name(), s);
      raw.put(c.name(), s);
    }
    BigDecimal unweighted = weightedSum(raw);
    boolean floorPassed = nutritionFloorGate.passes(plan, ctx);
    boolean varietyPassed = varietyGate.passes(plan, ctx);
    BigDecimal gateFactor = (floorPassed && varietyPassed)
        ? BigDecimal.ONE : BigDecimal.ZERO;
    BigDecimal composite = unweighted.multiply(gateFactor).setScale(6, RoundingMode.HALF_UP);

    ScoreBreakdownDocument breakdown = new ScoreBreakdownDocument(
        raw.get("preference"), raw.get("nutrition"), raw.get("cost"),
        raw.get("variety"), raw.get("time"), raw.get("batch"), raw.get("provisions"),
        composite, floorPassed, varietyPassed, "v1-uniform");

    return new ScoreResult(composite, breakdown);
  }

  private BigDecimal weightedSum(Map<String, BigDecimal> raw) {
    PlannerProperties.ScoringWeights w = properties.weights();
    return raw.get("preference").multiply(w.preference())
        .add(raw.get("nutrition").multiply(w.nutrition()))
        .add(raw.get("cost").multiply(w.cost()))
        .add(raw.get("variety").multiply(w.variety()))
        .add(raw.get("time").multiply(w.time()))
        .add(raw.get("batch").multiply(w.batch()))
        .add(raw.get("provisions").multiply(w.provisions()));
  }

  private void assertInRange(String name, BigDecimal value) {
    if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalStateException("sub-score " + name + " returned " + value + ", expected [0, 1]");
    }
  }
}
```

### Sub-score calculator skeleton — `TimeSubScore` (simplest)

```java
@Component
class TimeSubScore implements SubScoreCalculator {
  @Override public String name() { return "time"; }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    Map<UUID, MealSlotSkeleton> bySlotId = ctx.slotSkeletons().stream()
        .collect(Collectors.toMap(MealSlotSkeleton::slotId, Function.identity()));
    if (plan.assignments().isEmpty()) return BigDecimal.ONE;

    BigDecimal sum = BigDecimal.ZERO;
    int counted = 0;
    for (SlotAssignment a : plan.assignments()) {
      MealSlotSkeleton skel = bySlotId.get(a.slotId());
      if (skel == null) continue;
      RecipeDto recipe = findRecipe(ctx, a.recipeId()).orElse(null);
      if (recipe == null) { sum = sum.add(BigDecimal.ONE); counted++; continue; }
      int total = recipe.metadata().totalTimeMins();
      int budget = skel.timeBudgetMin();
      BigDecimal slotScore;
      if (total <= budget) {
        slotScore = BigDecimal.ONE;
      } else {
        BigDecimal overshoot = BigDecimal.valueOf(total - budget)
            .divide(BigDecimal.valueOf(budget), 6, RoundingMode.HALF_UP);
        slotScore = BigDecimal.ONE.subtract(overshoot).max(BigDecimal.ZERO);
      }
      sum = sum.add(slotScore);
      counted++;
    }
    return counted == 0 ? BigDecimal.ONE : sum.divide(BigDecimal.valueOf(counted), 6, RoundingMode.HALF_UP);
  }
}
```

## Edge-case checklist

### `ScoringEngineImpl`

- [ ] All seven sub-scores pass + both gates pass → composite = `Σ w × s` (no zero collapse)
- [ ] `NutritionFloorGate` fails → composite = 0 (regardless of sub-score values)
- [ ] `VarietyGate` fails → composite = 0
- [ ] Both gates fail → composite = 0
- [ ] Sub-score returns 1.1 → `IllegalStateException` with name in message
- [ ] Sub-score returns -0.1 → `IllegalStateException`
- [ ] Sub-score returns exactly 0 or exactly 1 → accepted (boundary inclusive)
- [ ] `ScoreBreakdownDocument.weightSchemeVersion = "v1-uniform"`
- [ ] Spring injects all 7 calculators via `List<SubScoreCalculator>` — verified by context-load IT

### `PreferenceSubScore`

- [ ] Recipe with null embedding → returns 0.5 for that recipe
- [ ] User with null taste vector → returns 0.5 for slots assigned to that user
- [ ] All slots null → plan score = 0.5
- [ ] Cosine = 1 (identical vectors) → recipe score = 1.0
- [ ] Cosine = -1 (anti-aligned vectors) → recipe score = 0.0
- [ ] Cosine = 0 (orthogonal) → recipe score = 0.5
- [ ] Shared slot uses `ctx.mergedHouseholdPrefs().tasteVector()`; per-person slot uses `softPrefsByUserId.get(eater).tasteVector()`

### `NutritionSubScore`

- [ ] Targets configured but plan meets exactly → score = 1.0
- [ ] UPPER_LIMIT direction, plan 50% over → `penalty = 0.5`, `direction_score = 0.5`
- [ ] LOWER_FLOOR direction, plan 50% under → `direction_score = 0.5`
- [ ] BOTH_BOUNDED direction, plan ±10% off → `direction_score = 0.9`
- [ ] Macro target null → `direction_score = 1.0` (no contribution)
- [ ] No targets row → score = 1.0 (mean over zero terms is vacuously 1.0; document the convention)
- [ ] Missing nutrition data on a recipe → that macro contributes 0 to actual; direction-score reflects undercount

### `CostSubScore`

- [ ] Budget null → score = 0.5 neutral
- [ ] Plan well under budget, high confidence → score near 1.0
- [ ] Plan over budget → score collapses toward 0 (× confidence)
- [ ] All ingredients lack supplier prices → `mean_confidence ≈ 0` → score = 0.5
- [ ] Mixed confidence — some ingredients with confidence 1.0, others 0 → score interpolates

### `VarietySubScore`

- [ ] 5 distinct cuisines, 4 proteins, 3 methods → score = 1.0
- [ ] 1 cuisine, 1 protein, 1 method (boring week) → score = `mean(0.2, 0.25, 0.33) ≈ 0.26`
- [ ] All slots null cuisine → cuisine dimension contributes 0 distinct → `min(1, 0/5) = 0`
- [ ] Tunable targets override at runtime — `@TestPropertySource` reduces cuisine target to 2; plan with 2 cuisines now scores 1.0 on that dimension

### `BatchSubScore`

- [ ] All slots same `batchCookSessionId` (one mega-batch) → score = `1 - 1/N` near 1.0
- [ ] All slots null `batchCookSessionId` → score = `1 - 1/N` near 1.0 (null-as-single-bucket convention)
- [ ] All slots distinct sessions → score = `1 - N/N = 0`

### `ProvisionsSubScore`

- [ ] `pantryTrackingEnabled = false` → score = 0.5
- [ ] All demand covered by inventory, all items >7d expiry → high `covered_value / max_value`
- [ ] Items near expiry (≤1d) weight 3× — favours plans that use them
- [ ] Empty inventory + pantry on → `covered_value = 0` → score = 0
- [ ] Recipe ingredient not in inventory → contributes to `max_value` but not `covered_value`

### `TimeSubScore`

- [ ] Slot at exactly budget → 1.0
- [ ] Slot 50% over budget → 0.5
- [ ] Slot 100% over → 0 (and hard filter would have dropped it, so this is a defensive boundary)
- [ ] Empty plan → 1.0 (vacuous)
- [ ] Slot with no recipe → 1.0 (doesn't penalise unfilled slots; qualityWarning tracks that)

### Gates

- [ ] `NutritionFloorGate` delegates to `NutritionFloorGateService.evaluate(primaryUserId, rollup)`; uses `.passed()` field
- [ ] `VarietyGate` rejects plans with >`maxRepeat` of one recipe; respects properties override
- [ ] `VarietyGate` with `maxRepeat = 2` accepts plans where every recipe ≤2 occurrences

### `PlannerProperties` extensions

- [ ] All weights defaults to 0.143 (seven equal weights summing to ~1.001 — rounding tolerance)
- [ ] `mealprep.planner.weights.preference=0.5` override picked up; ScoringEngine's `weightedSum` uses the new value
- [ ] Tuning constants override at runtime
- [ ] Negative weight fails validation at context load
- [ ] Weight > 1 fails validation

### Cross-cutting

- [ ] `ScoringEngineImpl` (production impl) wins over `StubScoringEngine` in default profile — `@Profile("test")` on stub keeps it from binding
- [ ] All seven calculators are `@Component`-scanned (no missing impl breaks the `List<SubScoreCalculator>` injection)
- [ ] `assertInRange` catches out-of-range returns
- [ ] `PlannerBoundaryTest` still passes — all new files under `internal/scoring/`
- [ ] No N+1 — sub-scores are pure functions over `ctx` and `plan`; no DB calls
- [ ] No regression on 01a-01d tests
- [ ] No `pom.xml` dep adds
- [ ] No other modules' files touched

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/ScoringEngineImpl.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/PreferenceSubScore.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/NutritionSubScore.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/CostSubScore.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/VarietySubScore.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/BatchSubScore.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/ProvisionsSubScore.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/TimeSubScore.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/NutritionFloorGate.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/VarietyGate.java

MOD   src/main/java/com/example/mealprep/planner/config/PlannerProperties.java                              (append ScoringWeights, ScoringTuning records)
MOD   src/main/java/com/example/mealprep/planner/api/dto/PlanCompositionContext.java                        (add nutritionByUserId field)
MOD   src/main/resources/application.yml                                                                     (append weights + tuning blocks)

NEW   src/test/java/com/example/mealprep/planner/ScoringEngineImplTest.java
NEW   src/test/java/com/example/mealprep/planner/PreferenceSubScoreTest.java
NEW   src/test/java/com/example/mealprep/planner/NutritionSubScoreTest.java
NEW   src/test/java/com/example/mealprep/planner/CostSubScoreTest.java
NEW   src/test/java/com/example/mealprep/planner/VarietySubScoreTest.java
NEW   src/test/java/com/example/mealprep/planner/BatchSubScoreTest.java
NEW   src/test/java/com/example/mealprep/planner/ProvisionsSubScoreTest.java
NEW   src/test/java/com/example/mealprep/planner/TimeSubScoreTest.java
NEW   src/test/java/com/example/mealprep/planner/NutritionFloorGateTest.java
NEW   src/test/java/com/example/mealprep/planner/VarietyGateTest.java
NEW   src/test/java/com/example/mealprep/planner/ScoringEngineIT.java                                       (full context: real impls + real properties; verify ScoringEngineImpl wins over Stub)
MOD   src/test/java/com/example/mealprep/planner/testdata/PlanTestData.java                                  (add: planWithVariety, planWithBudgetOver, planWithInventoryCoverage builders)
```

Count: ~24 files. Estimated agent runtime 50-60 min — at the upper bound; if the implementer feels tight, the 7 calculators could split into 01e + 01e' but the LLD groups them as one ticket. The math is locked so there's no design churn.

**Files this ticket does NOT modify**:
- `PlansController`, `PlannerExceptionHandler`, `PlanQueryService`, `PlannerServiceImpl` — no controller surface added.
- OpenAPI YAMLs — no endpoints.
- Migrations — none.
- Other modules — none touched.

## Gotchas to bake in

1. **`BigDecimal` arithmetic**: always specify scale + rounding mode. `divide` without those throws `ArithmeticException` on non-terminating decimals. Use `divide(divisor, 6, RoundingMode.HALF_UP)` consistently.
2. **`BigDecimal.compareTo`** not `.equals`. `1.0.equals(1.00)` is false; `.compareTo` is the right comparison.
3. **`@Component` package-private**: all 10 new classes are package-private (no `public` modifier on the class). Spring still picks them up via component-scan.
4. **`@Profile("test")` on the stub wins in test contexts that activate the test profile** — `@SpringBootTest` activates it by default. **Verify**: 01e's `ScoringEngineIT` should test the production impl, so it needs to either deactivate the test profile (`@ActiveProfiles("!test")` doesn't exist; use `@ActiveProfiles({})` to clear; or add `@Profile("!test")` to the production impl). **Recommended**: 01d's stub uses `@Profile("scoring-stub")` (not `test`), and ITs explicitly activate that profile when they want the stub. Production tests run in the default profile and see the real `ScoringEngineImpl`. **01e adjusts 01d's stub annotation** — `@Profile("test") → @Profile("scoring-stub")`. **Worth user review** — alternative is `@Primary` on the real impl. Rejected because `@Primary` doesn't help when both beans match.
5. **`List<SubScoreCalculator>` ordering**: Spring injects in `@Order` order (or class-name order if unset). **Don't rely on order** — `ScoringEngineImpl` indexes by `.name()`. Test order-independence by adding a calculator with a different name and asserting the engine still works.
6. **Cross-module DTO names** (verify on classpath when wiring):
   - `RecipeDto.versions[current].embedding` — `float[]` from recipe-01h (pgvector).
   - `RecipeMetadataDto.cuisine`, `totalTimeMins`.
   - `RecipeTagsDto.protein`, `cookingMethod`.
   - `NutritionForPlannerBundleDto.macroTargets()` — from nutrition-01h's bundle (verify shape).
   - `MacroTargetDto.direction` (UPPER_LIMIT / LOWER_FLOOR / BOTH_BOUNDED enum).
   - `SoftPreferenceBundleDto.tasteVector()` — from preference-01g (verify; may be null if 01g not merged).
   - `ProvisionForPlannerBundleDto.activeInventory()`, `budget()`, `supplierPricesByMappingKey()`, `staleness()`.
   - `LifestyleConfigDocument.pantryTrackingEnabled()` — from preference-01a.
7. **Cosine similarity NaN guard**: if `norm(a) == 0` or `norm(b) == 0`, the formula divides by zero. Return `0.5` neutral (treat zero-vector as no signal).
8. **Recipe lookup in calculator**: `findRecipe(ctx, recipeId)` walks `ctx.recipePool().recipes()` — `O(N)` per lookup; with 7 calculators × 21 slots, that's 7×21×500 = ~73k iterations. **Worth user review** — alternative is to pass an index `Map<UUID, RecipeDto>` from the composer. Recommended: 01e adds a transient helper field to `PlanCompositionContext` OR each calculator builds its own index on first use. For 01e v1, **build the index per-calculator at start** (one O(N) walk per `compute` call). Optimisation deferred until profiling.
9. **`@DecimalMin` / `@DecimalMax` on `BigDecimal`**: takes a String parameter (`"0.0"`). Don't pass a `double`.
10. **`@TestPropertySource("mealprep.planner.weights.cost=0")`**: a calculator's weight at 0 means that sub-score contributes nothing — useful for testing individual calculator math by zeroing the others.
11. **`NutritionFloorGate` delegates to a cross-module service**: inject `NutritionFloorGateService` by interface only. **Do NOT @MockBean** in IT unless the test also mocks the full nutrition service surface (multi-interface trap — `NutritionServiceImpl` implements `NutritionQueryService, NutritionUpdateService, NutritionCalculationService, NutritionFloorGateService` per the nutrition module's pattern). Quick check: `grep "implements" .../nutrition/.../NutritionServiceImpl.java`. For the integration IT, use the real nutrition service with a seeded targets row.
12. **`@Validated` on the appended properties record fields**: `@Validated` is annotated on the record class once (in 01d); appending fields doesn't require re-annotation. Each field's `@NotNull`/`@Min`/etc. is honoured.

## Dependencies

- **Hard dependency**: `planner-01d` (merged) — `ScoringEngine` interface, `SubScoreCalculator` interface, `CandidatePlan`, `SlotAssignment`, `PlanCompositionContext`, `PlannerProperties`, `StubScoringEngine` (whose profile annotation 01e adjusts).
- **Hard dependency**: `nutrition-01g` (merged) — `NutritionFloorGateService`, `CandidatePlanRollupDto`, `CandidateDailyRollupDto`, `FloorGateResultDto`.
- **Hard dependency**: `nutrition-01h` (merged) — `NutritionForPlannerBundleDto.macroTargets` shape.
- **Hard dependency**: `preference-01g` (or fallback) — `SoftPreferenceBundleDto.tasteVector`. If preference-01g not yet merged, `PreferenceSubScore` returns 0.5 universally with TODO.
- **Hard dependency**: `recipe-01h` (merged) — `RecipeVersionDto.embedding` (`float[]`).
- **Hard dependency**: `provisions-01f` (merged) — `ProvisionForPlannerBundleDto` and its child DTOs.
- **Sibling tickets running in parallel** (Wave 3 round 2): `planner-01f` (RollupBuilder — independent; reads the breakdown but doesn't write it).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `ScoringEngineImpl` wins over `StubScoringEngine` in the default profile — verified by IT
- [ ] All 7 sub-score calculators registered as `@Component` and discoverable via `List<SubScoreCalculator>` injection
- [ ] `PlannerBoundaryTest` still passes
- [ ] No `pom.xml` dep adds
- [ ] No other modules' files touched
- [ ] Determinism — same `(plan, ctx)` → byte-identical `ScoreResult` (no `Instant.now`, no random)

Squash-merge with: `feat(planner): 01e — ScoringEngine + 7 sub-score calculators (preference, nutrition, cost, variety, time, batch, provisions) + NutritionFloorGate + VarietyGate + scoring properties block`

## What's NOT in scope

- `RollupBuilder` (Stage B) → **planner-01f**
- Stage C / Phase 2 LLM stages → **planner-01g, 01h**
- Composer wiring (`PlanComposer`) → **planner-01j**
- Per-household weight tuning → out of scope v1
- Calibration tooling (offline plan-replay against new weights) → out of scope v1
- Cost-confidence threshold tuning UI → properties only; UI deferred
- Pre-computed batch_cook_session_id assignment — the calculator reads what the composer put there; composer does the actual session-id assignment based on `RecipeMetadataDto.batchCookable + lifestyle.prepDays`. **Composer logic lands in 01j** — 01e tests can use fixtures with manually-set `batchCookSessionId` values.
