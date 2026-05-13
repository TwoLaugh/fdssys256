# Ticket: planner — 01a Plan Data Model (entities + migrations + repositories + GET-by-id)

## Summary

Foundation ticket for the `planner` module. Lands the **normalised Option-B plan storage shape** (locked decision, LLD §Plan Storage Shape) — `Plan` aggregate root + `Day` + `MealSlot` + `ScheduledRecipe` + `ReoptSuggestion` — together with Flyway migrations `V20260507120000` through `V20260507120300`, JPA mappers, repositories (package-private), all module enums, the cross-module DTO records (`PlanDto`, `DayDto`, `MealSlotDto`, `ScheduledRecipeDto`, `ScoreBreakdownDocument`, `RollupSummaryDocument`, `DailyRollupDocument`, `WeeklyRollupDocument`, `ReoptSuggestionDto`), MapStruct mappers, the module's `PlannerException` root + `PlanNotFoundException` + `MealSlotNotFoundException` + `ReoptSuggestionNotFoundException`, the `PlannerExceptionHandler` `@RestControllerAdvice` annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`, `PlannerModule.java` facade, and exactly **one read endpoint** `GET /api/v1/plans/{planId}` so the slice compiles + has an end-to-end IT. Per `lld/planner.md` §Plan Storage Shape, §Database, §Entities, §DTOs, §Repositories, §REST Controllers.

This is the first planner ticket; the module package is currently empty.

**Defers** (every other planner concern is its own ticket):
- Lifecycle state machine + transition guards + supersession + revert → **planner-01b**
- Full read API (`getActivePlan`, `getPlanHistory`, `getPlansBetween`, `getPendingSuggestions`, `checkFeasibility`) → **planner-01c**
- BeamSearchEngine + HardFilterRunner + BeamPruner → **planner-01d**
- ScoringEngine + seven SubScoreCalculators + gates → **planner-01e**
- RollupBuilder (Stage B) → **planner-01f**
- StageCInvoker + StageCPickTask → **planner-01g**
- Phase2Augmenter + AugmentationVerifier + Phase2AugmentationTask → **planner-01h**
- MidWeekReoptCoordinator + PinningRules + ReoptScopeBuilder → **planner-01i**
- Remaining REST endpoints (`POST /generate`, accept/reject/abandon/revert, `MealSlotsController`, `markSlotState`, suggestions list/dismiss) → **planner-01j**
- Event listeners + materiality filters + suggestion lifecycle → **planner-01k**
- Decision-log integration + `AdminPlannerController` → **planner-01l**

01a's `GET /plans/{planId}` is the read-by-others contract — the entity hydration path is exercised end-to-end so the per-collection lazy-load pattern is validated against real Postgres before any other ticket builds on it.

## Behavioural spec

### Plan storage shape — Option B locked

1. Per LLD §Plan Storage Shape, the locked decision is **Option B (normalised)** — four tables: `planner_plans`, `planner_days`, `planner_meal_slots`, `planner_scheduled_recipes`. Option A (JSONB document) is **out of scope** — do NOT ship Option A's `plan_document` column or its migration. The LLD's "if the user picks A" branch is reference only.
2. `Plan` is the aggregate root with `@Version Long version`. Children (`Day`, `MealSlot`, `ScheduledRecipe`) share the parent's version — `markSlotState` (later, in 01j) will bump the parent via `OPTIMISTIC_FORCE_INCREMENT`. **No `@Version` on `Day` / `MealSlot` / `ScheduledRecipe`**. `ReoptSuggestion` is a standalone aggregate with its own `@Version`.

### Entities

3. `Plan` per [LLD §V20260507120000 lines 165-207](../../lld/planner.md) and §Entities row 1. Fields: `id UUID, householdId UUID, weekStartDate LocalDate, generation int, replacesPlanId UUID nullable, status PlanStatus, triggerKind TriggerKind, triggerEventId UUID nullable, qualityWarning boolean default false, coldStart boolean default false, aiAugmented boolean default false, traceId UUID, decisionId UUID, acceptedAt Instant nullable, completedAt Instant nullable, rejectedAt Instant nullable, rejectedReason String(255) nullable, abandonedAt Instant nullable, abandonedReason String(255) nullable, scoreBreakdown ScoreBreakdownDocument (JSONB), rollupSummary RollupSummaryDocument (JSONB), @Version Long version, @CreationTimestamp createdAt Instant, @UpdateTimestamp updatedAt Instant`. Owns `@OneToMany(mappedBy="plan", cascade=ALL, orphanRemoval=true, fetch=LAZY) List<Day> days`.
4. `Day` per LLD §V20260507120100 and §Entities row 2. Fields: `id UUID, plan Plan (@ManyToOne LAZY, @JoinColumn name="plan_id"), onDate LocalDate, notes String(255) nullable`. Owns `@OneToMany(mappedBy="day", cascade=ALL, orphanRemoval=true, fetch=LAZY) List<MealSlot> slots`. UNIQUE `(plan_id, on_date)`.
5. `MealSlot` per LLD §V20260507120100 and §Entities row 3. Fields: `id UUID, day Day (@ManyToOne LAZY, @JoinColumn name="day_id"), plan Plan (@ManyToOne LAZY, @JoinColumn name="plan_id" — denormalised for index hits), slotIndex int, kind SlotKind, label String(64), timeBudgetMin int, shared boolean, eaters List<UUID> (UUID[] mapped via `@Type(ListArrayType.class)` from hypersistence-utils), state SlotState default PLANNED, pinnedReason PinnedReason nullable`. Owns `@OneToOne(mappedBy="slot", cascade=ALL, orphanRemoval=true, fetch=LAZY, optional=true) ScheduledRecipe scheduledRecipe`. UNIQUE `(day_id, slot_index)`.
6. `ScheduledRecipe` per LLD §V20260507120100 and §Entities row 4. Fields: `id UUID, slot MealSlot (@OneToOne LAZY, @JoinColumn name="slot_id" UNIQUE), recipeId UUID (soft FK), recipeVersionId UUID (soft FK), recipeBranchId UUID (soft FK), servings int, batchCookSessionId UUID nullable, augmentationNotes String(512) nullable, augmentationSource AugmentationSource nullable, phase2Addition boolean default false`. Soft FKs to `recipes`, `recipe_versions`, `recipe_branches` are **deliberately not enforced at DB level** per LLD note (cross-module ordering).
7. `ReoptSuggestion` per LLD §V20260507120200 and §Entities row 5. Fields: `id UUID, householdId UUID, weekStartDate LocalDate, planId UUID (FK to planner_plans), triggerKind ReoptTriggerKind, triggerEventId UUID nullable, affectedSlotIds List<UUID> (UUID[]), summary String(255), status ReoptStatus default PENDING, expiresAt Instant nullable, @CreationTimestamp createdAt Instant, resolvedAt Instant nullable, @Version Long version`.
8. `ScoreBreakdownDocument` JSONB record per LLD §DTOs. Carried on `Plan.scoreBreakdown` via `@Type(JsonBinaryType.class)`. Fields: `BigDecimal preference, BigDecimal nutrition, BigDecimal cost, BigDecimal variety, BigDecimal time, BigDecimal batch, BigDecimal provisions, BigDecimal composite, boolean nutritionFloorGatePassed, boolean varietyGatePassed, String weightSchemeVersion`. **01a treats this as an opaque carrier** — actual values get populated by 01e (scoring); 01a just needs the JSONB round-trip working for persistence.
9. `RollupSummaryDocument` JSONB record per LLD §DTOs. Carried on `Plan.rollupSummary`. Fields: `List<DailyRollupDocument> daily, WeeklyRollupDocument weekly`. **01a treats this as an opaque carrier** — populated by 01f (rollup builder); 01a only needs the JSONB round-trip.
10. `DailyRollupDocument` record per LLD §DTOs. JSON-only (not an entity). Fields: `LocalDate date, int kcal, BigDecimal proteinG, BigDecimal fatG, BigDecimal carbsG, BigDecimal fibreG, BigDecimal costGbp, int totalTimeMin, List<String> violations`.
11. `WeeklyRollupDocument` record per LLD §DTOs. JSON-only. Fields: `int kcalTotal, BigDecimal proteinAvgG, BigDecimal fatAvgG, BigDecimal carbsAvgG, BigDecimal costEstimateGbp, BigDecimal costConfidence, int staleIngredientCount, BigDecimal varietyIndex, int batchCookSessions, List<String> constraintViolations`.

### Enums

12. `PlanStatus` (`DRAFT, GENERATED, ACTIVE, SUPERSEDED, COMPLETED, REJECTED, ABANDONED`). All seven values per LLD §Entities. **01a defines the enum but only uses `GENERATED` and `ACTIVE` for fixture data** — the other values are reached via 01b's state machine.
13. `SlotKind` — re-exported from `core.types.SlotKind` per LLD §Entities. **Verify `core.types.SlotKind` exists** (from the `core` module wave 1 ticket); if not, **add it to `core.types`** under a separate `chore: add SlotKind to core.types` if it's missing. Per the recipe / nutrition modules' pattern, `SlotKind` likely already exists. If it does, just import; if it doesn't, this ticket adds it (one new file, no new module).
14. `SlotState` (`PLANNED, COOKING, COOKED, EATEN, SKIPPED`). New in `planner.domain.entity` (or `core.types` if the user prefers — defaults to planner local since it's only used inside the planner aggregate).
15. `PinnedReason` (`EATEN, COOKED, COOKING, SKIPPED, USER_PINNED`). New in `planner.domain.entity`. Nullable on `MealSlot` — null when slot is regenerable.
16. `TriggerKind` (`USER_INITIATED, SCHEDULED_WEEKLY, MID_WEEK_REOPT`). New in `planner.domain.entity`. Used on the `Plan` row to record what produced it.
17. `ReoptTriggerKind` (`PROVISIONS, NUTRITION, PREFERENCE, HOUSEHOLD_SETTINGS, USER`). New. Used on `ReoptSuggestion`.
18. `ReoptStatus` (`PENDING, ACCEPTED, DISMISSED, EXPIRED`). New. Used on `ReoptSuggestion.status`.
19. `AugmentationSource` (`LLM, USER`). New. Used on `ScheduledRecipe.augmentationSource`.

### DTOs (the cross-module surface)

20. `PlanDto`, `DayDto`, `MealSlotDto`, `ScheduledRecipeDto` records per LLD §DTOs lines 349-377. All Java records. **Read by every wave-3 consumer (notification, grocery, frontend)** — so this is the locked public surface.
21. `ScoreBreakdownDocument`, `RollupSummaryDocument`, `DailyRollupDocument`, `WeeklyRollupDocument` records per LLD §DTOs lines 379-405. Both Java records AND JSONB carriers — same record reused for both purposes. Jackson handles `BigDecimal` round-trip natively.
22. `ReoptSuggestionDto` record per LLD §DTOs lines 437-442. Read by 01c (list endpoint) and 01k (listeners publish events that reference it); included here so 01k doesn't have to add it later.
23. Request records carried by this ticket but **never used in 01a's controller**: none. 01a's only endpoint is `GET /plans/{planId}` which is path-param only. **Defer** `GeneratePlanRequest`, `ReoptRequest`, `AcceptPlanRequest`, `RejectPlanRequest`, `AbandonPlanRequest`, `RevertToPlanRequest`, `MarkSlotStateRequest`, `FeasibilityCheckResultDto`, `ConstraintConflictDto`, `ResolutionOptionDto`, `ConflictType` — all to 01b/01c/01j.

### Mappers

24. MapStruct interfaces per LLD §Mappers: `PlanMapper`, `DayMapper`, `MealSlotMapper`, `ScheduledRecipeMapper`, `ReoptSuggestionMapper`. All `@Mapper(componentModel = "spring")`. Nested mappers wired via `uses = { ... }`. Mapper applies explicit `Comparator` ordering when building child collections: `days` by `onDate ASC`, `slots` by `slotIndex ASC`. ScheduledRecipe is 1:1 with slot — no list ordering needed.

### Repositories

25. Package-private interfaces (no `public` modifier) per LLD §Repositories. **01a ships only the methods needed by `getPlanById(planId)` + the migration validation IT**:
    - `PlanRepository`: `Optional<Plan> findWithDaysById(UUID id)` (single — used by GET endpoint). **Do NOT use `@EntityGraph(attributePaths = {"days", "days.slots", "days.slots.scheduledRecipe"})`** — three `@OneToMany List<>` collections trigger `MultipleBagFetchException` on Hibernate 6. Same trap as recipe-01a's RecipeVersion. **Pattern**: load `Plan` plain (Spring Data's default `findById`), then inside `@Transactional(readOnly = true)` touch `plan.getDays()` to lazy-load days, iterate to lazy-load each day's `slots`, iterate to lazy-load each slot's `scheduledRecipe`. The mapper then converts in-memory. Cost: ~1 + N + N×M SELECTs but no MultipleBagFetchException. The other plan-detail repository methods listed in the LLD §Repositories block (`findWithDaysByIdIn`, `findWithDaysByHouseholdIdAndWeekStartDateAndStatus`, etc.) **defer to 01c** which owns the full read API. 01a's repo file ships only `findById`, `findFirstByHouseholdIdAndWeekStartDate` (used by an internal test helper for fixture asserts), and `int countByHouseholdIdAndWeekStartDate` (one-line existence check used in fixture asserts).
    - `DayRepository`: empty interface (Spring Data minimum). Children of `Plan`; queried via the aggregate.
    - `MealSlotRepository`: empty interface for 01a. **Defer** `findByIdAndPlanId`, `findAllByPlanIdAndStateIn`, `findAllByPlanIdOrderByDayOnDateAscSlotIndexAsc` to 01j (slot-state controller) and 01i (re-opt).
    - `ScheduledRecipeRepository`: empty interface for 01a. **Defer** `existsActiveScheduleForRecipe` to recipe-deletion guard ticket (out of scope for planner entirely; lives in recipe module).
    - `ReoptSuggestionRepository`: `Optional<ReoptSuggestion> findByHouseholdIdAndWeekStartDateAndTriggerEventId(UUID, LocalDate, UUID)` — needed by 01k's idempotency check, included here so 01k doesn't modify the repo. Other methods (`findByHouseholdIdAndStatusOrderByCreatedAtDesc Page`, `findAllByStatusAndExpiresAtBefore List`) defer to 01c/01k.

### Service interfaces (01a subset only)

26. `PlanQueryService` interface declared per LLD §Service Interfaces lines 564-579 — **but in 01a it carries exactly ONE method**: `Optional<PlanDto> getPlanById(UUID planId)`. The remaining methods (`getActivePlan`, `getPlanHistory`, `getPlansBetween`, `getPlansByIds`, `getPendingSuggestions`, `getSuggestion`, `checkFeasibility`) are declared as JavaDoc TODO `// Layered by 01c, 01j` comments inside the interface file so the impl evolves cleanly. **Worth user review**: alternative is to declare the full interface and leave methods unimplemented (throws `UnsupportedOperationException`). Rejected because that fails ArchUnit's "no abstract methods left undefined" rule and pollutes the surface. Real method declarations land with their implementations.
27. `PlannerService` interface **not declared** in 01a. Lands with 01j (write controller).
28. `PlannerServiceImpl` (package-private impl in `domain/service/internal/`) implements `PlanQueryService` only in 01a. Constructor takes `PlanRepository`, `PlanMapper`. Method body: `findById → if present, touch lazy children inside @Transactional(readOnly=true) → return mapper.toDto`.

### `GET /api/v1/plans/{planId}` endpoint

29. New `PlansController` under `planner/api/controller/`. Annotated `@RestController`, `@RequestMapping("/api/v1/plans")`, `@Tag(name = "Plans")`. **One handler in 01a**: `@GetMapping("/{planId}") public ResponseEntity<PlanDto> getPlan(@PathVariable UUID planId)`. Returns 200 with hydrated `PlanDto` or 404 `PlanNotFoundException`.
30. Authentication: `security: [{ cookieAuth: [] }]` per the project's pattern. Any authenticated caller can read any plan in 01a — household authorisation rules **defer to a follow-up ticket** (LLD §Out of Scope item: "Authorisation rules for who in a household can acceptPlan / revertToPlan / abandonPlan ... for v1 any household member can accept/reject"; the same v1 stance applies to reads).
31. **No N+1 on the GET path** — verify via Hibernate stats counter in the IT. Expected statement count: 1 (Plan) + 1 (Days collection lazy-load) + N (per-day slots collection) + M (per-slot scheduledRecipe). For a 7-day × 3-slot plan that's 1 + 1 + 7 + 21 = 30 statements; bounded and predictable. The full `@EntityGraph` path with the `MultipleBagFetchException` workaround (separate queries) would be a 4-query plan; either pattern is acceptable. **01a uses the lazy-load-inside-@Transactional pattern** for consistency with recipe-01a's precedent.

### Errors

32. New module-root `PlannerException extends MealPrepException` (or `extends RuntimeException` if `MealPrepException` doesn't exist yet — verify against `core` module).
33. New exceptions in 01a (defined; only one thrown in 01a's controller): `PlanNotFoundException` (404, `type = .../plan-not-found`), `MealSlotNotFoundException` (404, `type = .../meal-slot-not-found`), `ReoptSuggestionNotFoundException` (404, `type = .../reopt-suggestion-not-found`). The remaining LLD-listed exceptions (`InvalidPlanStateTransitionException`, `InvalidSlotStateTransitionException`, `ConcurrentGenerationInProgressException`, `PlanInfeasibleException`, `RevertTargetNotInHistoryException`) defer to 01b/01j.
34. New `PlannerExceptionHandler` `@RestControllerAdvice` under `planner/api/`, **annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`** (per the gotchas catalogue — multi-advice ordering trap). Maps each 01a exception to ProblemDetail with the correct `type` URI. **Do NOT modify `config/GlobalExceptionHandler.java`** — module-specific exceptions live in the module's own advice.

### Cross-module facade + boundary

35. `PlannerModule.java` at module root. Re-exports `PlanQueryService` (interface only; 01a subset). One-line view of the public surface per the style guide.
36. **`PlannerBoundaryTest`** at `src/test/java/com/example/mealprep/planner/PlannerBoundaryTest.java`: ArchUnit rule that classes outside `com.example.mealprep.planner..` must not depend on `com.example.mealprep.planner..domain.repository..`. Mirrors the recipe / nutrition pattern.

## Database

```
src/main/resources/db/migration/V20260507120000__planner_create_plans.sql                                new
src/main/resources/db/migration/V20260507120100__planner_create_days_slots_recipes.sql                  new
src/main/resources/db/migration/V20260507120200__planner_create_reopt_suggestions.sql                   new
src/main/resources/db/migration/V20260507120300__planner_create_unique_active_constraint.sql            new
```

Schemas mirror LLD §Database lines 154-298 verbatim — **Option B (normalised), Option A out of scope**.

### `V20260507120000` — `planner_plans`

Exactly as LLD lines 165-207. **Verify**:
- `score_breakdown jsonb NOT NULL` and `rollup_summary jsonb NOT NULL` — JSONB columns with default `'{}'::jsonb` to satisfy `NOT NULL` for fresh inserts. **Reason for NOT NULL**: every plan has a score/rollup by construction; nullable carries no signal.
- Indexes: `idx_planner_plans_household_week_status (household_id, week_start_date, status)`, `idx_planner_plans_household_week_gen (household_id, week_start_date, generation DESC)`, `idx_planner_plans_household_range (household_id, week_start_date)`, `idx_planner_plans_trace (trace_id)` — all per LLD lines 194-207.

### `V20260507120100` — `planner_days`, `planner_meal_slots`, `planner_scheduled_recipes`

Exactly as LLD lines 213-260. **Verify**:
- `planner_meal_slots.eaters uuid[] NOT NULL DEFAULT '{}'` — Postgres array type, mapped Java-side via `@Type(ListArrayType.class)` from hypersistence-utils. **Sticky trap**: `text[]`/`uuid[]` is brittle in Hibernate per round-1/4 lessons, BUT the `ListArrayType` from hypersistence-utils handles UUID arrays cleanly. Recipe-01a's `text[] → jsonb List<String>` workaround was specifically because `text[]` had no clean hypersistence type; `uuid[]` does. **Use `ListArrayType.class`**, not the JSONB workaround.
- Indexes: `idx_planner_days_plan_date (plan_id, on_date)`, `idx_planner_meal_slots_plan_state (plan_id, state)`, `idx_planner_meal_slots_day (day_id)`, `idx_planner_scheduled_recipes_batch (batch_cook_session_id) WHERE batch_cook_session_id IS NOT NULL`, `idx_planner_scheduled_recipes_recipe (recipe_id)` per LLD lines 222-260.
- **No DB-level FKs to other modules** (`recipes`, `recipe_versions`, `recipe_branches`) — soft refs only, per the LLD's explicit cross-module FK avoidance note (lines 261-263).

### `V20260507120200` — `planner_reopt_suggestions`

Exactly as LLD lines 267-284. `affected_slot_ids uuid[] NOT NULL DEFAULT '{}'` — same `ListArrayType` mapping. Index `idx_planner_reopt_pending (household_id, status, created_at DESC)`.

### `V20260507120300` — partial unique index for ACTIVE plan invariant

Exactly as LLD lines 293-296:

```sql
CREATE UNIQUE INDEX uq_planner_plans_active_per_household_week
    ON planner_plans (household_id, week_start_date)
    WHERE status = 'active';
```

**Lowercase `'active'`** — JPA persists `@Enumerated(EnumType.STRING)` lowercase by default ONLY IF Hibernate `physical-strategy` casing is configured that way. Spring Boot 3.2.5 default is UPPERCASE (`'ACTIVE'`). **01a fixes the index value to match Hibernate's persistence form**:

```sql
CREATE UNIQUE INDEX uq_planner_plans_active_per_household_week
    ON planner_plans (household_id, week_start_date)
    WHERE status = 'ACTIVE';
```

**Worth user review**: the LLD's `'active'` lowercase suggests the LLD author had a different persistence strategy in mind. 01a's `'ACTIVE'` matches the project's Hibernate default. The IT verifies a concurrent attempt to insert a second `ACTIVE` row fires the constraint.

## OpenAPI updates

### New `src/main/resources/openapi/paths/planner.yaml`

```yaml
planById:
  get:
    tags: [Plans]
    operationId: getPlan
    summary: 'Fetch a plan by id; returns the full hydrated aggregate (days + slots + scheduled recipes).'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: planId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: 'Plan with all child collections populated.'
        content:
          application/json:
            schema: { $ref: '../schemas/planner.yaml#/PlanDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Plan not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### New `src/main/resources/openapi/schemas/planner.yaml`

Schemas: `PlanStatus`, `TriggerKind`, `SlotKind` (defer if `core.yaml` already publishes it; reference), `SlotState`, `PinnedReason`, `AugmentationSource`, `ReoptTriggerKind`, `ReoptStatus`, `PlanDto`, `DayDto`, `MealSlotDto`, `ScheduledRecipeDto`, `ScoreBreakdownDocument`, `RollupSummaryDocument`, `DailyRollupDocument`, `WeeklyRollupDocument`, `ReoptSuggestionDto`. **Drop / defer**: every Request DTO (`GeneratePlanRequest` etc.), `FeasibilityCheckResultDto`, `ConstraintConflictDto`, `ResolutionOptionDto`, `ConflictType`.

**Apply the locked-in gotchas**:
- `ScheduledRecipeDto` is **nullable** on `MealSlotDto.scheduledRecipe` (slot may be empty for eating-out / fasting). **Do NOT use `$ref + nullable: true`** — that's the sticky round-1/4/6 trap. **Inline the object schema** under `MealSlotDto.properties.scheduledRecipe` with `nullable: true` directly. Even though `ScheduledRecipeDto` is also published as a named schema (for the future PATCH/POST endpoints in 01j), the parent's `scheduledRecipe` field inlines the full structure.
- `pinnedReason` is nullable on `MealSlotDto`. **Inline** as `{ type: string, enum: [EATEN, COOKED, COOKING, SKIPPED, USER_PINNED], nullable: true }`. Do NOT `$ref` + `nullable`.
- `replacesPlanId`, `triggerEventId`, `acceptedAt`, `completedAt`, `rejectedAt`, `rejectedReason`, `abandonedAt`, `abandonedReason` are all nullable on `PlanDto`. For scalar fields (`string format: uuid`, `string`, `string format: date-time`) inline `nullable: true` directly. No `$ref` involved so no trap; just include.
- Every YAML description string containing `,` `:` `'` is single-quoted (round-4 lesson) — e.g. `'Plan with all child collections populated.'`.
- `weightSchemeVersion` on `ScoreBreakdownDocument` is a plain string (e.g. `"v1-uniform"`); no enum since the value is open-ended (future versions land freely).

### Two-region edit to `src/main/resources/openapi/openapi.yaml`

Append under `paths:` in a **new `# ===== planner =====`** block (no prior planner block exists):

```yaml
  # ===== planner =====
  /api/v1/plans/{planId}:
    $ref: 'paths/planner.yaml#/planById'
```

Append under `components.schemas:` in the new `# planner` block (one ref line per schema; ~17 lines).

## Verbatim shape snippets

### `Plan` entity — JSONB columns + child collection (List<>)

```java
@Entity
@Table(name = "planner_plans")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Plan {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;
  @Column(name = "household_id", nullable = false) private UUID householdId;
  @Column(name = "week_start_date", nullable = false) private LocalDate weekStartDate;
  @Column(name = "generation", nullable = false) private int generation;
  @Column(name = "replaces_plan_id") private UUID replacesPlanId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private PlanStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_kind", nullable = false, length = 32) private TriggerKind triggerKind;

  @Column(name = "trigger_event_id") private UUID triggerEventId;
  @Column(name = "quality_warning", nullable = false) private boolean qualityWarning;
  @Column(name = "cold_start", nullable = false) private boolean coldStart;
  @Column(name = "ai_augmented", nullable = false) private boolean aiAugmented;
  @Column(name = "trace_id", nullable = false) private UUID traceId;
  @Column(name = "decision_id", nullable = false) private UUID decisionId;
  @Column(name = "accepted_at") private Instant acceptedAt;
  @Column(name = "completed_at") private Instant completedAt;
  @Column(name = "rejected_at") private Instant rejectedAt;
  @Column(name = "rejected_reason", length = 255) private String rejectedReason;
  @Column(name = "abandoned_at") private Instant abandonedAt;
  @Column(name = "abandoned_reason", length = 255) private String abandonedReason;

  @Type(JsonBinaryType.class)
  @Column(name = "score_breakdown", nullable = false, columnDefinition = "jsonb")
  private ScoreBreakdownDocument scoreBreakdown;

  @Type(JsonBinaryType.class)
  @Column(name = "rollup_summary", nullable = false, columnDefinition = "jsonb")
  private RollupSummaryDocument rollupSummary;

  @Version @Column(name = "version", nullable = false) private long version;
  @CreationTimestamp @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
  @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  // Children — List<> with NO @EntityGraph (multi-bag fetch trap). Service touches inside @Transactional.
  @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  @Builder.Default
  private List<Day> days = new ArrayList<>();
}
```

### `MealSlot.eaters` UUID array

```java
@Type(ListArrayType.class)
@Column(name = "eaters", nullable = false, columnDefinition = "uuid[]")
@Builder.Default
private List<UUID> eaters = new ArrayList<>();
```

### `PlanRepository` — package-private, no `@EntityGraph`

```java
interface PlanRepository extends JpaRepository<Plan, UUID> {
  // No @EntityGraph — three @OneToMany List<> chain (days → slots[, scheduledRecipe]) triggers
  // MultipleBagFetchException on Hibernate 6. Service touches lazy children inside @Transactional.
  // findById is inherited from JpaRepository.

  Optional<Plan> findFirstByHouseholdIdAndWeekStartDate(UUID householdId, LocalDate weekStartDate);

  int countByHouseholdIdAndWeekStartDate(UUID householdId, LocalDate weekStartDate);
}
```

### Service skeleton — lazy-load pattern

```java
@Override
@Transactional(readOnly = true)
public Optional<PlanDto> getPlanById(UUID planId) {
  return planRepository.findById(planId)
      .map(plan -> {
        plan.getDays().forEach(day -> {
          day.getSlots().forEach(slot -> {
            // Touch the optional @OneToOne to force lazy load while inside the tx.
            slot.getScheduledRecipe();
          });
        });
        return planMapper.toDto(plan);
      });
}
```

## Edge-case checklist

- [ ] All 4 Flyway migrations run cleanly on a fresh Postgres 16 (Testcontainers) — `spring.jpa.hibernate.ddl-auto = validate` passes against the entity mapping
- [ ] `planner_plans.score_breakdown` and `rollup_summary` are JSONB and accept the `ScoreBreakdownDocument` / `RollupSummaryDocument` records via Jackson round-trip (write then read, byte-identical)
- [ ] `planner_meal_slots.eaters uuid[]` round-trips a `List<UUID>` of 0, 1, 5 elements via `ListArrayType`
- [ ] `planner_reopt_suggestions.affected_slot_ids uuid[]` round-trips similarly
- [ ] Partial unique index `uq_planner_plans_active_per_household_week` fires on a second `ACTIVE` insert for the same `(household, week)` — verified via direct JDBC insert in IT
- [ ] `GET /plans/{planId}` happy path: returns 200 + hydrated `PlanDto` with days ordered by `onDate`, slots ordered by `slotIndex`, scheduledRecipe present or null per fixture
- [ ] `GET /plans/{planId}` for a non-existent id → 404 `plan-not-found` ProblemDetail
- [ ] `GET /plans/{planId}` without cookie → 401
- [ ] No `MultipleBagFetchException` when fetching a plan with 7 days × 3 slots × scheduledRecipe — verified via IT that asserts the SELECT count is bounded and the response body is fully populated
- [ ] Mapper applies explicit ordering — verified by inserting days out of order and slots out of order in the fixture, asserting response order is `onDate` / `slotIndex` ascending
- [ ] `Plan.@Version` increments on UPDATE — verified via direct JDBC + JPA load roundtrip
- [ ] `cascade = ALL, orphanRemoval = true` works: deleting the `Plan` row cascades through `Day` → `MealSlot` → `ScheduledRecipe`; deleting a `Day` from `plan.getDays()` and saving cascades to its slots+scheduled recipes
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in the IT)
- [ ] `PlannerBoundaryTest` passes — outside-module classes cannot import `planner.domain.repository`
- [ ] `PlannerExceptionHandler` annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`
- [ ] `MealSlotDto.scheduledRecipe` is **inlined as nullable object** in the OpenAPI schema (NOT `$ref + nullable: true`)
- [ ] `MealSlotDto.pinnedReason` is **inlined as nullable enum**
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260507120000__planner_create_plans.sql
NEW   src/main/resources/db/migration/V20260507120100__planner_create_days_slots_recipes.sql
NEW   src/main/resources/db/migration/V20260507120200__planner_create_reopt_suggestions.sql
NEW   src/main/resources/db/migration/V20260507120300__planner_create_unique_active_constraint.sql

NEW   src/main/java/com/example/mealprep/planner/PlannerModule.java
NEW   src/main/java/com/example/mealprep/planner/api/controller/PlansController.java
NEW   src/main/java/com/example/mealprep/planner/api/PlannerExceptionHandler.java

NEW   src/main/java/com/example/mealprep/planner/api/dto/PlanDto.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/DayDto.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/MealSlotDto.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/ScheduledRecipeDto.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/ScoreBreakdownDocument.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/RollupSummaryDocument.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/DailyRollupDocument.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/WeeklyRollupDocument.java
NEW   src/main/java/com/example/mealprep/planner/api/dto/ReoptSuggestionDto.java

NEW   src/main/java/com/example/mealprep/planner/api/mapper/PlanMapper.java
NEW   src/main/java/com/example/mealprep/planner/api/mapper/DayMapper.java
NEW   src/main/java/com/example/mealprep/planner/api/mapper/MealSlotMapper.java
NEW   src/main/java/com/example/mealprep/planner/api/mapper/ScheduledRecipeMapper.java
NEW   src/main/java/com/example/mealprep/planner/api/mapper/ReoptSuggestionMapper.java

NEW   src/main/java/com/example/mealprep/planner/domain/entity/Plan.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/Day.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/MealSlot.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/ScheduledRecipe.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/ReoptSuggestion.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/PlanStatus.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/SlotState.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/PinnedReason.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/TriggerKind.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/ReoptTriggerKind.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/ReoptStatus.java
NEW   src/main/java/com/example/mealprep/planner/domain/entity/AugmentationSource.java

NEW   src/main/java/com/example/mealprep/planner/domain/repository/PlanRepository.java
NEW   src/main/java/com/example/mealprep/planner/domain/repository/DayRepository.java
NEW   src/main/java/com/example/mealprep/planner/domain/repository/MealSlotRepository.java
NEW   src/main/java/com/example/mealprep/planner/domain/repository/ScheduledRecipeRepository.java
NEW   src/main/java/com/example/mealprep/planner/domain/repository/ReoptSuggestionRepository.java

NEW   src/main/java/com/example/mealprep/planner/domain/service/PlanQueryService.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/PlannerServiceImpl.java

NEW   src/main/java/com/example/mealprep/planner/exception/PlannerException.java
NEW   src/main/java/com/example/mealprep/planner/exception/PlanNotFoundException.java
NEW   src/main/java/com/example/mealprep/planner/exception/MealSlotNotFoundException.java
NEW   src/main/java/com/example/mealprep/planner/exception/ReoptSuggestionNotFoundException.java

NEW   src/main/resources/openapi/paths/planner.yaml
NEW   src/main/resources/openapi/schemas/planner.yaml
MOD   src/main/resources/openapi/openapi.yaml                                                  (new `# planner` block: 1 line under paths, ~17 lines under components.schemas)

NEW   src/test/java/com/example/mealprep/planner/PlannerServiceImplTest.java
NEW   src/test/java/com/example/mealprep/planner/PlannerFlowIT.java
NEW   src/test/java/com/example/mealprep/planner/FlywayMigrationIT.java
NEW   src/test/java/com/example/mealprep/planner/PlannerBoundaryTest.java
NEW   src/test/java/com/example/mealprep/planner/testdata/PlanTestData.java
```

Count: ~50 files but most are 1-line records/enums/empty repo interfaces. The actual logic lives in `PlannerServiceImpl` (one method body) + `PlansController` (one handler) + migrations + mappers. Estimated agent runtime 40-55 min.

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module-specific exceptions land in `PlannerExceptionHandler` only.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module rule lives in `PlannerBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, migrations, entities — none touched.

## Gotchas to bake in

Embed these inline in the implementer's mind — they're all locked-in traps from past wave-2 rounds:

1. **`MultipleBagFetchException`**: `@EntityGraph(attributePaths = {"days", "days.slots", "days.slots.scheduledRecipe"})` over three `@OneToMany List<>` collections fails on Hibernate 6. **Do NOT use `@EntityGraph`** here; use the lazy-touch-inside-`@Transactional` pattern in the service. Recipe-01a and preference-01a both shipped this fix.
2. **Multi-advice ordering**: every `@RestControllerAdvice` MUST have explicit `@Order`. `PlannerExceptionHandler` → `@Order(Ordered.HIGHEST_PRECEDENCE)`. Without it, the project-wide `GlobalExceptionHandler` `@ExceptionHandler(Exception.class)` catch-all swallows `PlanNotFoundException` and you ship 500s.
3. **OpenAPI 3.0 `$ref + nullable: true` is silently ignored**. For `MealSlotDto.scheduledRecipe` (nullable nested object) **INLINE the entire `ScheduledRecipeDto` shape** under `MealSlotDto.properties.scheduledRecipe` with `nullable: true`. Do NOT `$ref` it. Even if the named `ScheduledRecipeDto` schema exists for documentation reuse elsewhere, inline here. Trap has hit rounds 1, 4, 6.
4. **`pinnedReason` nullable enum**: inline `{ type: string, enum: [...], nullable: true }` — never `$ref + nullable`.
5. **YAML description strings with `,` `:` `'`**: single-quote them. `'Plan with all child collections populated.'` not `Plan with all child collections populated.`. Otherwise swagger-cli rejects the malformed Response Object.
6. **Partial unique index value-casing**: `WHERE status = 'ACTIVE'` (UPPERCASE) matches Hibernate's default `@Enumerated(EnumType.STRING)` persistence form on Spring Boot 3.2.5. The LLD's `'active'` lowercase is a doc-side typo; index value MUST match what Hibernate writes.
7. **`@Version` flush timing**: if a future ticket's controller returns the post-save DTO, use `saveAndFlush()` — `save()` defers the `@Version` increment until commit, which is after the controller returns. Not exercised in 01a (no writes) but the pattern is locked in.
8. **`LazyInitializationException`**: if you let the controller's return path serialise child collections OUTSIDE the service's `@Transactional`, the mapper trips `LazyInitializationException` because the session is closed. **Map to DTO inside the service method's `@Transactional` boundary** so all lazy-load happens while the session is open. The controller receives a fully-materialised DTO.
9. **`text[]` is brittle**; **`uuid[]` via `ListArrayType` is fine**. The recipe-01a `text[] → jsonb List<String>` workaround does NOT apply to `eaters uuid[]` here — `ListArrayType.class` handles UUID arrays cleanly.
10. **`@MockBean` on multi-interface impl**: `PlannerServiceImpl` will eventually implement both `PlanQueryService` AND `PlannerService` (latter lands in 01j). 01a's impl declares only `PlanQueryService` so the trap doesn't bite yet — but the implementer should anticipate: when 01j adds `PlannerService`, any IT that `@MockBean private PlanQueryService ...` will ALSO need `@MockBean private PlannerService ...` or the bean won't satisfy both interfaces. Quick check before any future `@MockBean`: `grep "implements" .../PlannerServiceImpl.java`.
11. **JSONB-record round-trip**: `@Type(JsonBinaryType.class)` on a record-typed field works with `hypersistence-utils-hibernate-63`, BUT Jackson needs the record's constructor visible. If the record is in a different package and the agent makes it package-private by accident, Jackson can't construct it. **Keep DTO records `public`** — they're public by record-default but verify after any refactor.

## Dependencies

- **Hard dependency**: `project-setup` (merged) — base pom, OpenAPI base YAML, ArchUnit base rules, hypersistence-utils dep.
- **Hard dependency**: `core` module wave 1 (merged) — `MealPrepException` (if used), `core.types.SlotKind` (if it exists; else 01a adds it).
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter` (for the 401 path).
- **Soft dependency**: this is the first planner ticket; every other planner ticket depends on 01a.
- **Sibling tickets running in parallel** (Wave 3 round 1): `discovery-01a`, `feedback-01a`, `adaptation-pipeline-01a`. None should touch any planner file or the cross-cutting `openapi.yaml` outside their own `# <module>` block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean (mandatory)
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `PlannerExceptionHandler` annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`
- [ ] OpenAPI inlined-nullable-object pattern applied for `MealSlotDto.scheduledRecipe`; inlined-nullable-enum for `pinnedReason`
- [ ] All YAML description strings containing `,` `:` `'` single-quoted
- [ ] `MultipleBagFetchException` does not occur — verified by IT that loads a multi-day plan
- [ ] No N+1 catastrophe on the GET path — verified via Hibernate statement counter (bounded to days + slots count, not row-product)
- [ ] No `pom.xml` dependency adds (hypersistence-utils + Jackson JsonNode already on classpath from wave 2)
- [ ] No recipe / nutrition / household / provisions / preference / auth module file touched

Squash-merge with: `feat(planner): 01a — plan data model (Plan + Day + MealSlot + ScheduledRecipe + ReoptSuggestion) + V…120000-120300 migrations + GET /plans/{id}`

## What's NOT in scope

- Lifecycle state machine, transitions, supersession, revert flows → **planner-01b**
- Full read API (`getActivePlan` / history / range / suggestions / feasibility) → **planner-01c**
- BeamSearchEngine + HardFilterRunner + BeamPruner (Stage A) → **planner-01d**
- ScoringEngine + seven sub-score calculators + gates (Stage A scoring) → **planner-01e**
- RollupBuilder (Stage B) → **planner-01f**
- StageCInvoker + Phase2Augmenter (Stage C + Phase 2 LLM calls) → **planner-01g**, **planner-01h**
- MidWeekReoptCoordinator + PinningRules → **planner-01i**
- POST `/generate`, `/accept`, `/reject`, `/abandon`, `/revert`, `/{slotId}/state`, suggestions list/dismiss endpoints → **planner-01j**
- Event listeners (provisions / nutrition / preference / household-settings) + materiality filters + suggestion debounce → **planner-01k**
- `core.DecisionLogService` integration + per-iteration writes + trace propagation + `AdminPlannerController` → **planner-01l**
- `PlannerProperties` `@ConfigurationProperties` (default values for beam width, top-N, weights, timeouts, debounce window etc.) → **planner-01d** owns the first additions (`beamWidth`, `topN`, `minPoolPerSlot`, `maxPoolPerSlot`, `maxTimeOvershootRatio`, `stageATimeout`); later tickets append their own keys.
- `@ValidPlanGenerationRequest` + `@ValidSlotState` validators → **planner-01j**
- All planner events (`PlanGeneratedEvent`, `PlanAcceptedEvent`, etc.) — defined alongside the flows that publish them in 01b/01j/01k. 01a defines no events.
