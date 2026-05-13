# Ticket: planner — 01f Rollup Builder (Stage B: per-candidate flat summary)

## Summary

Pure-function ticket that builds per-candidate **daily + weekly rollups** consumed by Stage C's LLM prompt + the persisted `RollupSummaryDocument`. Lands `RollupBuilder` interface + `RollupBuilderImpl`, the package-private `DailyRollup` and `WeeklyRollup` intermediate carriers, and the `CandidatePlanRollupDto`-builder bridge so 01e's `NutritionFloorGate` and 01f's full builder share the same per-day aggregation logic. Per `lld/planner.md` §`RollupBuilder` (Stage B), §DTOs §`RollupSummaryDocument`/`DailyRollupDocument`/`WeeklyRollupDocument`.

01f is **deterministic, no I/O, no DB**. The builder walks an already-loaded `CandidatePlan` against the already-loaded `PlanCompositionContext` and emits the flat summary structures. It's the cheapest stage in the loop — runs once per top-N candidate in Stage A.

**Defers**:
- Stage C invocation (which consumes the rollup) → **planner-01g**
- Persistence of `RollupSummaryDocument` to the `Plan` row → that happens in 01j's composer flow when the final candidate gets persisted
- Cost-confidence rollup details — currently approximated; the deeper calibration is a tuning track

## LLD divergence — `RollupBuilder` consumes the same data as `NutritionFloorGate`

Both 01e's gate and 01f's builder need to aggregate macros per day across a candidate plan's slots. **01e built an ad-hoc rollup** inside the gate (per the 01e ticket's verbatim note). **01f extracts the per-day macro aggregation into a shared helper** — `DailyMacroAggregator` (package-private under `internal/rollup/`) — and refactors 01e's `NutritionFloorGate` to delegate to it.

**Worth user review**: alternative is to leave the duplication. Rejected because:
- Two implementations of "aggregate plan macros per day" will drift.
- The aggregator is ~30 lines; centralising it costs 1 file, saves N file-rewrites.
- The build-the-rollup-once-and-share pattern matches the LLD's "pure function over the candidate plan and context — no DB lookups" intent (LLD §`RollupBuilder` line 817).

This is a small refactor of 01e's `NutritionFloorGate` — 1 method swap. 01f's IT verifies the gate still passes/fails identically against canonical fixtures.

## Behavioural spec

### `RollupBuilder` interface + impl

1. New interface under `domain.service.internal.rollup/`:
   ```java
   public interface RollupBuilder {
     RollupSummaryDocument build(CandidatePlan plan, PlanCompositionContext context);
   }
   ```
2. `@Component` `RollupBuilderImpl` under same package, package-private. Dependencies via constructor: `DailyMacroAggregator`, `PlannerProperties` (for tunable constants).
3. **`@Transactional` not required** — pure function, no DB.
4. Algorithm per LLD §`RollupBuilder` line 813-819:
   - Compute per-day rollup: walk `plan.assignments` grouped by `slot.onDate`. For each day, sum macros (calories, protein, fat, carbs, fibre) using the same `DailyMacroAggregator` shared with 01e's gate. Sum cost (per-slot estimated cost from supplier prices), sum total time. Collect violations.
   - Compute weekly rollup: aggregate across all days; track `staleIngredientCount`, `varietyIndex`, `batchCookSessions`, `constraintViolations`.
   - Return a fully-populated `RollupSummaryDocument(List<DailyRollupDocument> daily, WeeklyRollupDocument weekly)`.

### `DailyMacroAggregator` (shared helper)

5. Package-private `@Component` class:
   ```java
   class DailyMacroAggregator {
     Map<LocalDate, DailyMacroTotals> aggregateByDate(CandidatePlan plan, PlanCompositionContext ctx);
   }

   record DailyMacroTotals(
       LocalDate date, int kcal, BigDecimal proteinG, BigDecimal fatG, BigDecimal carbsG, BigDecimal fibreG,
       BigDecimal saturatedFatG, Map<String, BigDecimal> micros) {}
   ```
6. Algorithm:
   - Build an index `Map<UUID, RecipeDto> byRecipeId` from `ctx.recipePool().recipes()`.
   - For each `SlotAssignment a` in `plan.assignments`:
     - Look up `recipe = byRecipeId.get(a.recipeId())`; skip if null (unfilled slot).
     - Read `recipe.versions[current].nutritionPerServing` — JsonNode JSONB carrying `{ caloriesPerServing, proteinG, ... }`. **If null** (recipe-01h's nutrition pipeline hasn't run), treat all macros as 0 for that slot. **Worth user review** — alternative is to mark the day as `staleIngredientCount > 0` and surface to the user. The `staleIngredientCount` weekly-rollup field tracks this; the daily totals just under-count.
     - Multiply each macro by `a.servings()`. Sum into the day's bucket (keyed by `ctx.slotSkeletons().find(slotId).onDate()`).
   - Return the map.
7. **`NutritionFloorGate`** (from 01e) is refactored to use `DailyMacroAggregator`:
   ```java
   class NutritionFloorGate {
     private final DailyMacroAggregator aggregator;
     private final NutritionFloorGateService gateService;
     // ...
     boolean passes(CandidatePlan plan, PlanCompositionContext ctx) {
       Map<LocalDate, DailyMacroTotals> daily = aggregator.aggregateByDate(plan, ctx);
       CandidatePlanRollupDto rollupDto = toRollupDto(daily, ctx);
       UUID primaryUserId = resolvePrimaryUserId(ctx);
       return gateService.evaluate(primaryUserId, rollupDto).passed();
     }
   }
   ```
   The `toRollupDto` helper converts `DailyMacroTotals` → `CandidateDailyRollupDto` (per nutrition-01g's shape — same data shape, different field names; e.g. `kcal → calories`).

### `DailyRollup` builder

8. For each day, build `DailyRollupDocument`:
   - `date`: the slot's `onDate`.
   - `kcal`: sum of calories across slots on this date.
   - `proteinG`, `fatG`, `carbsG`, `fibreG`: sums (BigDecimal, 1 decimal place).
   - `costGbp`: sum of `(supplier_price × ingredient.quantity × servings)` across slots × ingredients on this date. Same computation as 01e's `CostSubScore`; **refactor to a shared `DailyCostAggregator`**. **Worth user review** — alternative is to leave 01e + 01f compute independently. Rejected for the same drift reason as the macro aggregator. **01f adds `DailyCostAggregator`** alongside the macro one.
   - `totalTimeMin`: sum of `recipe.metadata().totalTimeMins()` per slot on this date.
   - `violations`: list of strings — per-day issues (e.g. `"day exceeds calorie target by 12%"`, `"slot 'lunch' has no scheduled recipe"`). Populated by walking the day's slots and checking against the user's nutrition targets and the slot fill-state. **01f v1 ships a minimal violations list** — only `"slot X is unfilled"` per missing recipe. Macro-violation strings are richer but the LLD's spec doesn't pin exact wording; **worth user review**.

### `WeeklyRollup` builder

9. Weekly aggregate per LLD §DTOs `WeeklyRollupDocument`:
   - `kcalTotal`: sum across days.
   - `proteinAvgG`, `fatAvgG`, `carbsAvgG`: per-day mean (sum / count).
   - `costEstimateGbp`: sum across days.
   - `costConfidence`: confidence-weighted mean of per-ingredient confidences. Same formula as 01e's `CostSubScore.mean_confidence`. **Refactor to a `WeeklyCostConfidence` helper** to keep 01e + 01f aligned. **Worth user review** — same justification as macro / cost aggregators.
   - `staleIngredientCount`: count of `recipe.versions[current]` with `nutritionPerServing == null` across all slots in the plan (recipe-01h's nutrition status `PENDING` or `null`).
   - `varietyIndex`: same metric 01e's `VarietySubScore` computes — pull from the score breakdown that's already on the plan's `ScoreResult`. **01f reads `plan.scoreResult.breakdown.variety`** rather than recomputing. **Worth user review** — alternative is recompute (defensive). Rejected because the breakdown is already deterministic.
   - `batchCookSessions`: `count_distinct(slot.batchCookSessionId for slot in plan.slots, excluding null)`.
   - `constraintViolations`: union of all per-day violations + any plan-level issues (e.g. `"hard floor breach"` if the gate returned false). Pulled from the gates' verdicts (which 01f re-runs via `DailyMacroAggregator`). **Worth user review** — alternative is to require the gates' results passed in. 01f re-runs the floor gate for the violations text but uses cached `ScoreResult` for sub-score values.

## Database

**Zero migrations.** Pure code.

## OpenAPI updates

**No new endpoints.** `RollupSummaryDocument` is already published as a schema by 01a (as part of `PlanDto`). 01f doesn't touch the OpenAPI surface.

## Verbatim shape snippets

### `RollupBuilderImpl`

```java
@Component
@RequiredArgsConstructor
class RollupBuilderImpl implements RollupBuilder {

  private final DailyMacroAggregator macroAggregator;
  private final DailyCostAggregator costAggregator;
  private final WeeklyCostConfidence costConfidence;
  private final NutritionFloorGate floorGate;        // for violations text only
  private final PlannerProperties properties;

  @Override
  public RollupSummaryDocument build(CandidatePlan plan, PlanCompositionContext ctx) {
    Map<LocalDate, DailyMacroTotals> dailyMacros = macroAggregator.aggregateByDate(plan, ctx);
    Map<LocalDate, BigDecimal> dailyCosts = costAggregator.aggregateByDate(plan, ctx);
    Map<LocalDate, Integer> dailyTotalTimes = aggregateTotalTime(plan, ctx);
    Map<LocalDate, List<String>> dailyViolations = computeViolations(plan, ctx, dailyMacros);

    List<DailyRollupDocument> daily = dailyMacros.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> new DailyRollupDocument(
            e.getKey(),
            e.getValue().kcal(),
            e.getValue().proteinG(),
            e.getValue().fatG(),
            e.getValue().carbsG(),
            e.getValue().fibreG(),
            dailyCosts.getOrDefault(e.getKey(), BigDecimal.ZERO),
            dailyTotalTimes.getOrDefault(e.getKey(), 0),
            dailyViolations.getOrDefault(e.getKey(), List.of())))
        .toList();

    WeeklyRollupDocument weekly = buildWeekly(plan, ctx, daily);

    return new RollupSummaryDocument(daily, weekly);
  }

  private WeeklyRollupDocument buildWeekly(CandidatePlan plan, PlanCompositionContext ctx,
                                            List<DailyRollupDocument> daily) {
    int kcalTotal = daily.stream().mapToInt(DailyRollupDocument::kcal).sum();
    int n = Math.max(1, daily.size());
    BigDecimal proteinAvg = daily.stream().map(DailyRollupDocument::proteinG)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP);
    BigDecimal fatAvg = daily.stream().map(DailyRollupDocument::fatG)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP);
    BigDecimal carbsAvg = daily.stream().map(DailyRollupDocument::carbsG)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP);

    BigDecimal costTotal = daily.stream().map(DailyRollupDocument::costGbp)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal confidence = costConfidence.compute(plan, ctx);

    int staleCount = countStaleIngredients(plan, ctx);
    BigDecimal varietyIndex = plan.scoreResult() != null && plan.scoreResult().breakdown() != null
        ? plan.scoreResult().breakdown().variety()
        : BigDecimal.ZERO;
    int batchSessions = (int) plan.assignments().stream()
        .map(SlotAssignment::batchCookSessionId).filter(Objects::nonNull).distinct().count();

    List<String> constraintViolations = aggregateViolations(plan, ctx);

    return new WeeklyRollupDocument(
        kcalTotal, proteinAvg, fatAvg, carbsAvg,
        costTotal, confidence, staleCount, varietyIndex, batchSessions, constraintViolations);
  }
}
```

### `DailyMacroAggregator`

```java
@Component
class DailyMacroAggregator {

  Map<LocalDate, DailyMacroTotals> aggregateByDate(CandidatePlan plan, PlanCompositionContext ctx) {
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);
    Map<UUID, LocalDate> slotIdToDate = ctx.slotSkeletons().stream()
        .collect(Collectors.toMap(MealSlotSkeleton::slotId, MealSlotSkeleton::onDate));

    Map<LocalDate, DailyMacroTotals.Builder> builders = new TreeMap<>();

    for (SlotAssignment a : plan.assignments()) {
      LocalDate date = slotIdToDate.get(a.slotId());
      if (date == null) continue;
      DailyMacroTotals.Builder b = builders.computeIfAbsent(date, DailyMacroTotals.Builder::new);
      RecipeDto recipe = byRecipeId.get(a.recipeId());
      if (recipe == null) continue;
      JsonNode npn = recipe.versions().get(0).nutritionPerServing();        // current version
      if (npn == null) continue;     // stale; under-count handled at the rollup level

      b.addKcal(npn.path("caloriesPerServing").asInt(0) * a.servings());
      b.addProtein(readBd(npn, "proteinG").multiply(BigDecimal.valueOf(a.servings())));
      b.addFat(readBd(npn, "fatG").multiply(BigDecimal.valueOf(a.servings())));
      b.addCarbs(readBd(npn, "carbsG").multiply(BigDecimal.valueOf(a.servings())));
      b.addFibre(readBd(npn, "fibreG").multiply(BigDecimal.valueOf(a.servings())));
      // saturatedFatG + micros similarly
    }
    return builders.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().build(),
                                  (a, b) -> a, LinkedHashMap::new));
  }

  private BigDecimal readBd(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isMissingNode() || v.isNull() ? BigDecimal.ZERO : new BigDecimal(v.asText());
  }
}
```

## Edge-case checklist

### `RollupBuilder`

- [ ] Empty plan (no assignments) → daily list empty, weekly all zeros, `staleIngredientCount = 0`
- [ ] Full 7-day plan with all slots filled → daily list size 7, sorted by date ASC
- [ ] Day with all slots having null nutrition → kcal=0, macros=0, but day still appears in daily list (with `staleIngredientCount` reflecting the recipes counted)
- [ ] Weekly avg macros respect per-day mean (sum / 7), not per-slot mean
- [ ] `costGbp` sums correctly across days; weekly `costEstimateGbp` equals sum of daily costs
- [ ] `staleIngredientCount` counts distinct recipes with null `nutritionPerServing` (not slots)
- [ ] `varietyIndex` pulled from `plan.scoreResult().breakdown().variety()` (not recomputed)
- [ ] `batchCookSessions` counts distinct non-null `batchCookSessionId`
- [ ] `constraintViolations` includes `"hard floor breach"` when 01e's `NutritionFloorGate` returns false
- [ ] Daily list sorted by date ASC

### `DailyMacroAggregator`

- [ ] Two slots same day, same recipe, 2 servings each → kcal contribution = `2 × calories × 2 servings × 2 slots` = `4 × calories`. Verify multiplication.
- [ ] Slot's recipe missing from pool → skip silently (slot is treated as 0-macro)
- [ ] Recipe with null `nutritionPerServing` → skip silently (treated as 0-macro)
- [ ] Sorted by date (TreeMap / sorted stream)

### `NutritionFloorGate` (refactor)

- [ ] Same fixture as 01e's `NutritionFloorGateTest` produces byte-identical `boolean passes(...)` result — verified by re-running 01e's tests after the refactor
- [ ] Performance: `aggregateByDate` is O(N slots), called once per `passes` call, not O(N²)

### Cross-cutting

- [ ] Determinism — same `(plan, ctx)` → byte-identical `RollupSummaryDocument`
- [ ] No DB calls — verified by mocking nothing and running with no repository on classpath
- [ ] No N+1 (vacuously — no DB access at all)
- [ ] `PlannerBoundaryTest` still passes
- [ ] No regression on 01a-01e tests
- [ ] No `pom.xml` dep adds
- [ ] No other modules' files touched

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/rollup/RollupBuilder.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/rollup/RollupBuilderImpl.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/rollup/DailyMacroAggregator.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/rollup/DailyCostAggregator.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/rollup/WeeklyCostConfidence.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/rollup/DailyMacroTotals.java         (package-private record)

MOD   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/NutritionFloorGate.java     (refactor to delegate to DailyMacroAggregator)
MOD   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/CostSubScore.java           (refactor to delegate to DailyCostAggregator + WeeklyCostConfidence — DRY)

NEW   src/test/java/com/example/mealprep/planner/RollupBuilderTest.java
NEW   src/test/java/com/example/mealprep/planner/DailyMacroAggregatorTest.java
NEW   src/test/java/com/example/mealprep/planner/DailyCostAggregatorTest.java
NEW   src/test/java/com/example/mealprep/planner/WeeklyCostConfidenceTest.java
NEW   src/test/java/com/example/mealprep/planner/RollupBuilderIT.java                                        (full-context fixture: 7-day plan; verify NutritionFloorGate result unchanged)
MOD   src/test/java/com/example/mealprep/planner/testdata/PlanTestData.java                                  (add: weeklyPlanFixture, planWithStaleNutrition builders)
```

Count: ~13 files. Estimated agent runtime 30-40 min.

**Files this ticket does NOT modify**:
- `PlansController`, `PlannerExceptionHandler`, public service interfaces — no surface change.
- OpenAPI YAMLs — no endpoints; `RollupSummaryDocument` schema already published.
- Migrations — none.
- Other modules — none touched.

## Gotchas to bake in

1. **`BigDecimal` arithmetic** — explicit scale + RoundingMode on every `divide`. Use `HALF_UP` consistently.
2. **`JsonNode` field reads**: `node.path("foo").asInt(0)` is null-safe (returns missingNode if absent; `asInt(0)` defaults to 0). `node.get("foo")` is NOT null-safe. **Use `path`**.
3. **`JsonNode` for BigDecimal**: `new BigDecimal(node.path("proteinG").asText())` is the canonical pattern. `asDouble` loses precision; avoid.
4. **`Map<LocalDate, ...>` ordering**: use `TreeMap` for natural-order; `LinkedHashMap` if you've already sorted the keys. The verbatim impl uses `TreeMap` for the builder map, then converts to `LinkedHashMap` for stable iteration order downstream.
5. **`@Component` package-private**: all 6 new classes are package-private. Spring picks them up; ArchUnit's boundary rule still holds.
6. **`Stream.collect(Collectors.toMap(...))`** with a merge function — required when duplicate keys are possible. Day aggregation has no duplicates by construction (one bucket per date) but use the 4-arg form to be explicit.
7. **`DailyMacroTotals` is a record with a builder**: alternative is a mutable POJO. Records are immutable + builder is a static nested class. Choose the form that compiles cleanly — Lombok's `@Builder` on a record doesn't work; either hand-roll the builder OR use a mutable internal class.
8. **`plan.scoreResult()` may be null**: during the search loop, `CandidatePlan` is built without a final score; the `ScoreResult` is set at the end. **01f's builder is called AFTER scoring**, so `scoreResult` is non-null. But test fixtures may build `CandidatePlan` directly — guard with `Objects.requireNonNullElse(...).breakdown()...`.
9. **Refactor coordination with 01e**: 01f modifies `NutritionFloorGate` and `CostSubScore` to use the shared aggregators. **Verify 01e's tests still pass after the refactor** — same fixtures, same results. The refactor is mechanical (method body swap, no signature change).
10. **Don't introduce circular deps**: `RollupBuilder` → `DailyMacroAggregator` → (no further deps inside planner). `NutritionFloorGate` (in scoring/) → `DailyMacroAggregator` (in rollup/). The cross-package import is fine inside the same module — boundary rule prohibits cross-MODULE repository imports, not cross-PACKAGE service imports.

## Dependencies

- **Hard dependency**: `planner-01a` (merged) — `RollupSummaryDocument`, `DailyRollupDocument`, `WeeklyRollupDocument` records.
- **Hard dependency**: `planner-01d` (merged) — `CandidatePlan`, `SlotAssignment`, `PlanCompositionContext`, `RecipeDto` consumer, `MealSlotSkeleton`.
- **Hard dependency**: `planner-01e` (merged) — `ScoreResult`, `NutritionFloorGate`, `CostSubScore` (refactored to use shared aggregators).
- **Hard dependency**: `recipe-01h` (merged) — `RecipeVersionDto.nutritionPerServing` JsonNode shape.
- **Hard dependency**: `provisions-01f` (merged) — `ProvisionForPlannerBundleDto.supplierPricesByMappingKey` used by cost aggregator.
- **Hard dependency**: `nutrition-01g` (merged) — `CandidatePlanRollupDto`, `CandidateDailyRollupDto`, `FloorGateResultDto` shapes (consumed by the gate's delegate).
- **Sibling tickets running in parallel** (Wave 3 round 3): `planner-01g` (Stage C — consumes 01f's output but ships separately), `planner-01h` (Phase 2 — independent).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] 01e's `NutritionFloorGateTest` and `CostSubScoreTest` still pass after the refactor — no behaviour change
- [ ] Determinism — same `(plan, ctx)` → byte-identical `RollupSummaryDocument`
- [ ] `PlannerBoundaryTest` still passes
- [ ] No `pom.xml` dep adds
- [ ] No other modules' files touched

Squash-merge with: `feat(planner): 01f — RollupBuilder (Stage B) + DailyMacroAggregator / DailyCostAggregator / WeeklyCostConfidence shared helpers (refactor NutritionFloorGate + CostSubScore)`

## What's NOT in scope

- Stage C invocation (consumer of the rollup) → **planner-01g**
- Phase 2 augmenter → **planner-01h**
- Composer wiring + persistence → **planner-01j**
- Decision-log integration (rollup gets logged per iteration) → **planner-01l**
- Variations of rollup for "what-if" presentation (per-eater attribution, per-slot drilldown) — out of scope v1
- Cost confidence calibration tuning — properties only
- Per-day prep-time vs cook-time decomposition — `totalTimeMin` is the only time field at the rollup layer; richer breakdowns are out of scope v1
