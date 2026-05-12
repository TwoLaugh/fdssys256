# Ticket: nutrition — 01h `IntakeAggregator` + Weekly Rollup Endpoint + `DivergenceDetector` + `NutritionIntakeDivergedEvent`

## Summary

Layer the **periodic-aggregation + divergence detection** layer on top of 01a..01g. Per [LLD §`IntakeAggregator` line 47](../../lld/nutrition.md), [LLD §`DivergenceDetector` line 48](../../lld/nutrition.md), [LLD §`WeeklyAggregateDto` line 459-460](../../lld/nutrition.md), [LLD §`getWeeklyAggregate` line 677](../../lld/nutrition.md), [LLD §REST `/week/{weekStart}/aggregate` line 803](../../lld/nutrition.md), [LLD §Divergence threshold and detection lines 913-924](../../lld/nutrition.md), [LLD §Events §`NutritionIntakeDivergedEvent` lines 893-895](../../lld/nutrition.md), [LLD §test plan lines 1071 / 1073 / 1091](../../lld/nutrition.md). Ships:

- **`IntakeAggregator`** — package-private `@Component` in `nutrition/domain/service/internal/`. Reads from the existing `intake_day` / `intake_slot` / `intake_snack` tables (shipped in 01b). Pure compute. Daily aggregate already lives on `NutritionServiceImpl.getDailyAggregate` from 01b; 01h adds the **weekly rollup** (Mon→Sun by default) by walking 7 days of intake and summing into a `WeeklyAggregateDto`.
- **`GET /api/v1/nutrition/intake/week/{weekStart}/aggregate`** — REST endpoint per LLD line 803. `weekStart` must be a Monday (validation error if not). Returns `WeeklyAggregateDto(weekStart, weekEnd, perDay[7], weeklyTotal, floorViolations)`.
- **`DivergenceDetector`** — package-private `@Component` in `nutrition/domain/service/internal/`. Runs after every intake-actual update (CONFIRM, OVERRIDE, EDIT, SKIP — NOT pure snack ops per LLD line 917). Computes `variance = (actualSoFar - plannedSoFar) / plannedSoFar` per macro. When `|variance| >= threshold` for any macro AND at least one slot is still `PENDING`, publishes `NutritionIntakeDivergedEvent`. Duplicate-suppression: publish only if the diverged-macros set differs from the last publication for the same `(userId, onDate)`, or a previously-diverged macro newly resolves.
- **`NutritionIntakeDivergedEvent`** event record per LLD line 893-895. Already declared in LLD; not yet implemented. Lives in `nutrition/event/`.
- **`DivergenceSummaryDto`** payload type per LLD line 897-899.
- **Wiring**: the four existing 01b/01c flows (`confirmFromPlan`, `overrideIntakeFromFreeText`, `editIntakeManually`, `skipMeal`) call `divergenceDetector.detectAndPublish(userId, onDate)` at the end of each `@Transactional` method, BEFORE the `AFTER_COMMIT` event publication (so the divergence event also fires after commit via `ApplicationEventPublisher`).
- **Per-detector dedup state**: a tiny new table `nutrition_divergence_state` keyed `(user_id, on_date)` storing the last-published `divergedMacros` set as JSONB, so dedup survives a JVM restart. **LLD divergence below**.

01h is the **final Wave-2 nutrition ticket**. After 01h, nutrition is feature-complete for Wave 2.

## LLD divergence — dedup state persisted (not in-memory)

LLD line 917 specifies duplicate suppression based on "the set of diverged macros differs from the previous publication for the same day". The LLD does not say **where** that previous-state lives. **01h persists it** in a new table `nutrition_divergence_state` rather than an in-memory cache because:

- The planner module (future) is the consumer; a JVM restart should not re-fire spurious "everything just diverged" events.
- Multi-instance deployment (planned per technical-architecture) needs shared state.
- The table is tiny (one row per user per active intake day; rows older than 30 days get swept).

**Worth user review** — alternative is a Caffeine cache with the documented "restart-loses-dedup" caveat. Rejected because the noise cost is real (every restart would re-fire divergence on every active user). The table costs <5 KB per user and one row write per intake update.

## LLD divergence — weekStart must be a Monday

LLD line 459 declares `WeeklyAggregateDto(weekStart, weekEnd, perDay[7], ...)` but does not pin the week-start day. **01h pins Monday** (ISO-8601 standard). `weekEnd = weekStart.plusDays(6)`. Rejects non-Monday `weekStart` with 400 `InvalidWeekStartException` (NEW). **Worth user review** — alternative is to accept any date and snap to the containing Monday; rejected because silent date manipulation is a foot-gun and the UI knows the convention.

## LLD divergence — `DailyAggregateDto[]` in `WeeklyAggregateDto` becomes `List<DailyAggregateDto>`

LLD line 459 uses Java array syntax (`DailyAggregateDto[] perDay`). **01h uses `List<DailyAggregateDto>`** for Jackson/serialisation idiomaticity. The field still carries exactly 7 entries (one per day, Mon→Sun). **Worth user review** — array vs list is cosmetic; list is the project-wide pattern for collections in DTOs.

## LLD divergence — `floorViolations` is a list of macro keys, not full FloorViolationDtos

LLD line 460 declares `List<String> floorViolations`. **01h ships verbatim** — the field is a flat list of macro/micro keys that breached hard floors during the week. Each element is a key like `"protein"` or `"iron_mg"` (lowercase, snake_case). For the rich per-day per-macro breakdown the caller uses the per-day rollup + `NutritionFloorGateService.evaluate` (from 01g). 01h does NOT add violation detail to `WeeklyAggregateDto` — that would duplicate 01g's responsibility. **Worth user review** — alternative is to inline `FloorViolationDto` here; rejected to keep the weekly aggregate focused on totals.

## Defers (still out of scope after 01h)

- Mid-week re-opt offer queue — planner module's concern. 01h publishes `NutritionIntakeDivergedEvent`; the planner subscribes.
- `getRecentIntakeTotals` (LLD line 679) — already shipped in 01b's `NutritionQueryService`. Verify; if missing, add as a follow-up.
- Monthly / quarterly rollups — daily and weekly only for Wave 2.
- Per-meal-slot aggregation in the weekly view — the per-day rollup already breaks down per slot via `IntakeDayDto`.
- Anything `@Scheduled` — the detector runs **on intake update**, not on a clock. The aggregator runs **on read**, not pre-computed. Both are pure-compute over the existing tables; no batch job needed at v1 scale.

## Behavioural spec

### `IntakeAggregator`

1. New package-private `@Component` `com.example.mealprep.nutrition.domain.service.internal.IntakeAggregator`.
2. **API**:
   ```java
   DailyAggregateDto aggregateDay(UUID userId, LocalDate onDate);
   WeeklyAggregateDto aggregateWeek(UUID userId, LocalDate weekStart);
   ```
3. **`aggregateDay`** — pure-compute helper backing the existing `getDailyAggregate` from 01b. **01h MAY extract the existing per-day arithmetic from `NutritionServiceImpl` into this component** for reuse by `aggregateWeek`. **Worth user review** — alternative is to keep the per-day arithmetic inline on the impl and reimplement summation in the aggregator. **Default: extract** (DRY). Behaviour is unchanged on the public method; the IT verifies byte-identical output before/after.
4. **`aggregateWeek`** — loads `intakeDayRepository.findAllByUserIdAndOnDateBetween(userId, weekStart, weekStart.plusDays(6))` (sibling of the existing per-day fetch from 01b; **add the method if not present** — verify).
   - For each of the 7 days, build the `DailyAggregateDto` (call `aggregateDay`).
   - Compute `weeklyTotal` by summing across the 7 `DailyAggregateDto`s: `caloriesPlanned`, `caloriesActualSoFar`, `caloriesRemaining` are integer sums; macro `plannedG`/`actualSoFarG`/`remainingG` are `BigDecimal` sums (scale 2, `RoundingMode.HALF_UP`); `microsActualSoFar` merges via `Map<String, BigDecimal>` summation across keys.
   - **`floorViolations`**: load `NutritionTargets` via `nutritionTargetsRepository.findWithChildrenByUserId(userId)`. For each hard-floor macro/micro (per the `isHardFloor` flag from 01a), check the **weekly total** against `floorG × 7` (macro) or `targetValue × 7` (micro). Any key whose weekly actual < weekly floor lands in the list. **Per-day check is the planner's concern via 01g — 01h's weekly view is the weekly-total check only.**
   - **No targets configured**: `floorViolations = List.of()` (matches 01g's no-targets-passes-by-default policy).
5. **Missing day**: when `findAllByUserIdAndOnDateBetween` returns fewer than 7 rows (user logged on 3 of 7 days), the missing days contribute a zero-valued `DailyAggregateDto` (`caloriesPlanned=0, caloriesActualSoFar=0, ...`, macros zero). The weekly total then reflects only the days the user actually used. **No exception, no warning** — the missing-day case is normal for a partially-tracked week.
6. **Ordering**: `perDay` is sorted by date ascending (Mon → Sun).

### `GET /api/v1/nutrition/intake/week/{weekStart}/aggregate`

7. **Endpoint**: appended to existing `IntakeController` from 01b. Authenticated; `userId` resolved server-side from auth context via `CurrentUserResolver.requireUserId()`.
8. **Path variable**: `weekStart` → `LocalDate`. Format `yyyy-MM-dd`.
9. **Validation rule** (service-layer): `weekStart.getDayOfWeek() == DayOfWeek.MONDAY` else throws `InvalidWeekStartException` (NEW; 400). Message: `"weekStart must be a Monday (ISO-8601 day-of-week 1); got <day>"`.
10. **Service call**: `nutritionQueryService.getWeeklyAggregate(userId, weekStart)` → `WeeklyAggregateDto` — implemented by `NutritionServiceImpl` delegating to `IntakeAggregator.aggregateWeek`.
11. **`@Transactional(readOnly = true)`** on the service-impl method.
12. **Response**: 200 + `WeeklyAggregateDto` JSON.
13. **Anonymous**: 401 (existing `SessionAuthenticationFilter`).
14. **No 404** — empty intake = aggregate of zeros; the user always has a "current week" view.

### `DivergenceDetector`

15. New package-private `@Component` `com.example.mealprep.nutrition.domain.service.internal.DivergenceDetector`.
16. **API**:
    ```java
    void detectAndPublish(UUID userId, LocalDate onDate, UUID traceId);
    ```
17. **Called BY**: the four 01b/01c flows after they mutate intake state — `confirmFromPlan`, `overrideIntakeFromFreeText`, `editIntakeManually`, `skipMeal`. **NOT** called by `logSnack` / `removeSnack` per LLD line 917 ("not pure snack ops; snacks have no planned counterpart"). **Wiring**: each existing flow's last in-tx statement (before the existing `eventPublisher.publishEvent(new IntakeLoggedEvent(...))`) becomes `divergenceDetector.detectAndPublish(userId, onDate, traceId)`. The detector is invoked **inside the existing `@Transactional` method**; the publication of `NutritionIntakeDivergedEvent` is registered for AFTER_COMMIT.
18. **Algorithm**:
    - **Load the day**: `intakeDayRepository.findWithDetailsByUserIdAndOnDate(userId, onDate)` (sibling of 01b's hot read; verify naming). Returns `Optional<IntakeDay>`. Empty → silently return (no day = nothing to diverge).
    - **Load targets**: `nutritionTargetsRepository.findWithChildrenByUserId(userId)`. Empty → silently return (no targets = no divergence baseline).
    - **Skip-condition 1**: `plannedKcal < minimumPlannedFloorKcal` (config: `mealprep.nutrition.divergence.minimum-planned-floor-kcal: 200`) → silently return. Avoids noise on tiny plans per LLD line 923.
    - **Skip-condition 2**: NO slot in the day is still `PENDING` → no future re-opt possible → still emit a resolution event if previously diverged, but do NOT emit a new divergence (LLD line 917: "at least one slot is still PENDING").
    - **Compute** for each macro `m` in `{calories, protein, carbs, fat, fibre}`:
      - `plannedSoFar[m] = sum(slot.planned[m] for slot in decided slots ∪ all skipped slots) + sum(snack[m])` — snacks count toward actuals, planned counterpart for snacks is zero.
      - Actually LLD line 917 is more precise: "computes plannedSoFar and actualSoFar per macro across all decided slots (snacks count toward actuals)". 01h reads "decided" as `actualStatus in {CONFIRMED, OVERRIDDEN, EDITED, SKIPPED}` — i.e. anything that's NOT `PENDING`. **Worth user review** — alternative is "all slots regardless of status". 01h follows LLD literally.
      - `actualSoFar[m] = sum(slot.actual[m] for decided slots) + sum(snack[m])`.
      - `variance[m] = (actualSoFar[m] - plannedSoFar[m]) / plannedSoFar[m]` when `plannedSoFar[m] > 0`; undefined/skipped when `plannedSoFar[m] == 0`.
    - **Threshold compare** (config: `mealprep.nutrition.divergence.macro-variance-threshold: 0.15`):
      - For each macro `m` with defined variance: `|variance[m]| >= threshold` → add `m` to `divergedMacros` set.
19. **Dedup state**:
    - Load `nutrition_divergence_state` row for `(userId, onDate)` via `divergenceStateRepository.findByUserIdAndOnDate(userId, onDate)`.
    - **Previous diverged set** = `row.divergedMacros` (empty if no row).
    - **Compare** with the newly-computed `divergedMacros`:
      - **Same set + non-empty** → no-op (already-published divergence still holds; planner already has the offer queued).
      - **Different non-empty set** → publish a fresh `NutritionIntakeDivergedEvent` with the new set; upsert the row's `divergedMacros`.
      - **Newly empty (previous was non-empty)** → publish a resolution event with `divergedMacros = Set.of()` per LLD line 917 ("a previously-diverged macro newly resolves"); upsert row to empty set.
      - **Both empty** → no-op.
    - Upsert the dedup row with `updatedAt = now` regardless of publish outcome (so the sweep job can age out idle rows).
20. **Publish via `ApplicationEventPublisher`** inside the existing parent `@Transactional` method; Spring delivers `AFTER_COMMIT` to any `@TransactionalEventListener(phase = AFTER_COMMIT)` consumer (the future planner module).

### `NutritionIntakeDivergedEvent` (event record)

21. New record `com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent` per LLD line 893-895:
    ```java
    public record NutritionIntakeDivergedEvent(UUID userId, LocalDate onDate,
        Set<String> divergedMacros, DivergenceSummaryDto summary,
        UUID traceId, Instant occurredAt) implements ScopeChangedEvent {
      @Override public String scopeKind() { return "nutrition-intake"; }
      @Override public UUID scopeId() { return userId; }   // closest scope id for nutrition-intake
    }
    ```
22. New record `DivergenceSummaryDto` per LLD line 897-899 in `nutrition/api/dto/`:
    ```java
    public record DivergenceSummaryDto(Map<String, BigDecimal> plannedSoFar,
                                       Map<String, BigDecimal> actualSoFar,
                                       Map<String, BigDecimal> percentVariance) {}
    ```
23. **Resolution event** uses the same record; `divergedMacros = Set.of()` is the resolution signal. **Worth user review** — alternative is a separate `NutritionIntakeDivergenceResolvedEvent`; rejected because the same `Set<String>` carries the signal and the planner's `switch` is simpler with one record.

### Errors

24. New exception `InvalidWeekStartException extends NutritionException` (400 — `type = .../invalid-week-start`).
25. **Append one new `@ExceptionHandler` method** to the existing `NutritionExceptionHandler` (already `@Order(Ordered.HIGHEST_PRECEDENCE)` from 01a..01g). Do NOT modify `config/GlobalExceptionHandler.java`. Do NOT create a second handler class.

### Cross-module facade

26. **No** changes to `NutritionModule.java`. The new `getWeeklyAggregate` method already lives on `NutritionQueryService` (LLD line 677) — verify; if missing from the interface, append the signature. `IntakeAggregator` and `DivergenceDetector` are package-private and NOT re-exported.

## Database

```
src/main/resources/db/migration/V20260601601300__nutrition_create_divergence_state.sql   new
```

Schema:

```sql
-- Nutrition module — 01h divergence-detector dedup state.
-- See lld/nutrition.md §Events §Divergence threshold and detection lines 913-924.
-- One row per (user_id, on_date) tracking the last-published diverged-macros set.
-- Dedup survives JVM restart and multi-instance deployment.

CREATE TABLE nutrition_divergence_state (
    user_id          uuid          NOT NULL,
    on_date          date          NOT NULL,
    diverged_macros  jsonb         NOT NULL DEFAULT '[]'::jsonb,   -- JSON array of macro keys
    updated_at       timestamptz   NOT NULL,
    PRIMARY KEY (user_id, on_date)
);

-- Sweep query: DELETE WHERE updated_at < now() - INTERVAL '30 days'.
CREATE INDEX idx_nutrition_divergence_state_updated_at
    ON nutrition_divergence_state (updated_at);
```

**Retention**: rows older than 30 days are eligible for sweep. 01h does NOT ship the `@Scheduled` sweep — the table grows at one row per user per active day, ~365 rows/user/year, ~5 KB/user/year. The sweep is a follow-up if storage becomes a concern; documented in the table comment as a NOTE.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/nutrition.yaml`

(File extended by 01a..01g — append below 01g's most recent block. Do NOT touch existing path-items.)

```yaml
nutritionIntakeWeekAggregate:
  get:
    tags: [Nutrition]
    operationId: getWeeklyIntakeAggregate
    summary: 'Weekly intake rollup (Monday-anchored) for the calling user.'
    description: 'Returns per-day breakdown plus a weekly total. Floor violations list keys whose weekly total fell below 7-day floor.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: weekStart
        required: true
        description: 'ISO-8601 date; must be a Monday. Non-Monday inputs yield 400 invalid-week-start.'
        schema: { type: string, format: date }
    responses:
      '200':
        description: 'Weekly aggregate; empty intake days contribute zero-valued DailyAggregateDto entries.'
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/WeeklyAggregateDto' }
      '400':
        description: 'weekStart is not a Monday'
        content:
          application/problem+json:
            schema: { $ref: '../schemas/common.yaml#/ProblemDetail' }
      '401':
        description: 'Unauthenticated'
        content:
          application/problem+json:
            schema: { $ref: '../schemas/common.yaml#/ProblemDetail' }
```

### Append to `src/main/resources/openapi/schemas/nutrition.yaml`

```yaml
WeeklyAggregateDto:
  type: object
  required: [weekStart, weekEnd, perDay, weeklyTotal, floorViolations]
  properties:
    weekStart: { type: string, format: date }
    weekEnd:   { type: string, format: date }
    perDay:
      type: array
      minItems: 7
      maxItems: 7
      items: { $ref: '#/DailyAggregateDto' }
    weeklyTotal: { $ref: '#/DailyAggregateDto' }
    floorViolations:
      type: array
      description: 'List of macro/micro keys whose weekly total fell below 7-day-summed floor.'
      items: { type: string, maxLength: 64 }
DailyAggregateDto:
  type: object
  required: [caloriesPlanned, caloriesActualSoFar, caloriesRemaining, protein, carbs, fat, fibre, microsActualSoFar]
  properties:
    caloriesPlanned:     { type: integer, minimum: 0 }
    caloriesActualSoFar: { type: integer, minimum: 0 }
    caloriesRemaining:   { type: integer }
    protein: { $ref: '#/MacroAggregateDto' }
    carbs:   { $ref: '#/MacroAggregateDto' }
    fat:     { $ref: '#/MacroAggregateDto' }
    fibre:   { $ref: '#/MacroAggregateDto' }
    microsActualSoFar:
      type: object
      additionalProperties: { type: number, format: double, minimum: 0 }
MacroAggregateDto:
  type: object
  required: [plannedG, actualSoFarG, remainingG]
  properties:
    plannedG:     { type: number, format: double, minimum: 0 }
    actualSoFarG: { type: number, format: double, minimum: 0 }
    remainingG:   { type: number, format: double }
DivergenceSummaryDto:
  type: object
  required: [plannedSoFar, actualSoFar, percentVariance]
  properties:
    plannedSoFar:
      type: object
      additionalProperties: { type: number, format: double, minimum: 0 }
    actualSoFar:
      type: object
      additionalProperties: { type: number, format: double, minimum: 0 }
    percentVariance:
      type: object
      additionalProperties: { type: number, format: double }
```

**Gotcha applied** — no nullable fields on any new schema; no risk of the `$ref + nullable` sticky trap. **`DailyAggregateDto` and `MacroAggregateDto` may already exist** from 01b's daily-aggregate endpoint — verify; if so, do NOT redefine. The weekly schema's `$ref` to `DailyAggregateDto` is then a normal (non-nullable) `$ref` which is safe.

**Gotcha applied** — every description string containing `,` `:` `'` is single-quoted (round-4 lesson). `'List of macro/micro keys whose weekly total fell below 7-day-summed floor.'` is single-quoted because it contains a comma.

**Gotcha applied** — `caloriesRemaining` is NOT `minimum: 0` because over-eating yields negative remaining; the rest of the macro `remainingG` likewise has no minimum.

### Append to entry `src/main/resources/openapi/openapi.yaml`

Under `paths:` in the `# nutrition` block (append after 01g's `/floor-gate/evaluate` entry):

```yaml
  /api/v1/nutrition/intake/week/{weekStart}/aggregate:
    $ref: 'paths/nutrition.yaml#/nutritionIntakeWeekAggregate'
```

Under `components.schemas:` in the `# nutrition` block (alphabetical insertion; verify whether `DailyAggregateDto` / `MacroAggregateDto` already registered from 01b):

```yaml
    DailyAggregateDto:    { $ref: 'schemas/nutrition.yaml#/DailyAggregateDto' }       # only if not already registered by 01b
    DivergenceSummaryDto: { $ref: 'schemas/nutrition.yaml#/DivergenceSummaryDto' }
    MacroAggregateDto:    { $ref: 'schemas/nutrition.yaml#/MacroAggregateDto' }       # only if not already registered by 01b
    WeeklyAggregateDto:   { $ref: 'schemas/nutrition.yaml#/WeeklyAggregateDto' }
```

## Verbatim shape snippets

### `IntakeAggregator` skeleton

```java
@Component
class IntakeAggregator {
  private final IntakeDayRepository intakeDayRepository;
  private final NutritionTargetsRepository nutritionTargetsRepository;

  WeeklyAggregateDto aggregateWeek(UUID userId, LocalDate weekStart) {
    LocalDate weekEnd = weekStart.plusDays(6);
    Map<LocalDate, IntakeDay> byDate = intakeDayRepository
        .findAllByUserIdAndOnDateBetween(userId, weekStart, weekEnd).stream()
        .collect(Collectors.toMap(IntakeDay::getOnDate, Function.identity()));

    List<DailyAggregateDto> perDay = new ArrayList<>(7);
    for (int i = 0; i < 7; i++) {
      LocalDate d = weekStart.plusDays(i);
      IntakeDay day = byDate.get(d);
      perDay.add(day != null ? aggregateDay(userId, d) : emptyAggregate());
    }
    DailyAggregateDto weeklyTotal = sumDailies(perDay);
    List<String> floorViolations = computeWeeklyFloorViolations(userId, weeklyTotal);
    return new WeeklyAggregateDto(weekStart, weekEnd, perDay, weeklyTotal, floorViolations);
  }
}
```

### `DivergenceDetector` skeleton

```java
@Component
class DivergenceDetector {
  private final IntakeDayRepository intakeDayRepository;
  private final NutritionTargetsRepository nutritionTargetsRepository;
  private final NutritionDivergenceStateRepository stateRepository;
  private final ApplicationEventPublisher events;
  private final BigDecimal threshold;       // @Value("${mealprep.nutrition.divergence.macro-variance-threshold:0.15}")
  private final int minPlannedFloorKcal;     // @Value("${mealprep.nutrition.divergence.minimum-planned-floor-kcal:200}")

  void detectAndPublish(UUID userId, LocalDate onDate, UUID traceId) {
    Optional<IntakeDay> dayOpt = intakeDayRepository.findWithDetailsByUserIdAndOnDate(userId, onDate);
    if (dayOpt.isEmpty()) return;
    Optional<NutritionTargets> targetsOpt = nutritionTargetsRepository.findWithChildrenByUserId(userId);
    if (targetsOpt.isEmpty()) return;

    IntakeDay day = dayOpt.get();
    int plannedKcal = sumPlannedKcal(day);
    if (plannedKcal < minPlannedFloorKcal) return;

    boolean hasPending = day.getSlots().stream().anyMatch(s -> s.getActualStatus() == IntakeSlotStatus.PENDING);
    DivergenceSnapshot snapshot = computeSnapshot(day);
    Set<String> newDiverged = snapshot.diverged(threshold);

    NutritionDivergenceState row = stateRepository.findByUserIdAndOnDate(userId, onDate)
        .orElseGet(() -> NutritionDivergenceState.empty(userId, onDate));
    Set<String> previousDiverged = row.getDivergedMacros();

    boolean shouldPublish =
        (!newDiverged.isEmpty() && !newDiverged.equals(previousDiverged) && hasPending)   // fresh / changed divergence
        || (newDiverged.isEmpty() && !previousDiverged.isEmpty());                         // resolution

    if (shouldPublish) {
      events.publishEvent(new NutritionIntakeDivergedEvent(userId, onDate,
          Set.copyOf(newDiverged), snapshot.toDto(), traceId, Instant.now()));
    }
    row.setDivergedMacros(newDiverged);
    row.setUpdatedAt(Instant.now());
    stateRepository.save(row);
  }
}
```

### Controller addition

```java
// IntakeController — append a method
@GetMapping("/week/{weekStart}/aggregate")
public ResponseEntity<WeeklyAggregateDto> getWeeklyAggregate(@PathVariable LocalDate weekStart) {
  if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
    throw new InvalidWeekStartException(weekStart.getDayOfWeek());
  }
  return ResponseEntity.ok(nutritionQueryService.getWeeklyAggregate(currentUser.requireUserId(), weekStart));
}
```

### Wiring in 01b/01c flows

```java
// In confirmFromPlan / overrideIntakeFromFreeText / editIntakeManually / skipMeal:
// BEFORE the existing eventPublisher.publishEvent(new IntakeLoggedEvent(...))
divergenceDetector.detectAndPublish(userId, onDate, traceId);
```

## Edge-case checklist

### `IntakeAggregator`

- [ ] `aggregateWeek` returns `perDay` with exactly 7 entries, sorted Mon→Sun
- [ ] User with intake on only 3 of 7 days → 4 zero-valued `DailyAggregateDto` entries + 3 populated; weekly total reflects only the 3 populated
- [ ] User with NO intake all week → `weeklyTotal` is all zeros, `floorViolations` contains every hard-floor macro/micro (or empty if no targets configured)
- [ ] User with NO targets configured → `floorViolations = []`
- [ ] Hard-floor macro: weekly actual < `floorG × 7` → key appears in `floorViolations`
- [ ] Hard-floor macro: weekly actual >= `floorG × 7` → key does NOT appear
- [ ] Soft-floor macro (`isHardFloor=false`) → NEVER appears in `floorViolations` regardless of value
- [ ] Hard-floor micro: weekly actual < `targetValue × 7` → key appears
- [ ] `microsActualSoFar` merges keys across days: day 1 `iron_mg=5`, day 2 `iron_mg=8` → weekly `iron_mg=13`
- [ ] Determinism: same `(userId, weekStart)` → byte-identical `WeeklyAggregateDto` (no `Instant.now()` in the aggregator)

### Weekly endpoint

- [ ] `GET /week/2026-05-11/aggregate` (Monday) → 200 + WeeklyAggregateDto
- [ ] `GET /week/2026-05-12/aggregate` (Tuesday) → 400 `invalid-week-start`
- [ ] `GET /week/2026-05-11/aggregate` anonymous → 401
- [ ] OpenAPI shape matches (swagger-request-validator filter active)
- [ ] Service method is `@Transactional(readOnly = true)`
- [ ] `weekEnd == weekStart.plusDays(6)` always

### `DivergenceDetector`

- [ ] No intake day → silent no-op
- [ ] No targets configured → silent no-op
- [ ] `plannedKcal < 200` (default threshold) → silent no-op (small-plan noise filter)
- [ ] All slots `PENDING` → no decided slots → no actuals → no event
- [ ] All slots decided (no `PENDING`) + variance > threshold → NO new divergence (no future slot to re-optimise); but resolution event fires if previously diverged
- [ ] At least one `PENDING` + protein variance 20% (above 15% threshold) → publishes event with `divergedMacros = {"protein"}`
- [ ] Same user + same day + second call producing identical `divergedMacros` set → NO new event (dedup)
- [ ] Same user + same day + new call producing a SUPERSET (`{protein, fat}`) → publishes event with new set
- [ ] Same user + same day + new call producing empty set after previously non-empty → publishes RESOLUTION event with `divergedMacros = Set.of()`
- [ ] Variance < 15% on every macro → no event, dedup state updated to empty set
- [ ] Macro with `plannedSoFar == 0` → variance undefined → macro skipped (not added to diverged)
- [ ] Snack-only flow (`logSnack`, `removeSnack`) → `DivergenceDetector` NOT invoked (per LLD line 917)
- [ ] CONFIRM, OVERRIDE, EDIT, SKIP flows → `DivergenceDetector` IS invoked exactly once each per call
- [ ] Dedup state row survives JVM restart (verified by stopping context, restarting, repeating same intake → no new event)
- [ ] Threshold and minimum-planned-floor configurable via `application.yml` properties

### `NutritionIntakeDivergedEvent`

- [ ] Event fires `AFTER_COMMIT` of the parent intake update (NOT inside the writing tx)
- [ ] `scopeKind()` returns `"nutrition-intake"`; `scopeId()` returns `userId`
- [ ] `divergedMacros` is an immutable `Set` (use `Set.copyOf`)
- [ ] `summary.percentVariance` uses fractional units (0.20 for 20%), not pct values (20.0)

### Cross-cutting

- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 1 handler method
- [ ] `NutritionBoundaryTest` (from 01a) still passes — no new sub-packages added (component in existing `domain/service/internal/`, repo in `domain/repository/`, event in `event/`, exception in `exception/`, DTO in `api/dto/`)
- [ ] `divergenceDetector.detectAndPublish` is called INSIDE the parent `@Transactional` method — the event publication happens via `ApplicationEventPublisher` and is delivered `AFTER_COMMIT` to any registered listener (the future planner module)
- [ ] **No `@TransactionalEventListener` in 01h** — we publish, we don't consume — so the round-7 propagation gotcha (`REQUIRES_NEW` / `NOT_SUPPORTED` only) does not apply here. If a test listener captures the event for assertions, it must follow the round-7 rule
- [ ] **No @MockBean of `NutritionServiceImpl`** in any new IT without `grep "implements"` first — `NutritionServiceImpl` provides `NutritionQueryService`, `NutritionUpdateService`, `NutritionCalculationService`, `NutritionFloorGateService` from 01g; any `@MockBean` of one MUST stub all four (round-6 multi-interface gotcha)
- [ ] OpenAPI request/response shapes match (swagger-request-validator in the IT)
- [ ] Migration applies cleanly; `FlywayMigrationIT` passes
- [ ] No N+1 — `aggregateWeek` issues exactly **two** SQL statements: one `findAllByUserIdAndOnDateBetween` with `@EntityGraph` (sibling of 01b's hot-read entity graph) + one `findWithChildrenByUserId` for targets
- [ ] `detectAndPublish` issues exactly **three** SQL statements: `findWithDetailsByUserIdAndOnDate` + `findWithChildrenByUserId` + `findByUserIdAndOnDate` on state (save inside tx, batched)
- [ ] No regression on 01a..01g tests
- [ ] No `pom.xml` dependency adds
- [ ] No recipe / household / provisions / auth / preference / ai module file touched

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601601300__nutrition_create_divergence_state.sql

NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/NutritionDivergenceState.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/NutritionDivergenceStateRepository.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/IntakeAggregator.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/DivergenceDetector.java

NEW   src/main/java/com/example/mealprep/nutrition/api/dto/WeeklyAggregateDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/DivergenceSummaryDto.java
NEW   src/main/java/com/example/mealprep/nutrition/event/NutritionIntakeDivergedEvent.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/InvalidWeekStartException.java

MOD   src/main/java/com/example/mealprep/nutrition/api/controller/IntakeController.java                  (append GET /week/{weekStart}/aggregate)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionQueryService.java             (verify getWeeklyAggregate signature present; if absent, append)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java     (implement getWeeklyAggregate via IntakeAggregator; wire DivergenceDetector into confirmFromPlan / overrideIntakeFromFreeText / editIntakeManually / skipMeal; constructor adds IntakeAggregator + DivergenceDetector)
MOD   src/main/java/com/example/mealprep/nutrition/domain/repository/IntakeDayRepository.java            (add findAllByUserIdAndOnDateBetween if absent; verify @EntityGraph for slots+snacks)
MOD   src/main/java/com/example/mealprep/nutrition/api/NutritionExceptionHandler.java                    (append 1 @ExceptionHandler method for InvalidWeekStartException; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/resources/application.yml                                                                  (add `mealprep.nutrition.divergence.macro-variance-threshold: 0.15` and `mealprep.nutrition.divergence.minimum-planned-floor-kcal: 200` defaults if not already present)

MOD   src/main/resources/openapi/paths/nutrition.yaml      (append 1 new path-item; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/nutrition.yaml    (append 3 new schemas — WeeklyAggregateDto, DivergenceSummaryDto; verify DailyAggregateDto/MacroAggregateDto already exist from 01b)
MOD   src/main/resources/openapi/openapi.yaml              (1 path entry + 1-3 schema refs under `# nutrition` block)

NEW   src/test/java/com/example/mealprep/nutrition/IntakeAggregatorTest.java                   (aggregateWeek with mixed-populated days; missing days zero-fill; floor violations on hard-floor macros; targets-absent → empty violations; micro-key merging)
NEW   src/test/java/com/example/mealprep/nutrition/DivergenceDetectorTest.java                 (no-day no-op; no-targets no-op; below-min-plan no-op; threshold-cross fires event; dedup suppresses identical re-fire; resolution event fires when newly-empty)
NEW   src/test/java/com/example/mealprep/nutrition/WeeklyAggregateFlowIT.java                  (HTTP: 200 happy on Monday; 400 on non-Monday; 401 anonymous; OpenAPI shape; determinism on identical state)
NEW   src/test/java/com/example/mealprep/nutrition/DivergenceDetectorIT.java                   (full flow: confirm intake → variance > threshold → exactly one NutritionIntakeDivergedEvent AFTER_COMMIT; second identical confirm → no second event; final confirm bringing variance back below threshold + previous diverged → resolution event)
MOD   src/test/java/com/example/mealprep/nutrition/testdata/NutritionTestData.java             (append builders for WeeklyAggregateDto, DivergenceSummaryDto, NutritionIntakeDivergedEvent, NutritionDivergenceState)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-8 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exception goes in `NutritionExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module rule lives in `NutritionBoundaryTest` (unchanged in 01h).
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, migrations, entities — none touched.
- 01a..01g existing tests — none modified; only `NutritionTestData.java` gets appends.

## Dependencies

- **Hard dependency**: `nutrition-01a` (merged) — `NutritionException`, `NutritionExceptionHandler`, `NutritionBoundaryTest`, `NutritionServiceImpl`, `NutritionTargets`, `MacroTarget`, `MicroTarget`, `NutritionTargetsRepository.findWithChildrenByUserId`, `is_hard_floor` column.
- **Hard dependency**: `nutrition-01b` (merged) — `IntakeDay`, `IntakeSlot`, `IntakeSnack` entities + repositories; `IntakeSlotStatus` enum (`PENDING`, `CONFIRMED`, `OVERRIDDEN`, `EDITED`, `SKIPPED`); `MealSlot` enum; `DailyAggregateDto`, `MacroAggregateDto` (already shipped on the API); `confirmFromPlan` flow; `findWithDetailsByUserIdAndOnDate` hot-read.
- **Hard dependency**: `nutrition-01c` (merged) — `overrideIntakeFromFreeText`, `editIntakeManually`, `skipMeal`, `logSnack` flows (we wire into the first three, NOT `logSnack`).
- **Hard dependency**: `nutrition-01g` (merged) — `NutritionFloorGateService` (we link to it in NOT-in-scope; we don't import it).
- **Hard dependency**: `core` — `ScopeChangedEvent` base.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **No cross-module SPI**. 01h neither defines nor consumes any cross-module interface.
- **Sibling tickets running in parallel** (Wave 2 round 8): `provisions-01h`, `recipe-01h`. None should touch any nutrition file or the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# nutrition` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean (mandatory; not optional)
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 1 handler method
- [ ] OpenAPI 3.0 inlined nullable fields (none in this ticket); enum/object `$ref`s use plain `$ref` (not nullable-paired) — defensive against round-1/4/6 sticky trap
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] Migration applies cleanly; `FlywayMigrationIT` passes
- [ ] **`DivergenceDetector` is invoked inside the existing `@Transactional` methods** (not a separate listener) — round-7 `@TransactionalEventListener` propagation rule does NOT apply because we publish (don't consume)
- [ ] **No `@MockBean` on `NutritionServiceImpl`** in any new IT without `grep "implements"` first — round-6 multi-interface gotcha
- [ ] No regression on 01a-01g tests
- [ ] No `pom.xml` dependency adds
- [ ] No recipe / household / provisions / auth / preference / ai module file touched

## What's NOT in scope

- Mid-week re-opt offer queue — planner module's concern (consumer of `NutritionIntakeDivergedEvent`).
- `@Scheduled` sweep of `nutrition_divergence_state` rows >30 days old — follow-up if storage becomes a concern.
- Monthly / quarterly rollups — daily and weekly only for Wave 2.
- Per-meal-slot aggregation in the weekly view — the per-day rollup breaks down per slot via `IntakeDayDto`.
- Caching the weekly aggregate — the read is two SQL statements; caching is premature.
- Admin-role gate on `/week/{weekStart}/aggregate` — caller's own data only; no admin scope.
- A separate `NutritionIntakeDivergenceResolvedEvent` — the same event record with `divergedMacros = Set.of()` carries the resolution signal.

Squash-merge with: `feat(nutrition): 01h — IntakeAggregator + weekly rollup endpoint + DivergenceDetector + NutritionIntakeDivergedEvent`
