# Ticket: nutrition — 01g `NutritionFloorGateService` (planner's per-slot acceptance gate)

## Summary

Layer the **`NutritionFloorGateService`** — the deterministic floor gate the planner consumes for its multiplicative scoring kill-switch — on top of 01a/01b/01c/01d/01e/01f. Per [LLD §`NutritionFloorGateService` lines 755-776](../../lld/nutrition.md), [LLD §test plan line 1068](../../lld/nutrition.md). Pure read + compute: loads the caller's `NutritionTargets` aggregate (from 01a), walks a candidate-plan rollup, and returns the list of breached **hard floors** plus a pass/fail bit. **No persistence**. No events. Cross-module in-process facade only — consumed by the future planner module (which does not exist yet); 01g exposes a single side-door REST endpoint `POST /api/v1/nutrition/floor-gate/evaluate` for operator / integration testing.

01g is the **last nutrition Wave-2 ticket**. After 01g, nutrition is feature-complete except for `01h` (weekly aggregates + `DivergenceDetector`).

**Defers** (still out of scope after 01g):

- Weekly aggregates + `DivergenceDetector` → **nutrition-01h**
- The actual planner-side multiplicative gate (the `passed=false → score *= 0` collapse). That's the planner module's concern; 01g just returns the verdict.
- The household batch path `evaluateForHousehold(List<UUID>, ...)` — see "LLD divergence: household batch path" below.

## LLD divergence — household batch path is in-scope but cross-module-read deferred

LLD line 762 spec'd a second method `Map<UUID, FloorGateResultDto> evaluateForHousehold(List<UUID> userIds, CandidatePlanRollupDto rollup)`. **01g ships the method signature** but its impl simply iterates `evaluate` per `userId` — no N+1 optimisation, no shared `findWithChildrenByUserIdIn` repository call (the bulk repo method **does** exist from 01a per LLD line 607). **Worth user review** — alternative is to issue one `findWithChildrenByUserIdIn` and zip — rejected because batch sizes are bounded (a household maxes at ~6 members per HLD), and a clean per-user loop is more readable. The IT verifies the batch path returns one entry per input.

## LLD divergence — per-day vs per-slot

The user's prompt described the gate as "is this meal slot's nutrition acceptable?" — **per slot**. The LLD's `CandidatePlanRollupDto` is **per day** (`CandidateDailyRollupDto` carries the rolled-up macros per `LocalDate`). 01g follows the **LLD's per-day shape** verbatim (the planner produces per-day rollups by summing its candidate meal slots before calling the gate). The "per slot" framing in the parent prompt is the upstream signal; the LLD has already collapsed it to per-day. **Worth user review** — adding a per-slot `FloorViolationDto.mealSlot` field is a minor addition; rejected for v1 because the gate is deterministic regardless and the per-slot attribution is a presentation concern (the planner already knows which slot it just added when the gate flips to false).

## LLD divergence — REST endpoint added

LLD line 824 says: "`NutritionCalculationService` is in-process only — no REST — because the recipe module injects it directly." The same logic strictly applies to `NutritionFloorGateService` (planner injects it directly). **01g adds one REST endpoint** `POST /api/v1/nutrition/floor-gate/evaluate` mirroring 01f's manual-recalc side-door — same justification: operator / debugging / integration-test access without producing a plan. Authenticated; the caller's own `userId` is resolved server-side; the rollup is in the body. **Worth user review** — could be admin-gated when the role enum gains `ADMIN`. v1 ships it open to any authenticated caller.

## Behavioural spec

### `NutritionFloorGateService` — public interface

1. New public interface `com.example.mealprep.nutrition.domain.service.NutritionFloorGateService` verbatim from [LLD lines 760-763](../../lld/nutrition.md):
   ```java
   public interface NutritionFloorGateService {
       FloorGateResultDto evaluate(UUID userId, CandidatePlanRollupDto rollup);
       Map<UUID, FloorGateResultDto> evaluateForHousehold(List<UUID> userIds, CandidatePlanRollupDto rollup);
   }
   ```
2. **Implemented by `NutritionServiceImpl`** (existing class from 01a..01f). The new methods join the existing impl per the LLD's single-impl convention (LLD §package layout line 41).

### `evaluate` flow

3. `@Transactional(readOnly = true)`. Pure read. No SPI write-back; no events.
4. **Targets load** (01a's repo): `nutritionTargetsRepository.findWithChildrenByUserId(userId)` → `Optional<NutritionTargets>`. **Empty → return** `FloorGateResultDto(passed=true, violations=[], summary="No targets configured — gate passes by default")` per LLD line 1068 ("Floors not configured → no violation (HLD: floors are optional)").
5. **Hard-floor list assembly**: walk the loaded `NutritionTargets`:
   - For each macro (`protein, carbs, fat, fibre`, plus optional `saturatedFat`): if `MacroTarget.isHardFloor == true` AND `floorG != null` → add `(macroKey, floorG)` to the hard-floor list.
   - For each `MicroTarget` child: if `isHardFloor == true` AND `targetValue != null` → add `(microKey, targetValue)` to the hard-floor list.
   - **Defaults per LLD line 774**: macros default `isHardFloor=true`; micros default `isHardFloor=false`. **01g reads the flag as persisted** — it does not re-apply defaults at evaluation time (defaults are write-time concerns owned by 01a's `nutrition_targets` migration `is_hard_floor NOT NULL DEFAULT true` for macros and the JSONB `is_hard_floor` key for micros — verify with 01a's migration before assuming).
6. **Per-day walk** of `rollup.perDay()`:
   - For each `CandidateDailyRollupDto day`:
     - For each `(macroKey, floorG)` in hard-floor list: compare against the day's macro field (`day.proteinG()` / `.carbsG()` / `.fatG()` / `.fibreG()` — for `saturatedFat`, the rollup carries it inside `day.micros()` keyed `"saturatedFatG"`; **verify** the rollup shape — if it's a top-level field, branch accordingly. **01g treats `saturatedFat` as a micro** for the gate purposes since the rollup's primary shape is the macro-quartet + micros map).
     - `actual.compareTo(floorG) < 0` → append `new FloorViolationDto(day.date(), macroKey, floorG, actual)` to violations.
     - For each `(microKey, target)` in hard-floor list: `day.micros().getOrDefault(microKey, BigDecimal.ZERO).compareTo(target) < 0` → append violation.
7. **`passed = violations.isEmpty()`**.
8. **`summary`**:
   - Empty violations → `"Plan passes all hard floors across N day(s)"` (with N = `rollup.perDay().size()`).
   - Non-empty → `"Plan fails N hard floor(s) across M day(s)"` (counts distinct `(date, key)` pairs and distinct dates).
9. **Return** `new FloorGateResultDto(passed, violations, summary)`.
10. **No targets row** AND `rollup.perDay()` is empty → still returns `passed=true` with summary `"No targets configured — gate passes by default"`. (Don't change summary based on empty rollup; the no-targets short-circuit takes precedence.)

### `evaluateForHousehold` flow

11. `@Transactional(readOnly = true)`.
12. Iterates `userIds` and calls `evaluate(userId, rollup)` per user. **Bulk repo not used** (LLD divergence above).
13. Returns `Map<UUID, FloorGateResultDto>` preserving input order via `LinkedHashMap`.
14. Empty `userIds` → empty map.
15. Duplicate `userIds` → last-wins map semantics (each `evaluate` call is deterministic so duplicates collapse safely).

### `POST /api/v1/nutrition/floor-gate/evaluate`

16. New endpoint on a new controller `NutritionFloorGateController` under `nutrition/api/controller/`. Authenticated (cookieAuth).
17. **Caller resolution**: `actorUserId = currentUserResolver.requireUserId()`. The gate evaluates **for the caller** — no `userId` path variable. The household batch path is NOT exposed via REST in 01g (since the planner is the natural consumer and it injects the service directly).
18. **Request body**: `CandidatePlanRollupDto` per LLD line 765 — JSON request must include `startDate`, `endDate`, `perDay` (array of `CandidateDailyRollupDto`). Validation: `@NotNull` on every field; `@Size(min=1)` on `perDay`.
19. **Validation rule** (service-layer): `rollup.perDay()` dates must lie in `[startDate, endDate]` inclusive — else 400 `InvalidPlanRollupException` (NEW). The endpoint also rejects `endDate < startDate` with the same 400.
20. **Response**: 200 with `FloorGateResultDto`. Always 200 — `passed=false` is NOT an error (the gate's whole purpose is to flip a bit, not error).
21. Anonymous → 401 (existing `SessionAuthenticationFilter`).

### Errors

22. New exception `InvalidPlanRollupException extends NutritionException` (400 — `type = .../invalid-plan-rollup`).
23. **Append one new `@ExceptionHandler` method** to the existing `NutritionExceptionHandler` (already `@Order(Ordered.HIGHEST_PRECEDENCE)` from 01a/01b/01c/01d/01e/01f). Do NOT modify `config/GlobalExceptionHandler.java`. Do NOT create a second handler class.

### Cross-module facade

24. **Append** `NutritionFloorGateService` re-export to `NutritionModule.java` if 01a follows the nested-class facade pattern; else `@Autowired NutritionFloorGateService` works directly. **Optional.**

## Database

**Zero migrations.** 01g is a pure code-path change. The `nutrition_targets` table + `is_hard_floor` column ship from 01a/01b.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/nutrition.yaml`

(File extended by 01a..01f — append one new path-item below 01f's most recent block. Do NOT touch existing path-items.)

```yaml
nutritionFloorGateEvaluate:
  post:
    tags: [Nutrition]
    operationId: evaluateNutritionFloorGate
    summary: 'Evaluate the hard-floor gate for the calling user against a candidate plan rollup.'
    description: 'Returns passed=true with empty violations when no targets are configured or every hard floor is met across every day.'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/CandidatePlanRollupDto' }
    responses:
      '200':
        description: 'Gate verdict. HTTP 200 even when passed=false; passed is the actionable signal.'
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/FloorGateResultDto' }
      '400': { description: 'Validation error (e.g. perDay dates outside [startDate, endDate])', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/nutrition.yaml`

```yaml
CandidatePlanRollupDto:
  type: object
  required: [startDate, endDate, perDay]
  properties:
    startDate: { type: string, format: date }
    endDate:   { type: string, format: date }
    perDay:
      type: array
      minItems: 1
      items: { $ref: '#/CandidateDailyRollupDto' }
CandidateDailyRollupDto:
  type: object
  required: [date, activityLevel, calories, proteinG, carbsG, fatG, fibreG, micros]
  properties:
    date: { type: string, format: date }
    activityLevel:
      type: string
      enum: [SEDENTARY, LIGHT, MODERATE, ACTIVE, VERY_ACTIVE]
    calories: { type: integer, minimum: 0 }
    proteinG: { type: number, format: double, minimum: 0 }
    carbsG:   { type: number, format: double, minimum: 0 }
    fatG:     { type: number, format: double, minimum: 0 }
    fibreG:   { type: number, format: double, minimum: 0 }
    micros:
      type: object
      additionalProperties: { type: number, format: double, minimum: 0 }
FloorGateResultDto:
  type: object
  required: [passed, violations, summary]
  properties:
    passed: { type: boolean }
    violations:
      type: array
      items: { $ref: '#/FloorViolationDto' }
    summary: { type: string, maxLength: 255 }
FloorViolationDto:
  type: object
  required: [date, macroOrMicro, floor, actual]
  properties:
    date: { type: string, format: date }
    macroOrMicro: { type: string, maxLength: 64 }
    floor:  { type: number, format: double, minimum: 0 }
    actual: { type: number, format: double, minimum: 0 }
```

**Gotcha applied**: `FloorGateResultDto` carries no nullable fields. `activityLevel` is inlined as a non-nullable enum directly under `CandidateDailyRollupDto.properties.activityLevel` (NOT `$ref + nullable`). Even though `ActivityLevel` may already be a named schema from 01b, we inline here per the round-1/4/6 sticky lesson — non-nullable here is by intent, the inline-vs-ref decision still prefers inline for any field that *could ever* become nullable downstream (defensive).

**Gotcha applied**: every YAML description string containing `,` `:` `'` is single-quoted per round-4 lesson. `'HTTP 200 even when passed=false; passed is the actionable signal.'` is single-quoted because it contains commas and a semicolon.

### Append to entry `src/main/resources/openapi/openapi.yaml`

Under `paths:` in the `# nutrition` block (append after 01f's `/recalculate` entry):

```yaml
  /api/v1/nutrition/floor-gate/evaluate:
    $ref: 'paths/nutrition.yaml#/nutritionFloorGateEvaluate'
```

Under `components.schemas:` in the `# nutrition` block (alphabetical insertion):

```yaml
    CandidatePlanRollupDto:   { $ref: 'schemas/nutrition.yaml#/CandidatePlanRollupDto' }
    CandidateDailyRollupDto:  { $ref: 'schemas/nutrition.yaml#/CandidateDailyRollupDto' }
    FloorGateResultDto:       { $ref: 'schemas/nutrition.yaml#/FloorGateResultDto' }
    FloorViolationDto:        { $ref: 'schemas/nutrition.yaml#/FloorViolationDto' }
```

## Verbatim shape snippets

### Service interface

```java
package com.example.mealprep.nutrition.domain.service;

public interface NutritionFloorGateService {
  FloorGateResultDto evaluate(UUID userId, CandidatePlanRollupDto rollup);
  Map<UUID, FloorGateResultDto> evaluateForHousehold(List<UUID> userIds, CandidatePlanRollupDto rollup);
}
```

### DTOs

```java
public record CandidatePlanRollupDto(LocalDate startDate, LocalDate endDate,
                                     List<CandidateDailyRollupDto> perDay) {}

public record CandidateDailyRollupDto(LocalDate date, ActivityLevel activityLevel, int calories,
                                      BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG, BigDecimal fibreG,
                                      Map<String, BigDecimal> micros) {}

public record FloorGateResultDto(boolean passed, List<FloorViolationDto> violations, String summary) {}

public record FloorViolationDto(LocalDate date, String macroOrMicro, BigDecimal floor, BigDecimal actual) {}
```

### Service skeleton

```java
@Override
@Transactional(readOnly = true)
public FloorGateResultDto evaluate(UUID userId, CandidatePlanRollupDto rollup) {
  Optional<NutritionTargets> targetsOpt = nutritionTargetsRepository.findWithChildrenByUserId(userId);
  if (targetsOpt.isEmpty()) {
    return new FloorGateResultDto(true, List.of(),
        "No targets configured — gate passes by default");
  }
  NutritionTargets targets = targetsOpt.get();

  // Build the hard-floor lists once
  List<MacroFloor> macroFloors = collectMacroFloors(targets);
  List<MicroFloor> microFloors = collectMicroFloors(targets);

  List<FloorViolationDto> violations = new ArrayList<>();
  for (CandidateDailyRollupDto day : rollup.perDay()) {
    for (MacroFloor mf : macroFloors) {
      BigDecimal actual = mf.extract(day);
      if (actual.compareTo(mf.floor()) < 0) {
        violations.add(new FloorViolationDto(day.date(), mf.key(), mf.floor(), actual));
      }
    }
    for (MicroFloor mfi : microFloors) {
      BigDecimal actual = day.micros().getOrDefault(mfi.key(), BigDecimal.ZERO);
      if (actual.compareTo(mfi.floor()) < 0) {
        violations.add(new FloorViolationDto(day.date(), mfi.key(), mfi.floor(), actual));
      }
    }
  }

  boolean passed = violations.isEmpty();
  String summary = passed
      ? "Plan passes all hard floors across " + rollup.perDay().size() + " day(s)"
      : "Plan fails " + violations.size() + " hard floor(s) across "
          + violations.stream().map(FloorViolationDto::date).distinct().count() + " day(s)";
  return new FloorGateResultDto(passed, List.copyOf(violations), summary);
}

@Override
@Transactional(readOnly = true)
public Map<UUID, FloorGateResultDto> evaluateForHousehold(List<UUID> userIds, CandidatePlanRollupDto rollup) {
  LinkedHashMap<UUID, FloorGateResultDto> out = new LinkedHashMap<>();
  for (UUID userId : userIds) out.put(userId, evaluate(userId, rollup));
  return out;
}

private record MacroFloor(String key, BigDecimal floor,
                          java.util.function.Function<CandidateDailyRollupDto, BigDecimal> extract) {
  BigDecimal extract(CandidateDailyRollupDto day) { return extract.apply(day); }
}
private record MicroFloor(String key, BigDecimal floor) {}
```

### Controller skeleton

```java
@RestController
@RequestMapping("/api/v1/nutrition/floor-gate")
@Tag(name = "Nutrition")
public class NutritionFloorGateController {
  private final NutritionFloorGateService gate;
  private final CurrentUserResolver currentUser;

  @PostMapping("/evaluate")
  public ResponseEntity<FloorGateResultDto> evaluate(@Valid @RequestBody CandidatePlanRollupDto rollup) {
    validateRollupDateRange(rollup);                    // throws InvalidPlanRollupException on bad shape
    return ResponseEntity.ok(gate.evaluate(currentUser.requireUserId(), rollup));
  }
}
```

## Edge-case checklist

- [ ] No targets configured (`findWithChildrenByUserId` empty) → `passed=true`, `violations=[]`, summary "No targets configured — gate passes by default"
- [ ] All macros hard-floor enabled, plan meets every floor → `passed=true`, summary "Plan passes all hard floors across N day(s)"
- [ ] One macro floor breached on one day → `passed=false`, exactly one violation
- [ ] Multiple macro floors breached across multiple days → `passed=false`, one violation per `(date, macroKey)` pair
- [ ] Macro target with `isHardFloor=false` → never produces a violation even if breached
- [ ] Macro target with `floorG=null` AND `isHardFloor=true` → no violation (no floor to compare against)
- [ ] Micro target with `isHardFloor=true` AND `targetValue=100` and rollup day's `micros["iron_mg"]=50` → violation
- [ ] Micro target with `isHardFloor=true` AND day's `micros` lacks the key entirely → treated as 0 → violation
- [ ] Micro target with `isHardFloor=false` → never produces a violation
- [ ] `evaluateForHousehold` returns one entry per input userId; LinkedHashMap preserves order
- [ ] `evaluateForHousehold` with empty userIds → empty map
- [ ] `evaluateForHousehold` with duplicate userIds → last write wins; size matches distinct count
- [ ] `POST /floor-gate/evaluate` happy path → 200 + FloorGateResultDto JSON; passed/violations/summary all present
- [ ] `POST /floor-gate/evaluate` anonymous → 401
- [ ] `POST /floor-gate/evaluate` with `endDate < startDate` → 400 `invalid-plan-rollup`
- [ ] `POST /floor-gate/evaluate` with `perDay[0].date` outside `[startDate, endDate]` → 400 `invalid-plan-rollup`
- [ ] `POST /floor-gate/evaluate` with `perDay = []` → 400 (Jakarta `@Size(min=1)`)
- [ ] `POST /floor-gate/evaluate` with missing `startDate` → 400 (Jakarta `@NotNull`)
- [ ] `POST /floor-gate/evaluate` with `passed=false` outcome → still HTTP 200 (not 422)
- [ ] OpenAPI request/response shapes match (swagger-request-validator in the IT)
- [ ] Determinism — same `(userId, rollup)` → byte-identical `FloorGateResultDto` (no `Instant.now()` anywhere)
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 1 handler method
- [ ] `NutritionBoundaryTest` (from 01a) still passes — no new sub-packages added (interface in existing `domain/service/`, controller in existing `api/controller/`, exception in existing `exception/`)
- [ ] No N+1 — `evaluate` issues exactly **one** SQL statement (the `findWithChildrenByUserId` `@EntityGraph` from 01a)
- [ ] `evaluateForHousehold(List.of(a, b, c), rollup)` issues exactly **three** SQL statements — one per user
- [ ] No regression on 01a/01b/01c/01d/01e/01f tests
- [ ] No `pom.xml` dependency adds
- [ ] No recipe / household / provisions / auth / preference module file touched

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/nutrition/api/controller/NutritionFloorGateController.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/CandidatePlanRollupDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/CandidateDailyRollupDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/FloorGateResultDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/FloorViolationDto.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionFloorGateService.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/InvalidPlanRollupException.java

MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java     (implements NutritionFloorGateService; constructor unchanged — uses existing NutritionTargetsRepository)
MOD   src/main/java/com/example/mealprep/nutrition/api/NutritionExceptionHandler.java                    (append 1 @ExceptionHandler method; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/nutrition/NutritionModule.java                                  (optional — re-export NutritionFloorGateService)

MOD   src/main/resources/openapi/paths/nutrition.yaml      (append 1 new path-item; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/nutrition.yaml    (append 4 new schemas; verify ActivityLevel does not need redefining)
MOD   src/main/resources/openapi/openapi.yaml              (1 line under paths in `# nutrition` block; 4 schema refs under components.schemas in `# nutrition` block)

NEW   src/test/java/com/example/mealprep/nutrition/NutritionFloorGateServiceTest.java     (no-targets → passes; macro breach; micro breach; isHardFloor=false → never violates; per-day walk; LinkedHashMap order on household path)
NEW   src/test/java/com/example/mealprep/nutrition/NutritionFloorGateFlowIT.java          (HTTP: 200 happy; 200 passed=false; 400 endDate<startDate; 400 day outside range; 401 anonymous; OpenAPI shape validation)
MOD   src/test/java/com/example/mealprep/nutrition/testdata/NutritionTestData.java        (append builders for CandidatePlanRollupDto, CandidateDailyRollupDto, FloorGateResultDto, FloorViolationDto)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-7 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exception goes in the existing `NutritionExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module-specific rule lives in `NutritionBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, migrations, entities — none touched.
- `NutritionBoundaryTest` — no new sub-packages; everything lands in existing `api/controller/`, `api/dto/`, `domain/service/`, `exception/`.

## Dependencies

- **Hard dependency**: `nutrition-01a` (merged) — `NutritionException`, `NutritionExceptionHandler`, `NutritionBoundaryTest`, `NutritionServiceImpl`, `NutritionTargets`, `MacroTarget`, `MicroTarget`, `NutritionTargetsRepository.findWithChildrenByUserId` with `@EntityGraph`, `is_hard_floor` column, `ActivityLevel` enum.
- **Hard dependency**: `nutrition-01b` (merged) — `ActivityLevel` enum on the rollup; pattern reuse.
- **Hard dependency**: `nutrition-01c`, `01d`, `01e`, `01f` (all merged) — pattern reuse only.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **No cross-module SPI**. 01g neither defines nor consumes any cross-module interface.
- **Sibling tickets running in parallel** (Wave 2 round 7): `provisions-01g`, `recipe-01g`. None should touch any nutrition file or the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# nutrition` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean (mandatory; not optional)
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 1 handler method
- [ ] OpenAPI 3.0 inlined nullable fields (none in this ticket); enum `ActivityLevel` inlined under `CandidateDailyRollupDto` (defensive — avoids the round-1/4/6 `$ref + nullable` sticky trap)
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] **No @MockBean of `NutritionServiceImpl`** in any IT — if any IT needs to stub a nutrition interface, it MUST `@MockBean` every interface the impl provides: `NutritionQueryService`, `NutritionUpdateService`, `NutritionCalculationService`, `NutritionFloorGateService` (round-6 gotcha — multi-interface @MockBean collateral damage; quick check: `grep "implements" src/main/java/.../NutritionServiceImpl.java` before adding `@MockBean`)
- [ ] Service methods have `@Transactional(readOnly = true)` — no `@Transactional(noRollbackFor)` needed (no 4xx-from-write-then-throw pattern in this scope; gate is read-only and validation throws BEFORE the service is called)
- [ ] No regression on 01a-01f tests
- [ ] No `pom.xml` dependency adds
- [ ] No recipe / household / provisions / auth / preference module file touched

## What's NOT in scope

- Weekly aggregates + `DivergenceDetector` → **nutrition-01h**.
- The planner-side multiplicative gate (`passed=false → score *= 0`) — planner module's concern.
- Per-meal-slot attribution in `FloorViolationDto` (LLD has per-day rollup; the planner already knows which slot pushed totals over the line).
- Admin-role gate on the `/evaluate` endpoint — open to authenticated callers; admin gate when role enum gains `ADMIN`.
- Caching the targets load — `findWithChildrenByUserId` is one SQL statement with `@EntityGraph`; no caching needed.
- Cross-user authorisation on `evaluate(userId, ...)` — direct service injection is in-process; the controller always uses `currentUser.requireUserId()`.

Squash-merge with: `feat(nutrition): 01g — NutritionFloorGateService + POST /api/v1/nutrition/floor-gate/evaluate (planner's hard-floor verdict)`
