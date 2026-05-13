# Ticket: planner — 01d Beam Search Engine + Hard Filter Runner + Beam Pruner (Stage A composition)

## Summary

Pure-logic Stage-A composition ticket. Lands `BeamSearchEngine`, `HardFilterRunner`, `BeamPruner`, `PartialPlan`, `CandidatePlan` (value carriers), `BeamSearchConfig`, the partial `PlanCompositionContext` record (the read-only bundle the search consumes), `RecipePoolSnapshot`, the first `PlannerProperties` keys (`beamWidth`, `topN`, `minPoolPerSlot`, `maxPoolPerSlot`, `maxTimeOvershootRatio`, `stageATimeout`, `weekStartDayOfWeek`), and the **`SubScoreCalculator` interface stub** (interface only — implementations land in 01e). Per `lld/planner.md` §`BeamSearchEngine`, §`HardFilterRunner` + `BeamPruner`, §Read pattern §`PlanCompositionContext`, §Configuration §`PlannerProperties`.

01d ships **deterministic beam-search infrastructure** with a `ScoringEngine` placeholder that returns `BigDecimal.ZERO` so the search is testable end-to-end against canonical fixtures. The real scoring math lands in 01e; 01d's tests focus on the search algorithm (top-N invariants, beam-size cap, pinned-slot respect) using a stub `ScoringEngine` that returns deterministic values keyed by recipe id.

**Defers**:
- `ScoringEngine` real impl + the 7 `SubScoreCalculator`s + gates → **planner-01e**
- `RollupBuilder` → **planner-01f**
- `StageCInvoker` (Stage C LLM call) → **planner-01g**
- `Phase2Augmenter` → **planner-01h**
- `PlanComposer` end-to-end orchestrator + `ConstraintFeasibilityCheck` → **planner-01j** (controllers + composer wiring)
- Pinned-slot derivation from a current `Plan` → **planner-01i** (mid-week re-opt's `PinningRules`)

## LLD divergence — `ScoringEngine` interface lands in 01d

LLD §`ScoringEngine` lines 664-688 defines the `ScoringEngine` interface. **01d ships the interface declaration** (so `BeamSearchEngine` can compile and inject it) but ships a **`StubScoringEngine` implementation** in `internal/scoring/` that returns `ScoreResult(BigDecimal.ZERO, defaultBreakdown)`. The stub is annotated `@Component @Primary @Profile("!production")` so 01e's real impl supersedes it at runtime AND in production profile. **Worth user review** — alternative is to ship `BeamSearchEngine` without a `ScoringEngine` dep and have 01e wire it in. Rejected because the search algorithm fundamentally requires a scoring function (it's `top-N by score`); without a stub, the unit tests can't verify ordering. Same pattern as recipe-01f's SPI-with-Noop.

## LLD divergence — `PlanCompositionContext` partial in 01d

LLD §Read pattern lines 1167-1183 declares 13 fields on `PlanCompositionContext`. **01d ships the record** but with **only the fields needed by Stage A search**: `householdId`, `weekStartDate`, `hardConstraintsByUserId`, `softPrefsByUserId`, `mergedHouseholdPrefs`, `provisions`, `householdSettings`, `recipePool`, `traceId`, `decisionId`. **Defer** to 01e/01f/01g/01j: `nutritionByUserId`, `lifestyle`, `ingredientPriceConfidenceByMappingKey`, `household` (the entity vs the bundle DTOs).

This keeps 01d's compile-time dependency surface narrow — 01d only needs to know about `PreferenceQueryService`, `ProvisionForPlannerService`, `HouseholdQueryService`, `RecipeQueryService`, `HardConstraintFilterService`. Wave-2 bundle DTOs are stable; cross-module references compile cleanly.

01j's composer extends the record with the deferred fields before passing to 01e's scoring + 01f's rollup.

## Behavioural spec

### Value carriers

1. **`PartialPlan`** record — package-private under `internal/beamsearch/`:
   ```java
   record PartialPlan(
       LocalDate weekStartDate,
       List<SlotAssignment> assignments,    // one per slot filled so far, in week order
       BigDecimal currentScore              // running score (sum of per-slot weighted contributions; updated as the beam grows)
   ) {}

   record SlotAssignment(
       UUID dayId, UUID slotId, int slotIndex, LocalDate onDate, SlotKind kind,
       UUID recipeId, UUID recipeVersionId, UUID recipeBranchId,
       int servings,
       boolean pinned                       // true when the assignment came from PinningRules; the search does not overwrite
   ) {}
   ```
   `dayId` and `slotId` are pre-allocated by the composer (01j) — the search produces assignments against an existing slot skeleton. **Worth user review**: alternative is to defer ID generation to persist-time. Rejected because the search wants to reference the slot by its UUID in scoring (per-slot `time_budget_min` lookup is keyed by `slotId`).

2. **`CandidatePlan`** record — public under `domain.service.internal.beamsearch/` (or `api.dto/` per the stretch-zone style). 01d places it under `internal/beamsearch/` since it's a search output consumed only by other internal helpers (rollup, scoring, Stage C invoker), all module-private:
   ```java
   record CandidatePlan(
       UUID candidateId,                    // ephemeral; assigned per search run
       LocalDate weekStartDate,
       List<SlotAssignment> assignments,    // complete — one per slot in the week
       ScoreResult scoreResult              // populated by ScoringEngine before the candidate enters the top-N list
   ) {}
   ```

3. **`ScoreResult`** record from LLD §`ScoringEngine` lines 668. Lands here (not 01e) so `BeamSearchEngine` can declare its return type:
   ```java
   public record ScoreResult(BigDecimal composite, ScoreBreakdownDocument breakdown) {}
   ```

### `BeamSearchConfig`

4. Public record per LLD line 647:
   ```java
   public record BeamSearchConfig(int width, int topN, int maxPoolPerSlot) {}
   ```
   Defaults sourced from `PlannerProperties`: `width = 20`, `topN = 5`, `maxPoolPerSlot = 50`. Constructor checks: `width ≥ 1`, `topN ≥ 1`, `maxPoolPerSlot ≥ 1`, `width ≥ topN`.

### `PlanCompositionContext` (partial 01d shape)

5. Public record (cross-module-visible only to other planner internals; lives under `api.dto/` because the rollup ticket 01f and scoring ticket 01e both consume it):
   ```java
   public record PlanCompositionContext(
       UUID householdId,
       LocalDate weekStartDate,
       List<MealSlotSkeleton> slotSkeletons,                                     // see below — produced by the composer (01j)
       Map<UUID, HardConstraintsDto> hardConstraintsByUserId,
       Map<UUID, SoftPreferenceBundleDto> softPrefsByUserId,
       MergedSoftPreferencesDto mergedHouseholdPrefs,
       ProvisionForPlannerBundleDto provisions,
       HouseholdSettingsDto householdSettings,
       RecipePoolSnapshot recipePool,
       List<SlotAssignment> pinnedAssignments,                                   // empty for fresh generation; populated by 01i for re-opt
       UUID traceId,
       UUID decisionId
   ) {}
   ```

6. **`MealSlotSkeleton`** record — the pre-allocated slot shell the search fills in:
   ```java
   public record MealSlotSkeleton(
       UUID dayId, UUID slotId,
       int slotIndex, LocalDate onDate, SlotKind kind, String label,
       int timeBudgetMin, boolean shared, List<UUID> eaters
   ) {}
   ```
   The composer (01j) materialises this from the household's slot-configuration + week-start-date. 01d treats it as input.

7. **`RecipePoolSnapshot`** record:
   ```java
   public record RecipePoolSnapshot(List<RecipeDto> recipes, Instant generatedAt) {}
   ```
   Per LLD §Concurrency line 1327 — "loaded once and pinned." Concurrent recipe edits caught at slot rendering. The composer (01j) builds this; 01d treats it as a frozen pool.

### `HardFilterRunner`

8. Package-private class under `internal/beamsearch/HardFilterRunner.java`. Pure function:
   ```java
   class HardFilterRunner {
     Map<UUID /* slotId */, List<RecipeDto>> filterPool(PlanCompositionContext ctx);
   }
   ```
9. Algorithm per LLD §`BeamSearchEngine` lines 654-658:
   - For each `MealSlotSkeleton skeleton` in `ctx.slotSkeletons()`:
     - Pull the **base pool** = recipes in `ctx.recipePool().recipes()` whose tag `kind` matches the slot (breakfast/lunch/dinner — match `RecipeMetadataDto.mealTypes` or `RecipeTagsDto.mealKind` against `skeleton.kind()`). **Worth user review** — the LLD's pool-building text says "matching the slot kind"; whether this is `recipe.mealTypes.contains(kind)` or `recipe.kind == slot.kind` depends on the recipe DTO shape from recipe-01a. **01d uses `mealTypes.contains(...)`** since `RecipeMetadataDto.mealTypes` is `List<String>` and the recipe LLD allows recipes to be tagged for multiple slots.
   - Filter by **time budget**: drop recipes whose `metadata.totalTimeMins > skeleton.timeBudgetMin * maxTimeOvershootRatio` (from `PlannerProperties.maxTimeOvershootRatio`, default 1.5). This is the **hard time filter**; the soft-time gradient lives in 01e's `TimeSubScore`.
   - Filter by **hard constraints**: for each recipe, build the `ingredientMappingKey` list (from `recipe.versions[current].ingredients[*].ingredientMappingKey`) and pass to `HardConstraintFilterService.check(userId, ingredientKeys)` for per-person slots, OR `HardConstraintFilterService.checkForHousehold(householdId, ingredientKeys)` for shared slots (`skeleton.shared() == true`). Drop recipes whose check returns `BLOCKED`.
   - Filter by **equipment availability**: drop recipes whose `metadata.equipmentRequired` (List<String>) contains any item NOT in `ctx.provisions().equipment()` (mapped to a `Set<String>` of `EquipmentDto.canonicalKey` or `name`). **Verify the equipment-key mapping** when wiring — provisions-01b's `EquipmentDto` shape is the source.
   - Result: `Map<UUID slotId, List<RecipeDto>>`. **Cap each list at `ctx.maxPoolPerSlot`** (default 50) — sort by recipe id stable order (`UUID.compareTo`), take the first `maxPoolPerSlot`. **Worth user review** — alternative is to sort by a heuristic score (preference cosine sim, last-used recency). Rejected because the cap is just to bound search space, and the beam-search's scoring step selects the actual best.
10. **Empty pool**: if any slot's filtered pool is empty, `HardFilterRunner` does not throw — it returns the map with an empty list at that slot. The search engine handles the empty case (skip the slot, or fail-fast — see step 12 below). Empty-pool detection is the `ConstraintFeasibilityCheck`'s job (01j); 01d just reports.

### `BeamSearchEngine` interface + impl

11. **Public interface** under `domain.service.internal.beamsearch/`:
    ```java
    public interface BeamSearchEngine {
      List<CandidatePlan> search(PlanCompositionContext context, BeamSearchConfig config);
    }
    ```
    **`@Component`** impl at `BeamSearchEngineImpl.java`, package-private. Dependencies via constructor: `HardFilterRunner`, `BeamPruner`, `ScoringEngine` (stub from 01d; real impl from 01e supersedes).

12. **Algorithm** per LLD §`BeamSearchEngine` lines 652-660:
    - Step 1: `Map<UUID, List<RecipeDto>> pool = hardFilterRunner.filterPool(ctx)`.
    - Step 2: Initialise the beam — `List<PartialPlan> beam = List.of(emptyPartialPlan(ctx))`. The empty plan has no `assignments` (or only `pinnedAssignments` from re-opt scope; pinned-first ordering).
    - Step 3: For each `MealSlotSkeleton skeleton` in `ctx.slotSkeletons()` in week-order (sorted by `onDate ASC, slotIndex ASC`):
      - **If the slot is pinned** (present in `ctx.pinnedAssignments()`): every `PartialPlan p` in the beam has the pinned `SlotAssignment` appended (no expansion, no pruning). Score contribution preserved as recorded in the pinned `SlotAssignment` (carried over from the previous plan in re-opt; **for 01d's fresh-generation tests, `pinnedAssignments` is empty**).
      - **Otherwise**: `List<RecipeDto> slotPool = pool.get(skeleton.slotId())`. If `slotPool.isEmpty()`, leave every beam entry unchanged (slot stays unfilled — `ConstraintFeasibilityCheck` will surface this in 01j; 01d does not raise an exception).
      - **Expand**: for each `(beam-entry × recipe-in-pool)` Cartesian product, build a candidate `PartialPlan` with one more `SlotAssignment` appended.
      - **Score**: for each expanded candidate, call `scoringEngine.score(candidate-as-CandidatePlan, ctx)` — this is the **incremental scoring** assumption. **01d's stub scoring engine returns deterministic-by-recipe-id values** so the search behaviour is testable; the real engine (01e) computes the full composite. **Worth user review** — incremental vs full-plan scoring at each step is a design choice; the LLD's pseudo-code reads "score the expanded partial plan" which can mean either. **01d implements full-plan-score-on-partial** (the stub aggregates over `assignments`); this matches the LLD's prose intent.
      - **Prune**: `beam = beamPruner.retainTop(expanded, width)` keeps the top `width` by score.
    - Step 4: After every slot processed, convert each remaining `PartialPlan` to a complete `CandidatePlan` (renumber, attach the final `ScoreResult` from the last scoring call).
    - Step 5: Return the top `topN` by `composite` score.

13. **Timeout**: read `PlannerProperties.stageATimeout` (default 30s). The search wraps the slot loop in a `Stopwatch`; if elapsed > timeout:
    - **First-level fallback**: retry once with `width = width / 2`, restart from step 2.
    - **Second-level fallback**: degrade to greedy (`width = 1`).
    - Per LLD §Failure Modes line 1344: "on second timeout, degrade to greedy selection (width 1). Plan flagged with `qualityWarning = true` for the latter."
    - The `qualityWarning` flag is set by the **composer (01j)**, not the search itself. The search returns `CandidatePlan`s; the composer reads "did the search hit the second fallback?" via a `BeamSearchOutcome` return type. **01d ships a minimal carrier**:
      ```java
      record BeamSearchOutcome(List<CandidatePlan> candidates, boolean degradedToGreedy) {}
      ```
      And the `BeamSearchEngine` interface returns `BeamSearchOutcome`, not `List<CandidatePlan>`. **LLD divergence**: the LLD declared `List<CandidatePlan> search(...)`; 01d wraps in `BeamSearchOutcome` to surface degradation. **Worth user review** — alternative is a thread-local / MDC flag. Rejected for explicit-is-better.
14. **Determinism**: when scores tie, break by recipe-id stable order (`UUID.compareTo` on the candidate's last `SlotAssignment.recipeId`). Tests pin this so canonical fixtures produce repeatable outputs.

### `BeamPruner`

15. Package-private class:
    ```java
    class BeamPruner {
      List<PartialPlan> retainTop(List<PartialPlan> expanded, int width);
    }
    ```
16. Algorithm: sort `expanded` by `currentScore DESC`, ties broken by last `SlotAssignment.recipeId` ASC, take the first `width`. **Pure function, no DB**. Unit-tested against canonical inputs.

### `SubScoreCalculator` interface

17. **Interface declaration only** in 01d under `domain.service.internal.scoring/`:
    ```java
    interface SubScoreCalculator {
      String name();                                                              // matches PlannerProperties weight key
      BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx);          // returns [0, 1]
    }
    ```
    Package-private. **No implementations in 01d** — those land in 01e.

### `ScoringEngine` interface + stub

18. **Interface** (public, lives under `internal/scoring/` since the search consumes it and 01e implements it):
    ```java
    public interface ScoringEngine {
      ScoreResult score(CandidatePlan plan, PlanCompositionContext context);
    }
    ```
19. **Stub impl** under `internal/scoring/StubScoringEngine.java`. **Worth user review**: alternative naming `NoopScoringEngine`. 01d uses `Stub*` to flag "test-only, deterministic." Annotations:
    ```java
    @Component
    @Profile("test")          // active in test profile so 01e's real impl can ship alongside without conflict
    class StubScoringEngine implements ScoringEngine { ... }
    ```
    Returns `ScoreResult(BigDecimal.ONE.scaleByPowerOfTen(-9).multiply(BigDecimal.valueOf(deterministic-hash(plan))), defaultBreakdown)`. The deterministic hash is over the sorted list of recipe ids — same plan, same score; different plan, different score. Tests pin specific fixture outputs.
    **Crucial**: this stub MUST NOT be picked up in production. The `@Profile("test")` annotation gates it. 01e's real impl is `@Component` without a profile — it wins outside the test profile. **Worth user review** — alternative is `@ConditionalOnMissingBean(ScoringEngine.class)` on the stub; rejected because that risks the stub winning when 01e's bean is somehow not registered (compile-error scenario). `@Profile("test")` is explicit.
20. **Default `ScoreBreakdownDocument`** for the stub: all sub-scores `BigDecimal.ZERO`, composite from the hash, gates `true`, weight scheme `"v1-uniform"`.

### `PlannerProperties` — first config keys

21. **New** `@ConfigurationProperties(prefix = "mealprep.planner")` `@Validated` record at `planner/config/PlannerProperties.java`. **01d ships the first 7 fields**; later tickets append more.
    ```java
    @ConfigurationProperties(prefix = "mealprep.planner")
    @Validated
    public record PlannerProperties(
        @NotNull DayOfWeek weekStartDayOfWeek,        // default MONDAY
        @Min(1) int beamWidth,                        // default 20
        @Min(1) int topN,                             // default 5
        @Min(1) int minPoolPerSlot,                   // default 3
        @Min(1) int maxPoolPerSlot,                   // default 50
        @DecimalMin("1.0") @DecimalMax("3.0")
        BigDecimal maxTimeOvershootRatio,             // default 1.5
        @NotNull Duration stageATimeout               // default PT30S
    ) {}
    ```
22. Defaults applied via `@ConstructorBinding` (Spring Boot 3.x: every `@ConfigurationProperties` record auto-uses `@ConstructorBinding`).
23. `application.yml` defaults appended in `src/main/resources/application.yml`:
    ```yaml
    mealprep:
      planner:
        week-start-day-of-week: MONDAY
        beam-width: 20
        top-n: 5
        min-pool-per-slot: 3
        max-pool-per-slot: 50
        max-time-overshoot-ratio: 1.5
        stage-a-timeout: PT30S
    ```
24. **Register** via `@EnableConfigurationProperties(PlannerProperties.class)` on `PlannerModule.java` (the facade from 01a).
25. **Per-test override**: ITs can `@TestPropertySource("mealprep.planner.beam-width=5")` to shrink fixture sizes.

## Database

**Zero migrations.** Pure code. The bundle DTOs (`HardConstraintsDto`, `SoftPreferenceBundleDto`, `ProvisionForPlannerBundleDto`, `HouseholdSettingsDto`, `RecipeDto`, `RecipeMetadataDto`) are all from wave-2 modules; verify they're on the classpath. They are: `preference-01a`, `nutrition-01a-h`, `provisions-01a-h`, `recipe-01a-h`, `household-01a-f` all merged.

## OpenAPI updates

**No new endpoints.** 01d ships pure logic + a config block. The `CandidatePlan`, `PartialPlan`, `BeamSearchOutcome`, `PlanCompositionContext` records are internal carriers; not exposed via HTTP. No edits to any OpenAPI YAML.

## Verbatim shape snippets

### `BeamSearchEngineImpl` skeleton

```java
@Component
@RequiredArgsConstructor
@Slf4j
class BeamSearchEngineImpl implements BeamSearchEngine {

  private final HardFilterRunner hardFilterRunner;
  private final BeamPruner beamPruner;
  private final ScoringEngine scoringEngine;
  private final PlannerProperties properties;

  @Override
  public BeamSearchOutcome search(PlanCompositionContext ctx, BeamSearchConfig config) {
    Instant start = Instant.now();

    Map<UUID, List<RecipeDto>> pool = hardFilterRunner.filterPool(ctx);

    List<PartialPlan> beam = List.of(initialBeamEntry(ctx));
    List<MealSlotSkeleton> orderedSlots = ctx.slotSkeletons().stream()
        .sorted(Comparator.comparing(MealSlotSkeleton::onDate)
                          .thenComparingInt(MealSlotSkeleton::slotIndex))
        .toList();

    for (MealSlotSkeleton skel : orderedSlots) {
      if (Duration.between(start, Instant.now()).compareTo(properties.stageATimeout()) > 0) {
        log.warn("Beam search timeout exceeded at slot {}; degrading", skel.slotId());
        return greedyFallback(ctx, config, pool, orderedSlots);
      }
      beam = expandAndPrune(beam, skel, pool, ctx, config);
    }

    List<CandidatePlan> topN = finalise(beam, ctx, config);
    return new BeamSearchOutcome(topN, false);
  }

  private BeamSearchOutcome greedyFallback(/* ... */) {
    // run again with width=1
  }

  private List<PartialPlan> expandAndPrune(List<PartialPlan> beam, MealSlotSkeleton skel,
                                            Map<UUID, List<RecipeDto>> pool,
                                            PlanCompositionContext ctx,
                                            BeamSearchConfig config) {
    Optional<SlotAssignment> pinned = ctx.pinnedAssignments().stream()
        .filter(a -> a.slotId().equals(skel.slotId()))
        .findFirst();
    if (pinned.isPresent()) {
      return beam.stream()
          .map(p -> p.append(pinned.get()))
          .toList();
    }
    List<RecipeDto> slotPool = pool.getOrDefault(skel.slotId(), List.of());
    if (slotPool.isEmpty()) {
      return beam;                           // unfilled; composer surfaces as qualityWarning
    }
    List<PartialPlan> expanded = new ArrayList<>(beam.size() * slotPool.size());
    for (PartialPlan p : beam) {
      for (RecipeDto r : slotPool) {
        PartialPlan candidate = p.append(toAssignment(skel, r, ctx.householdSettings()));
        ScoreResult sr = scoringEngine.score(candidate.toCandidatePlanView(), ctx);
        expanded.add(candidate.withScore(sr.composite()));
      }
    }
    return beamPruner.retainTop(expanded, config.width());
  }
}
```

### `BeamPruner`

```java
@Component
class BeamPruner {

  List<PartialPlan> retainTop(List<PartialPlan> expanded, int width) {
    return expanded.stream()
        .sorted(Comparator
            .comparing(PartialPlan::currentScore, Comparator.reverseOrder())
            .thenComparing(p -> p.assignments().get(p.assignments().size() - 1).recipeId()))
        .limit(width)
        .toList();
  }
}
```

### `HardFilterRunner` — skeleton

```java
@Component
@RequiredArgsConstructor
class HardFilterRunner {

  private final HardConstraintFilterService hardConstraintFilterService;
  private final PlannerProperties properties;

  Map<UUID, List<RecipeDto>> filterPool(PlanCompositionContext ctx) {
    Set<String> availableEquipment = ctx.provisions().equipment().stream()
        .map(EquipmentDto::canonicalKey)         // verify field name in EquipmentDto
        .collect(Collectors.toSet());

    Map<UUID, List<RecipeDto>> result = new LinkedHashMap<>();
    for (MealSlotSkeleton skel : ctx.slotSkeletons()) {
      List<RecipeDto> filtered = ctx.recipePool().recipes().stream()
          .filter(r -> matchesKind(r, skel.kind()))
          .filter(r -> withinTimeBudget(r, skel.timeBudgetMin()))
          .filter(r -> hasRequiredEquipment(r, availableEquipment))
          .filter(r -> passesHardConstraints(r, skel, ctx))
          .sorted(Comparator.comparing(RecipeDto::id))
          .limit(properties.maxPoolPerSlot())
          .toList();
      result.put(skel.slotId(), filtered);
    }
    return result;
  }

  private boolean withinTimeBudget(RecipeDto r, int budget) {
    return r.metadata().totalTimeMins()
        <= Math.round(budget * properties.maxTimeOvershootRatio().doubleValue());
  }

  // matchesKind, hasRequiredEquipment, passesHardConstraints — straightforward filters
}
```

## Edge-case checklist

### `BeamSearchEngine`

- [ ] Empty `slotSkeletons` → returns `BeamSearchOutcome(List.of(empty candidate), false)` — vacuous truth
- [ ] Single-slot, 3-recipe pool, width=2, topN=1 → top 1 returned by stub-deterministic score
- [ ] 21-slot week (7 days × 3 slots), width=5, topN=3 — beam size never exceeds 5 at any iteration; final result is 3 candidates
- [ ] Slot with empty pool → that slot left unfilled in every beam entry; composer's job to surface qualityWarning (01j); 01d does not throw
- [ ] All pinned slots → search produces exactly one candidate (no expansion possible); score = sum of pinned scores
- [ ] Mixed pinned + regenerable → only regenerable slots are expanded; pinned ones append verbatim
- [ ] Beam size never exceeds `config.width()` after any iteration (loop invariant)
- [ ] Final result is `min(topN, beam.size())` (handles small-pool cases gracefully)
- [ ] Tie-break by recipe-id stable order — verified by fixture with two recipes scoring identically; same fixture always returns same order
- [ ] Timeout exceeded → `greedyFallback` triggered; `BeamSearchOutcome.degradedToGreedy() == true`

### `HardFilterRunner`

- [ ] Recipe whose `mealTypes` doesn't include slot kind → filtered out
- [ ] Recipe with `totalTimeMins = 90` for a slot with `timeBudgetMin = 30, maxTimeOvershootRatio = 1.5` → filtered (90 > 45)
- [ ] Recipe with `totalTimeMins = 45` for same slot → kept
- [ ] Recipe requiring "stand-mixer" when provisions equipment is `["pan", "knife"]` → filtered
- [ ] Recipe with empty `equipmentRequired` → kept (no equipment needed = always available)
- [ ] Shared slot: passes recipe through `checkForHousehold(...)`
- [ ] Per-person slot: passes through `check(userId, ...)` for each eater; **01d divergence** — for a per-person slot with multiple eaters, we apply `check` per eater and the recipe passes only if every eater's check passes. **Worth user review** — alternative is union of allergens. The LLD says "per-eater filter for shared slots"; 01d interprets "shared" as the entry point and "per-eater" as the iteration. Verify against the preference-01b `HardConstraintFilterService` contract.
- [ ] Pool exceeds `maxPoolPerSlot` → capped at `maxPoolPerSlot`, sorted by `RecipeDto.id`
- [ ] Slot with no kind-matching recipes → empty list at that key (not missing key — `LinkedHashMap` preserves insertion order)

### `BeamPruner`

- [ ] `retainTop(emptyList, 5)` → empty
- [ ] `retainTop(list-of-3, 5)` → all 3
- [ ] `retainTop(list-of-10, 3)` → top 3 by score; deterministic on ties
- [ ] Sort is stable across runs (no `Collections.shuffle` accidentally)

### `PlannerProperties`

- [ ] Defaults apply when no override in `application.yml` — verified by IT
- [ ] `@Min(1)` violated → context fails to load with validation error
- [ ] `maxTimeOvershootRatio > 3.0` → validation fails
- [ ] `@TestPropertySource("mealprep.planner.beam-width=5")` overrides successfully

### Stub `ScoringEngine`

- [ ] `@Profile("test")` only — does NOT register in default / production profile
- [ ] Deterministic: same `CandidatePlan` input → byte-identical `ScoreResult` output
- [ ] Different recipes → different scores (no hash collision in test fixtures)

### Cross-cutting

- [ ] `BeamSearchEngine`, `HardFilterRunner`, `BeamPruner`, `StubScoringEngine` are all package-private (`@Component` without `public` modifier on class) — boundary test passes
- [ ] No HTTP / REST surface added — no OpenAPI YAML touched
- [ ] No N+1 — `HardFilterRunner` issues N×M `HardConstraintFilterService.check` calls (one per recipe × eater). Mitigation: cache per-recipe `BLOCKED` decisions inside one call. **Worth user review** — alternative is a batch `checkBulk` call on the filter service. Defer the optimisation until profiling shows hotspot.
- [ ] No regression on 01a/01b/01c tests
- [ ] `PlannerBoundaryTest` still passes
- [ ] No `pom.xml` dep adds
- [ ] No other modules' files touched

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/planner/api/dto/PlanCompositionContext.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/MealSlotSkeleton.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/RecipePoolSnapshot.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/CandidatePlan.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/SlotAssignment.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/ScoreResult.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/BeamSearchOutcome.java

NEW   src/main/java/com/example/mealprep/planner/config/PlannerProperties.java

NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/beamsearch/BeamSearchConfig.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/beamsearch/BeamSearchEngine.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/beamsearch/BeamSearchEngineImpl.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/beamsearch/HardFilterRunner.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/beamsearch/BeamPruner.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/beamsearch/PartialPlan.java

NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/ScoringEngine.java        (interface)
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/SubScoreCalculator.java   (interface)
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/scoring/StubScoringEngine.java    (@Profile("test"))

MOD   src/main/java/com/example/mealprep/planner/PlannerModule.java                                        (add @EnableConfigurationProperties(PlannerProperties.class))
MOD   src/main/resources/application.yml                                                                   (add mealprep.planner.* defaults)

NEW   src/test/java/com/example/mealprep/planner/BeamSearchEngineTest.java
NEW   src/test/java/com/example/mealprep/planner/HardFilterRunnerTest.java
NEW   src/test/java/com/example/mealprep/planner/BeamPrunerTest.java
NEW   src/test/java/com/example/mealprep/planner/StubScoringEngineTest.java
NEW   src/test/java/com/example/mealprep/planner/PlannerPropertiesIT.java
MOD   src/test/java/com/example/mealprep/planner/testdata/PlanTestData.java                                (add: canonicalRecipePool, twoSlotContext, threeRecipePool builders)
```

Count: ~25 files. All pure-logic + records + config. Estimated agent runtime 45-55 min.

**Files this ticket does NOT modify**:
- `PlansController` / `PlannerExceptionHandler` / `PlanQueryService` / `PlannerServiceImpl` — 01d adds no controller surface and doesn't wire the search into the public service yet (01j does).
- OpenAPI YAMLs — no endpoints.
- Other modules — none touched.

## Gotchas to bake in

1. **Cross-module DTO imports**: `RecipeDto`, `HardConstraintsDto`, `EquipmentDto`, `SoftPreferenceBundleDto`, `ProvisionForPlannerBundleDto`, `HouseholdSettingsDto`, `MergedSoftPreferencesDto` come from wave-2 modules' `api.dto` packages. Use them via direct import — they're public per the style guide. **Do NOT import from `domain.entity`** — that's a module-boundary violation that ArchUnit catches.
2. **`HardConstraintFilterService`** lives in `preference.domain.service`. Inject by interface only. **Do NOT import the impl class.**
3. **`@Profile("test")` on the stub**: the test profile is activated by `@SpringBootTest` automatically (Spring Boot's default test slice). **Verify** in the IT that the stub IS active by `@Autowired ScoringEngine` and `assertThat(engine).isInstanceOf(StubScoringEngine.class)`. When 01e ships, both `@Component` beans coexist — Spring picks the non-`@Profile("test")` one in test contexts that don't activate `test` profile. **Worth user review** — alternative is to add `@Primary` to 01e's real impl. Confirm in 01e's ticket.
4. **`PlannerProperties` record fields**: `Duration` parses ISO-8601 (`PT30S`). Don't use `30S` or `30s` in YAML.
5. **`@Validated` on the record**: validation runs at context load. A bad property value crashes startup with a clear message — that's the intent.
6. **`weekStartDayOfWeek` as `DayOfWeek` enum**: Spring's relaxed binding accepts `MONDAY`, `monday`, `Monday`. Default to `MONDAY` (the project locks Monday-start weeks per the LLD).
7. **`@Component` package-private**: `HardFilterRunner`, `BeamPruner`, `BeamSearchEngineImpl`, `StubScoringEngine` are all package-private. Spring's component-scan picks them up regardless of visibility. The class-level access modifier (no `public`) keeps them out of the cross-module surface.
8. **`MultipleBagFetchException` does NOT apply here** — 01d touches no JPA entities directly. The `PlanCompositionContext` carries already-loaded DTOs.
9. **`@RequiredArgsConstructor`**: use for constructor injection. Don't use `@Autowired` on fields.
10. **Determinism in tie-breaks**: every `sorted(...)` chain must end in a stable secondary comparator (recipe-id), otherwise tests flake. The `Comparator.comparing(...).thenComparing(...)` pattern is mandatory.
11. **`Stream.toList()`** returns unmodifiable. If downstream code tries to mutate the beam in place, that NPEs. Either pass through `new ArrayList<>(...)` at expansion boundaries or stick to immutable patterns. The skeleton above is immutable-friendly.
12. **`@TestPropertySource` overrides only apply to that IT class**; if the override should apply to a unit test, use `@ContextConfiguration` or pass `PlannerProperties` to the constructor manually.

## Dependencies

- **Hard dependency**: `planner-01a` (merged) — `Plan`, `MealSlot`, `ScheduledRecipe`, `SlotKind`, `PlanCompositionContext`-adjacent DTOs (`PlanDto` etc. — not used here, but the module needs to compile cleanly).
- **Hard dependency**: `planner-01b` (merged) — `PlanStatus`, `PlanGenerationCounter` (not used here but referenced in the same module).
- **Hard dependency**: `preference-01b` (merged) — **`HardConstraintFilterService`** (`check(userId, ingredientKeys)`, `checkForHousehold(householdId, ingredientKeys)`).
- **Hard dependency**: `provisions-01f` (merged) — `ProvisionForPlannerBundleDto`, `EquipmentDto`.
- **Hard dependency**: `recipe-01a` (merged) — `RecipeDto`, `RecipeMetadataDto`, `RecipeTagsDto`.
- **Hard dependency**: `household-01f` (merged) — `HouseholdSettingsDto`, `MergedSoftPreferencesDto`.
- **Sibling tickets running in parallel** (Wave 3 round 2): `planner-01c` (no overlap), `planner-01e` (extends the scoring tree — 01e adds `@Component` impls under `internal/scoring/` and may shadow the stub; coordinate via `@Profile`).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `StubScoringEngine` annotated `@Profile("test")` — verified by IT context inspection
- [ ] `PlannerProperties` registered via `@EnableConfigurationProperties` on `PlannerModule.java`
- [ ] Determinism — same fixture, same test, byte-identical search output
- [ ] Beam size loop invariant verified — beam never exceeds `config.width()` after any iteration
- [ ] No `pom.xml` dep adds
- [ ] No other modules' files touched
- [ ] `PlannerBoundaryTest` still passes — `internal/beamsearch/`, `internal/scoring/`, `api/dto/` all in scope per the existing rule

Squash-merge with: `feat(planner): 01d — BeamSearchEngine + HardFilterRunner + BeamPruner + StubScoringEngine + first PlannerProperties keys (Stage A composition)`

## What's NOT in scope

- Real `ScoringEngine` impl + 7 sub-score calculators + gates → **planner-01e**
- `RollupBuilder` (Stage B) → **planner-01f**
- Stage C / Phase 2 LLM calls → **planner-01g, 01h**
- `PlanComposer` end-to-end orchestrator → **planner-01j**
- `ConstraintFeasibilityCheck` → **planner-01j**
- Mid-week re-opt + `PinningRules` (the pinned-assignments source) → **planner-01i**
- Cost / latency observability metrics on the search → out of scope v1
- Async beam-search variant → v1 is synchronous; concurrency comes via the per-`(household, week)` lock at the composer layer
