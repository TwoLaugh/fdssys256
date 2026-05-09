# Ticket: nutrition — 01a Nutrition Targets Aggregate

## Summary

Implement the `nutrition` module's foundation aggregate: `NutritionTargets` (root, one per user) plus its four child collections — `PerMealDistributionEntry`, `MicroTarget`, `EatingWindow` (`@OneToOne`), `ActivityAdjustment` — plus a `NutritionTargetsAuditLog`, repository, `NutritionQueryService.getTargets(userId)` (read-by-others contract for the planner / future floor-gate / per-meal distribution callers), `NutritionUpdateService.updateTargets(userId, request, actorUserId)`, the V…600000 / V…600100 / V…600200 / V…600300 / V…600400 migrations, and the endpoint pair: `GET /api/v1/nutrition/targets`, `PUT /api/v1/nutrition/targets`, plus `GET /api/v1/nutrition/targets/audit-log?page=&size=`. Per [`lld/nutrition.md`](../../lld/nutrition.md) §V20260502120000 / 120100 / 120200 / 120300 / 120800, §Service Interfaces, §TargetsController.

**Defers** (this LLD is 1112 lines; only the targets aggregate lands here):
- All of intake (intake_day / slot / snack / audit, prefill / confirm / override / edit / skip / log-snack endpoints) → **nutrition-01b**
- Food/mood journal (entry CRUD, recent-entries listing for feedback context) → **nutrition-01c**
- Ingredient mapping cache (`IngredientMapping`, USDA / OFF clients, `IngredientMappingPipeline`, lookup endpoints) → **nutrition-01d**
- Health directives queue (inbound / accept / reject / safety gate / `DirectiveApplier`) → **nutrition-01e**
- `NutritionCalculationService` (recipe save-time + `RecipeEvolvedEvent` listener) → **nutrition-01f** (depends on 01d)
- `NutritionFloorGateService` (planner's multiplicative-gate consumer) → **nutrition-01g**
- `DivergenceDetector`, `IntakeAggregator`, `WeeklyAggregateDto`, weekly-rollup endpoints → **nutrition-01h**
- DRI seed data (`R__nutrition_seed_dri_defaults.sql`) → **nutrition-01c** (needed first by `initialiseTargets`'s sensible defaults; not by 01a)
- All five published events except `NutritionTargetsChangedEvent` → bundled with their respective sub-tickets

01a unblocks downstream callers ("get user's targets") and lands the audit-log shape so subsequent tickets layer additional `actorKind` values without migration churn.

This is the first nutrition ticket. Module package is currently empty.

**LLD divergence note**: the LLD also specs a `DailyActivityLog` table (V…120300, separate from the `ActivityAdjustment` child of targets). This is a per-day write target, not part of the targets aggregate, and reads are a separate query path (`getDailyActivity` / `getDailyActivityRange`). **Deferring `DailyActivityLog` to nutrition-01b** alongside intake — the per-day activity matters during intake aggregation; not before. The `nutrition_activity_adjustment` child *is* in scope (it's part of the targets aggregate).

## Behavioural spec

### Aggregate shape

1. `NutritionTargets` is the aggregate root, one row per user (`user_id` UNIQUE). Fields per [LLD V20260502120000 lines 86-122](../../lld/nutrition.md): `id, userId, goal (Goal enum: LOSE_WEIGHT|MAINTAIN|GAIN_WEIGHT, default MAINTAIN), dailyCalorieTarget (int), calorieToleranceUnder (int, default 100), calorieToleranceOver (int, default 150), calorieEnforcement (varchar 24, default 'weekly_average'), calorieDirection (EnforcementDirection: UPPER_LIMIT|LOWER_FLOOR|BOTH_BOUNDED, default BOTH_BOUNDED), proteinTargetG (BigDecimal 6,1), proteinFloorG (BigDecimal nullable), proteinEnforcement (default 'daily_floor'), proteinDirection (default LOWER_FLOOR)` — same pattern repeated for `carbs, fat, fibre`, plus `satFatTargetG (nullable), satFatDirection (default UPPER_LIMIT)`, `notes (varchar 512), version (@Version), createdAt, updatedAt, userOverriddenDirections (List<String> via JSONB — see Postgres `text[]` gotcha below)`.
2. `PerMealDistributionEntry` child. Fields: `id, targetsId (via @ManyToOne(fetch=LAZY) NutritionTargets target + @JoinColumn(name="targets_id")), mealSlot (MealSlot enum: BREAKFAST|LUNCH|DINNER|SNACKS), calorieTarget (int), proteinTargetG (BigDecimal 6,1)`. `UNIQUE(targets_id, meal_slot)`. `@Version` only on the root.
3. `MicroTarget` child. Fields: `id, targetsId, nutrientKey (varchar 48), targetValue (BigDecimal 10,3 nullable), upperLimit (BigDecimal 10,3 nullable), sourcePreference (varchar 24 nullable), notes (varchar 255 nullable)`. `UNIQUE(targets_id, nutrient_key)`.
4. `EatingWindow` child via `@OneToOne(mappedBy="target", cascade=ALL, orphanRemoval=true, fetch=LAZY)`. Fields: `id, targetsId UNIQUE, enabled (boolean default false), windowStart (LocalTime nullable), windowEnd (LocalTime nullable), notes (varchar 255 nullable)`.
5. `ActivityAdjustment` child. Fields: `id, targetsId, activityLevel (ActivityLevel enum: REST_DAY|LIGHT_ACTIVITY|TRAINING_DAY|HEAVY_TRAINING), calorieModifier (int default 0), carbModifierG (int default 0)`. `UNIQUE(targets_id, activity_level)`.
6. `NutritionTargetsAuditLog` — append-only. Fields per [LLD V20260502120800 lines 330-340](../../lld/nutrition.md): `id, targetsId, actorUserId, actorKind (varchar 24, enum ActorKind: USER|HEALTH_DIRECTIVE|FEEDBACK), sourceDirectiveId (UUID nullable), fieldPath (varchar 96), previousValueJson (JsonNode via JSONB), newValueJson (JsonNode via JSONB), occurredAt (Instant)`. No `@Version`, no `@LastModifiedDate`.

### `getTargets` (read-by-others)

7. `GET /api/v1/nutrition/targets` returns the calling user's `TargetsDto` (200) or 404 `NutritionTargetsNotFoundException` if no row exists yet. Cookie-auth required.
8. Repository load via `findByUserId(userId)` (plain — **no `@EntityGraph` on multiple `@OneToMany List<>` collections**; that throws `MultipleBagFetchException` because we have three list children: `perMealDistribution`, `microTargets`, `activityAdjustments`). Inside `@Transactional(readOnly = true)`, the service touches each collection via getters (`targets.getPerMealDistribution().size()`, etc.) to force lazy load. Cost: 4 SELECTs per call (1 root + 3 collections; `EatingWindow` `@OneToOne` joins on the root SELECT). **Acceptable for non-hot-path reads.** If perf gates this later, add `@BatchSize(size=20)` to the collections (one extra batched query) or switch to two `@EntityGraph` queries and zip in the service. Do NOT use `Set<>` — entity equals/hashCode pitfalls outweigh the saving.
9. `NutritionQueryService.getTargets(UUID userId): Optional<TargetsDto>` is the cross-module read-by-others method.

### `updateTargets`

10. `PUT /api/v1/nutrition/targets` accepts `UpdateTargetsRequest` (per LLD lines 404-413) carrying `expectedVersion`. Mismatch → 409 `OptimisticLockException` mapped to ProblemDetail (handled by `GlobalExceptionHandler`, no new handler needed).
11. On success: replace each child collection in-place via `replaceX(List<X>)` helpers (cascade + orphanRemoval handles delete + insert). Update root scalar fields. Bump `@Version`. Return updated `TargetsDto`.
12. **Audit-log: write one `NutritionTargetsAuditLog` row per field that actually changed** (compare old vs new; skip no-op fields). `actorUserId = actorUserId`, `actorKind = USER`, `fieldPath` is the dotted path (e.g., `"calorieTarget"`, `"micro.iron_mg.targetValue"`, `"perMeal.lunch.proteinG"`). For 01a, `sourceDirectiveId` is always null. Atomic with the targets write (same `@Transactional`).
13. **Goal-driven defaults are out of scope for 01a.** The LLD specifies `PUT /api/v1/nutrition/targets/goal` re-applies direction defaults to non-overridden macros via `GoalDefaultsResolver`. Defer to a later sub-ticket; 01a's `updateTargets` simply takes whatever directions the request carries and writes them through. The `userOverriddenDirections` column is added by the migration so 01b can layer the goal-defaults logic without re-migrating.

### `getTargetsAuditLog`

14. `GET /api/v1/nutrition/targets/audit-log?page=&size=` returns paginated `Page<NutritionTargetsAuditEntryDto>` newest-first. Spring `Pageable` with default size 20, max 100. Cookie-auth required.

### Cross-module facade + boundary

15. `NutritionModule.java` facade re-exports `NutritionQueryService` and `NutritionUpdateService` (interface only; no impl exposed).
16. Repositories package-private. `NutritionBoundaryTest` at `src/test/java/com/example/mealprep/nutrition/NutritionBoundaryTest.java` mirrors `AuthBoundaryTest`: classes outside `com.example.mealprep.nutrition..` must not depend on `com.example.mealprep.nutrition..domain.repository..`.

### Errors

17. New module-root `NutritionException extends RuntimeException`; subclass `NutritionTargetsNotFoundException` (404). `NutritionExceptionHandler` `@RestControllerAdvice` at `com.example.mealprep.nutrition.api`, **annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`**, maps the not-found exception to 404 ProblemDetail (`type = .../nutrition-targets-not-found`).
18. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler`. `MethodArgumentNotValidException` (validation failures) likewise.

### Events

19. `NutritionTargetsChangedEvent(UUID userId, UUID targetsId, Set<String> changedFieldPaths, UUID traceId, Instant occurredAt)` published `AFTER_COMMIT` after `updateTargets`. No listeners in 01a. Lives in `core.events.MealPrepEvent`-extending base if there's a sealed marker; otherwise plain record. (Check `core.events` for the sealed base; if absent, plain `record` is fine — auth events are plain records.)

## Database

```
src/main/resources/db/migration/V20260601600000__nutrition_create_targets.sql                  new
src/main/resources/db/migration/V20260601600100__nutrition_create_per_meal_distribution.sql    new
src/main/resources/db/migration/V20260601600200__nutrition_create_micro_targets.sql            new
src/main/resources/db/migration/V20260601600300__nutrition_create_eating_window_and_activity.sql new
src/main/resources/db/migration/V20260601600400__nutrition_create_targets_audit.sql            new
```

Schema mirrors LLD V20260502120000 / 120100 / 120200 / 120300 (`nutrition_eating_window` + `nutrition_activity_adjustment` ONLY — exclude `nutrition_daily_activity_log`, that's deferred to 01b) / 120800. The LLD timestamps are placeholders; renumber to `V20260601600xxx` so they sequence after preference (`V20260601300000+`) and household (`V20260601500xxx`).

**Do NOT create**: `nutrition_intake_day`, `nutrition_intake_slot`, `nutrition_intake_snack`, `nutrition_intake_audit`, `nutrition_food_mood_journal`, `nutrition_ingredient_mapping`, `nutrition_health_directives`, `nutrition_daily_activity_log`. Those are deferred. **Do NOT seed** the DRI defaults (`R__nutrition_seed_dri_defaults.sql`) — deferred.

## OpenAPI updates

### New `src/main/resources/openapi/paths/nutrition.yaml`

```yaml
targets:
  get:
    tags: [Nutrition]
    operationId: getNutritionTargets
    summary: Return the calling user's nutrition targets, or 404 if not yet initialised.
    security: [{ cookieAuth: [] }]
    responses:
      '200':
        description: Targets aggregate.
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/TargetsDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not initialised, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  put:
    tags: [Nutrition]
    operationId: updateNutritionTargets
    summary: Replace the calling user's nutrition targets (full replacement; expectedVersion required).
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/UpdateTargetsRequest' }
    responses:
      '200': { description: Targets updated, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/TargetsDto' } } } }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not initialised, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
targetsAuditLog:
  get:
    tags: [Nutrition]
    operationId: getNutritionTargetsAuditLog
    summary: Paginated audit log of changes to the calling user's targets.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200': { description: Audit-log page, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/NutritionTargetsAuditEntryDtoPage' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not initialised, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### New `src/main/resources/openapi/schemas/nutrition.yaml`

Schemas: `Goal`, `EnforcementDirection`, `MealSlot`, `ActivityLevel`, `ActorKind`, `CalorieTargetDto`, `MacroTargetDto`, `PerMealDistributionDto`, `MicroTargetDto`, `EatingWindowDto`, `ActivityAdjustmentDto`, `TargetsDto`, `UpdateTargetsRequest`, `NutritionTargetsAuditEntryDto`, `NutritionTargetsAuditEntryDtoPage` (Spring `Page<T>` shape: `{ content: [...], page: { number, size, totalElements, totalPages } }`).

Use the field shapes from [LLD lines 376-413](../../lld/nutrition.md). Drop the `Set<String> userOverriddenDirections` from the request shape if you prefer — it's a server-managed field that 01b will start touching.

### Two-region edit to `src/main/resources/openapi/openapi.yaml`

Append under `paths:`:

```yaml
  /api/v1/nutrition/targets:
    $ref: 'paths/nutrition.yaml#/targets'
  /api/v1/nutrition/targets/audit-log:
    $ref: 'paths/nutrition.yaml#/targetsAuditLog'
```

Append under `components.schemas:` (one ref line per schema):

```yaml
    Goal: { $ref: 'schemas/nutrition.yaml#/Goal' }
    EnforcementDirection: { $ref: 'schemas/nutrition.yaml#/EnforcementDirection' }
    MealSlot: { $ref: 'schemas/nutrition.yaml#/MealSlot' }
    ActivityLevel: { $ref: 'schemas/nutrition.yaml#/ActivityLevel' }
    ActorKind: { $ref: 'schemas/nutrition.yaml#/ActorKind' }
    CalorieTargetDto: { $ref: 'schemas/nutrition.yaml#/CalorieTargetDto' }
    MacroTargetDto: { $ref: 'schemas/nutrition.yaml#/MacroTargetDto' }
    PerMealDistributionDto: { $ref: 'schemas/nutrition.yaml#/PerMealDistributionDto' }
    MicroTargetDto: { $ref: 'schemas/nutrition.yaml#/MicroTargetDto' }
    EatingWindowDto: { $ref: 'schemas/nutrition.yaml#/EatingWindowDto' }
    ActivityAdjustmentDto: { $ref: 'schemas/nutrition.yaml#/ActivityAdjustmentDto' }
    TargetsDto: { $ref: 'schemas/nutrition.yaml#/TargetsDto' }
    UpdateTargetsRequest: { $ref: 'schemas/nutrition.yaml#/UpdateTargetsRequest' }
    NutritionTargetsAuditEntryDto: { $ref: 'schemas/nutrition.yaml#/NutritionTargetsAuditEntryDto' }
```

## Verbatim shape snippets

### Entity — JSONB pattern (mirrors `core/audit/domain/entity/DecisionLog.java`)

For the audit-log JSONB columns (`previous_value_json`, `new_value_json`):

```java
@Type(JsonBinaryType.class)
@Column(name = "previous_value_json", nullable = false, columnDefinition = "jsonb")
private JsonNode previousValueJson;
```

For `userOverriddenDirections` on the root, the LLD says `text[]`. The preference module hit the same wall — see `HardConstraints.java` lines 33-37 in this repo: **Hibernate's `text[]` mapping is brittle on Spring Boot 3.2.5 / hypersistence-utils-63; use `JsonBinaryType` with `List<String>` instead**. Apply the same workaround:

```java
@Type(JsonBinaryType.class)
@Column(name = "user_overridden_directions", nullable = false, columnDefinition = "jsonb")
private List<String> userOverriddenDirections;
```

Update the migration accordingly: `user_overridden_directions jsonb NOT NULL DEFAULT '[]'::jsonb` (NOT `text[]`).

### Repository — package-private (no multi-bag entity graph)

```java
interface NutritionTargetsRepository extends JpaRepository<NutritionTargets, UUID> {
  Optional<NutritionTargets> findByUserId(UUID userId);
  // Service must access children inside @Transactional to lazy-load them — see invariant 8.
}

interface NutritionTargetsAuditRepository extends JpaRepository<NutritionTargetsAuditLog, UUID> {
  Page<NutritionTargetsAuditLog> findByTargetsIdOrderByOccurredAtDesc(UUID id, Pageable p);
}
```

### Service interfaces

```java
public interface NutritionQueryService {
  Optional<TargetsDto> getTargets(UUID userId);
  Page<NutritionTargetsAuditEntryDto> getTargetsAuditLog(UUID userId, Pageable pageable);
}

public interface NutritionUpdateService {
  TargetsDto updateTargets(UUID userId, UpdateTargetsRequest request, UUID actorUserId);
}
```

The full LLD interfaces span 4 services × ~30 methods (intake, journal, calculation, floor-gate, directives) — **all out of scope here**.

## Edge-case checklist

- [ ] `GET /targets` for a user with no row → 404 `nutrition-targets-not-found` ProblemDetail
- [ ] `GET /targets` for an authenticated user with a row → 200, all four child collections present, no N+1 (verify with statement count or `@Sql` snapshot)
- [ ] `GET /targets` without cookie → 401
- [ ] `PUT /targets` with stale `expectedVersion` → 409 (`concurrent-update` ProblemDetail from `GlobalExceptionHandler`)
- [ ] `PUT /targets` replacing all four child collections cleanly via cascade + orphanRemoval (no leaked rows)
- [ ] `PUT /targets` writes one audit-log row per *changed* field; no row written for no-op fields
- [ ] `PUT /targets` writes the audit log atomically — if the targets save fails, no audit row leaks (same `@Transactional`)
- [ ] Audit-log row carries the full old/new JSON for the changed field
- [ ] `GET /targets/audit-log` paginated newest-first; default size 20, capped at 100
- [ ] Validation: `expectedVersion < 0` → 400; `daily_calorie_target < 0` → 400; oversize lists (`> 30 microTargets`, `> 4 perMealDistribution`) → 400 (matches `@Size` annotations on the request DTO)
- [ ] Authenticated request for user A cannot read or update user B's targets — `userId` resolved server-side, never accepted from request
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter)
- [ ] `NutritionBoundaryTest` passes — outside-module classes cannot import `nutrition.domain.repository`
- [ ] `NutritionTargetsChangedEvent` published exactly once after commit, with `changedFieldPaths` covering only changed fields

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601600000__nutrition_create_targets.sql
NEW   src/main/resources/db/migration/V20260601600100__nutrition_create_per_meal_distribution.sql
NEW   src/main/resources/db/migration/V20260601600200__nutrition_create_micro_targets.sql
NEW   src/main/resources/db/migration/V20260601600300__nutrition_create_eating_window_and_activity.sql
NEW   src/main/resources/db/migration/V20260601600400__nutrition_create_targets_audit.sql

NEW   src/main/java/com/example/mealprep/nutrition/NutritionModule.java
NEW   src/main/java/com/example/mealprep/nutrition/api/controller/TargetsController.java
NEW   src/main/java/com/example/mealprep/nutrition/api/NutritionExceptionHandler.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/TargetsDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/CalorieTargetDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/MacroTargetDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/PerMealDistributionDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/MicroTargetDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/EatingWindowDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/ActivityAdjustmentDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/UpdateTargetsRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/NutritionTargetsAuditEntryDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/mapper/TargetsMapper.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/NutritionTargets.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/PerMealDistributionEntry.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/MicroTarget.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/EatingWindow.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/ActivityAdjustment.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/NutritionTargetsAuditLog.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/Goal.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/EnforcementDirection.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/MealSlot.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/ActivityLevel.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/ActorKind.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/NutritionTargetsRepository.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/NutritionTargetsAuditRepository.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionQueryService.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionUpdateService.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java
NEW   src/main/java/com/example/mealprep/nutrition/event/NutritionTargetsChangedEvent.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/NutritionException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/NutritionTargetsNotFoundException.java

NEW   src/main/resources/openapi/paths/nutrition.yaml
NEW   src/main/resources/openapi/schemas/nutrition.yaml
MOD   src/main/resources/openapi/openapi.yaml                                                  (2 lines under paths:; ~14 lines under components.schemas:)

NEW   src/test/java/com/example/mealprep/nutrition/NutritionServiceImplTest.java
NEW   src/test/java/com/example/mealprep/nutrition/TargetsFlowIT.java
NEW   src/test/java/com/example/mealprep/nutrition/NutritionBoundaryTest.java
NEW   src/test/java/com/example/mealprep/nutrition/testdata/NutritionTestData.java
```

**Files this ticket does NOT modify** (cross-cutting; sibling Wave-2 tickets must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`

## Dependencies

- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Sibling tickets running in parallel** (Wave 2 round 1): `household-01a`, `provisions-01a`, `recipe-01a`. None should touch any nutrition file or any of the cross-cutting files listed above.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `NutritionExceptionHandler` annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`
- [ ] No regression on existing tests
- [ ] No N+1 on `getTargets` — verified via Hibernate statistics or single-statement count assertion in IT

## What's NOT in scope

- `IntakeDay`, `IntakeSlot`, `IntakeSnack`, `IntakeAuditLog`, all intake endpoints (`prefillFromPlan`, `confirmFromPlan`, `overrideIntakeFromFreeText`, `editIntakeManually`, `skipMeal`, `logSnack`, `removeSnack`) → **nutrition-01b**
- `DailyActivityLog` table + endpoints (`getDailyActivity`, `upsertDailyActivity`) → **nutrition-01b**
- `FoodMoodJournalEntry` + journal endpoints + `getJournalEntriesForFeedbackContext` → **nutrition-01c**
- `IngredientMapping` cache, `IngredientLookupRequest/Result`, USDA / OFF clients, `IngredientMappingPipeline` → **nutrition-01d**
- `HealthDirective` queue, inbound / accept / reject endpoints, `DirectiveSafetyGate`, `DirectiveApplier` → **nutrition-01e**
- `NutritionCalculationService` (recipe save-time + `RecipeEvolvedEvent` listener) → **nutrition-01f**
- `NutritionFloorGateService` for the planner → **nutrition-01g**
- `IntakeAggregator`, `WeeklyAggregateDto`, weekly-rollup endpoints, `DivergenceDetector` → **nutrition-01h**
- Goal-driven defaults (`PUT /api/v1/nutrition/targets/goal`) and `GoalDefaultsResolver` → later targets sub-ticket
- `initialiseTargets` auto-seed at user-creation (DRI defaults) → depends on `R__nutrition_seed_dri_defaults.sql` (deferred)
- `applyFeedback` from feedback module → depends on directives + intake (deferred)
- Custom validators (`@ValidEatingWindow`, `@ValidPerMealDistribution`, `@ValidActivityProfile`, `@ValidDirectiveInstruction`) — basic Jakarta annotations only in 01a; the four custom validators land with their respective sub-tickets

Squash-merge with: `feat(nutrition): 01a — targets aggregate + GET/PUT/audit-log endpoints + boundary test`
