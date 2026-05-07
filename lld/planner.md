# Planner Module — LLD

*Implementation specification for the orchestrator: composes weekly plans, runs the [optimisation loop](../design/optimisation-loop.md) at week scale, owns plan lifecycle, coordinates mid-week re-optimisation. Translates [meal-planner.md](../design/meal-planner.md) into a buildable Spring Boot module.*

## Scope

This document specifies the `planner` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, REST controllers, validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The planner is **read-only against the data models** — it injects every other module's `QueryService` and the planner-bundle DTOs they expose ([lld/style-guide.md §Bundle-DTO-for-planner](style-guide.md#bundle-dto-for-planner-convention)). Its only writes are to its own plan storage and (via [core](style-guide.md#decision-log)) the decision log. Stage D refine-directives go through `OptimiserService` (adaptation pipeline) only — the planner never invokes update services on the data models.

The HLD's four optimisation-loop stages map to four internal helpers, each behind a deliberately narrow interface:

| Stage | Helper | What it does |
|---|---|---|
| Pre-A | `ConstraintFeasibilityCheck` | Catches over-restrictive constraint sets *before* search; surfaces resolutions to the user |
| A | `BeamSearchEngine` + `ScoringEngine` (which composes seven `SubScoreCalculator`s) | Hard-filter, score, beam-search → top-N candidate plans |
| B | `RollupBuilder` | Per-candidate flat summary stats (the LLM context) |
| C | `StageCInvoker` (calls `AiService` with `StageCPickTask`), then `Phase2Augmenter` (`Phase2AugmentationTask`) | LLM picks from N, then proposes plan-level augmentations |
| D | (no dedicated helper — `PlannerService` emits a `RefineDirective` to `OptimiserService` and re-runs Stage A on the affected slot) | |

Mid-week re-opt is the same pipeline scoped to remaining slots, coordinated by `MidWeekReoptCoordinator`.

---

## Package Layout

```
com.example.mealprep.planner/
├── PlannerModule.java                       facade re-exporting public service interfaces
├── api/
│   ├── controller/                          PlansController, MealSlotsController,
│   │                                        AdminPlannerController
│   ├── dto/                                 records (see DTOs section)
│   └── mapper/                              PlanMapper, MealSlotMapper, ScheduledRecipeMapper,
│                                            ReoptSuggestionMapper
├── domain/
│   ├── entity/                              JPA entities (see Entities section)
│   ├── repository/                          Spring Data interfaces — package-private
│   └── service/
│       ├── PlannerService.java              public interface (writes)
│       ├── PlanQueryService.java            public interface (reads)
│       ├── PlannerServiceImpl.java          single impl of both
│       └── internal/
│           ├── ConstraintFeasibilityCheck       pre-A: pool-size + resolution detector
│           ├── beamsearch/
│           │   ├── BeamSearchEngine             stage A search driver
│           │   ├── PartialPlan                  package-private value carrier
│           │   ├── HardFilterRunner             per-slot pool builder
│           │   └── BeamPruner                   width-N retain helper
│           ├── scoring/
│           │   ├── ScoringEngine                composes the seven sub-scores + gates
│           │   ├── SubScoreCalculator           interface; one impl per sub-score
│           │   ├── PreferenceSubScore           taste-profile alignment
│           │   ├── NutritionSubScore            convergence vs targets
│           │   ├── CostSubScore                 confidence-weighted
│           │   ├── VarietySubScore              variety_index
│           │   ├── TimeSubScore                 time_fit
│           │   ├── BatchSubScore                batch_cook_compatibility
│           │   ├── ProvisionsSubScore           provisions_utilisation
│           │   ├── NutritionFloorGate           multiplicative gate via NutritionFloorGateService
│           │   └── VarietyGate                  multiplicative gate (max-repeat rule)
│           ├── rollup/
│           │   ├── RollupBuilder                stage B
│           │   ├── DailyRollup                  intermediate per-day totals
│           │   └── WeeklyRollup                 aggregated weekly figure
│           ├── stagec/
│           │   ├── StageCInvoker                runs StageCPickTask via AiService
│           │   ├── Phase2Augmenter              runs Phase2AugmentationTask via AiService
│           │   ├── StageCPickTask               AiTask<StageCPickResponse>
│           │   ├── Phase2AugmentationTask       AiTask<Phase2AugmentationResponse>
│           │   └── AugmentationVerifier         post-hoc hard-filter for every augmentation
│           ├── reopt/
│           │   ├── MidWeekReoptCoordinator      drives scoped re-opt
│           │   ├── PinningRules                 derives pinned slots from current state
│           │   └── ReoptScopeBuilder            from a trigger event, computes the scope
│           ├── lifecycle/
│           │   ├── PlanStateMachine             pure transition logic
│           │   └── PlanGenerationCounter        per-(household, week) generation increment
│           ├── PlanComposer                     top-level orchestrator: A → B → C → D loop
│           └── PlanReader                       hydrates entities into PlanDto
├── event/
│   ├── PlannerEvent.java                    sealed marker (extends MealPrepEvent)
│   ├── PlanGeneratedEvent, PlanAcceptedEvent, PlanSupersededEvent,
│   │   PlanCompletedEvent, PlanRejectedEvent, PlanAbandonedEvent
│   ├── ReoptTriggeredEvent
│   └── ReoptSuggestedEvent                  user-confirmation hint
├── exception/                               module root + per-failure subclasses
├── validation/                              @ValidPlanGenerationRequest, @ValidSlotState +
│                                            validators
└── config/                                  PlannerProperties (@ConfigurationProperties)
```

`PlannerModule.java` carries no business logic — one-line view of what's exposed (`PlannerService`, `PlanQueryService`).

The `internal/` subpackage groups the loop-stage helpers; everything in there is package-private and never crosses the module boundary. `SubScoreCalculator` is itself package-private; the seven implementations are wired into `ScoringEngine` by Spring `List<SubScoreCalculator>` injection.

---

## Plan Storage Shape — Locked: Option B (normalised)

The HLD did not commit to either shape; both were analysed. **Locked decision (2026-05-07): Option B (normalised tables).** The slot-level write workload (cooking → cooked → eaten) and grocery's per-slot batch-cook aggregation make per-slot indexability the right call; the read cost of joining slots into a plan is paid once per plan view. Option A's analysis is preserved below as documentation, but its migration is reference only and **does not ship**.

### Option A — JSONB nested document

One row per plan: `planner_plans(id, household_id, week_start_date, generation, replaces_plan_id, status, trigger, trigger_event_id, trace_id, decision_id, created_at, accepted_at, completed_at, plan_document JSONB, version)`. The `plan_document` column carries days, slots, scheduled recipes, batch-cook session ids — everything per the HLD's nested JSON.

**Pros:**
- Read-whole matches the planner's load pattern: serve plan, score plan, present plan all want every slot.
- Revert is trivial — copy one row.
- Schema flexibility: adding a slot field (`pinnedReason`, `augmentationNotes`) is a code change, no migration.
- One write per generation: nested inserts collapse to one upsert.

**Cons:**
- Filtering on slots requires JSON operators (`plan_document @> '{"days":[{"slots":[{"state":"cooking"}]}]}'`) — readable but harder to optimise.
- No FK from cross-module references. `scheduled_recipe.recipe_id` cannot be a real FK to `recipes.id`; orphan detection becomes a sweep job rather than a constraint.
- `markSlotState` rewrites the whole document — a 5–10 KB JSONB write per state transition. With 21 slots × ~5 transitions per slot, that's ~100 writes/week/household — manageable, but not as cheap as a per-slot update.
- GIN index needed for any slot-level filter (e.g. "all plans containing recipe X"); GIN on a 5–10 KB document is heavier than a per-row index on a normalised column.

### Option B — Normalised tables (recommended)

Four tables: `planner_plans`, `planner_days`, `planner_meal_slots`, `planner_scheduled_recipes` — schema below.

**Pros:**
- Per-slot writes are local: `markSlotState` updates one row, one column.
- FK-able: `planner_scheduled_recipes.recipe_id` references `recipes.id`. Cross-module integrity at DB level.
- Indexes on hot fields: `meal_slots.state`, `meal_slots.kind`, `scheduled_recipes.batch_cook_session_id` for the grocery module's session-aggregation read.
- Querying "all plans active right now" or "all slots in state `planned`" is a trivial WHERE clause.
- `getPlansBetween(household, from, to)` — common UI need — is one indexed range query without document parsing.

**Cons:**
- Nested writes are multi-table inserts. Done in one transaction with `cascade = ALL`, but more code than a JSONB upsert.
- Schema evolution — adding a slot field — is a migration. For ~5–10 fields/year that's fine; for fast-evolving shape it's friction.
- Revert is "INSERT … SELECT" across four tables. More code than copying a row.
- Optimistic locking sits on the plan aggregate; child-row writes via `markSlotState` need either join-and-bump-parent or per-table `@Version`. We bump the parent (see Concurrency).

### Recommendation

**Option B (normalised).** Three deciders:

1. The grocery module joins on `scheduled_recipes` + `batch_cook_session_id` to compute the shopping list. JSONB makes that a manual join through `jsonb_array_elements`; relational makes it a bare SELECT.
2. The notification module ([lld/notification.md](notification.md)) and the cook listener (which sets slot state on `MealCookedEvent`) both want per-slot reads and writes. Per-slot is the dominant write pattern.
3. The "schema flexibility" argument is weak here — the slot shape is reasonably stable per the HLD, and the slot-level `Map<String, JsonNode> extras` JSONB column (pattern from [lld/notification.md](notification.md)) handles the long tail without making the whole structure opaque.

Revert (Option B's weakest point) is a deliberate one-method service-layer call — `revertToPlan` — that does an INSERT … SELECT once. Cost is one method, paid once.

The decision is genuinely contestable; **flagged for user review**. If the user chooses A, the only changes are `V20260507120100` (single-table create instead of four), the entity layout (`Plan` carries a `JsonNode planDocument`), and the repository (per-slot fetches become document parsing). The service layer, controllers, events, and scoring remain identical.

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme.

```
V20260507120000__planner_create_plans.sql                    (Option B chosen)
V20260507120100__planner_create_days_slots_recipes.sql
V20260507120200__planner_create_reopt_suggestions.sql
V20260507120300__planner_create_unique_active_constraint.sql
```

If Option A wins on review, the second migration collapses into a single `plan_document JSONB` column on `planner_plans` — see Option A migration block at the end of this section.

### V20260507120000 — Plan aggregate root

```sql
CREATE TABLE planner_plans (
    id                       uuid PRIMARY KEY,
    household_id             uuid NOT NULL,
    week_start_date          date NOT NULL,
    generation               integer NOT NULL,                   -- 1 for first plan; +1 per regeneration
    replaces_plan_id         uuid REFERENCES planner_plans(id),
    status                   varchar(16) NOT NULL,               -- draft|generated|active|superseded|completed|rejected|abandoned
    trigger_kind             varchar(32) NOT NULL,               -- user-initiated|scheduled-weekly|mid-week-reopt
    trigger_event_id         uuid,                               -- FK conceptually; stored loose because it can target any event source
    quality_warning          boolean NOT NULL DEFAULT false,     -- set when constraint feasibility's "user declined all resolutions" path fired
    cold_start               boolean NOT NULL DEFAULT false,
    ai_augmented             boolean NOT NULL DEFAULT false,     -- false when Stage C/Phase 2 fell back to deterministic
    trace_id                 uuid NOT NULL,
    decision_id              uuid NOT NULL,
    accepted_at              timestamptz,
    completed_at             timestamptz,
    rejected_at              timestamptz,
    rejected_reason          varchar(255),
    abandoned_at             timestamptz,
    abandoned_reason         varchar(255),
    score_breakdown          jsonb NOT NULL,                     -- per-sub-score values; mirrored by ScoreBreakdownDocument
    rollup_summary           jsonb NOT NULL,                     -- daily + weekly figures; mirrored by RollupSummaryDocument
    version                  bigint NOT NULL DEFAULT 0,          -- @Version
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);

-- Hot read: getActivePlan(household, week). Filter by both columns + status='active'.
CREATE INDEX idx_planner_plans_household_week_status
    ON planner_plans (household_id, week_start_date, status);

-- Plan history listing for a household; the UI's "previous weeks" view.
CREATE INDEX idx_planner_plans_household_week_gen
    ON planner_plans (household_id, week_start_date, generation DESC);

-- Range listing: getPlansBetween(household, from, to).
CREATE INDEX idx_planner_plans_household_range
    ON planner_plans (household_id, week_start_date);

-- Trace lookup for the decision-log explanation flow.
CREATE INDEX idx_planner_plans_trace ON planner_plans (trace_id);
```

`score_breakdown` and `rollup_summary` are JSONB by deliberate choice — they are read-whole, never filtered on, and document-y. The slot-level structure (which *is* filtered on) lives in normalised tables below.

### V20260507120100 — Day, slot, scheduled recipe

```sql
CREATE TABLE planner_days (
    id                       uuid PRIMARY KEY,
    plan_id                  uuid NOT NULL REFERENCES planner_plans(id) ON DELETE CASCADE,
    on_date                  date NOT NULL,
    notes                    varchar(255),                       -- user-set, e.g. "eating out tonight"
    UNIQUE (plan_id, on_date)
);
-- Iterate days for a plan in order.
CREATE INDEX idx_planner_days_plan_date ON planner_days (plan_id, on_date);

CREATE TABLE planner_meal_slots (
    id                       uuid PRIMARY KEY,
    day_id                   uuid NOT NULL REFERENCES planner_days(id) ON DELETE CASCADE,
    plan_id                  uuid NOT NULL REFERENCES planner_plans(id) ON DELETE CASCADE,
    slot_index               integer NOT NULL,                   -- ordering within day; 0-based
    kind                     varchar(16) NOT NULL,               -- breakfast|lunch|dinner|snack|custom
    label                    varchar(64) NOT NULL,
    time_budget_min          integer NOT NULL,
    shared                   boolean NOT NULL,
    eaters                   uuid[] NOT NULL DEFAULT '{}',       -- empty when shared=true; non-empty list when shared=false
    state                    varchar(16) NOT NULL DEFAULT 'planned',  -- planned|cooking|cooked|eaten|skipped
    pinned_reason            varchar(32),                        -- set during re-opt when this slot is pinned
    UNIQUE (day_id, slot_index)
);
-- Hot reads: state filters during re-opt scope build, kind filter for analytics.
CREATE INDEX idx_planner_meal_slots_plan_state ON planner_meal_slots (plan_id, state);
CREATE INDEX idx_planner_meal_slots_day        ON planner_meal_slots (day_id);

CREATE TABLE planner_scheduled_recipes (
    id                       uuid PRIMARY KEY,
    slot_id                  uuid NOT NULL UNIQUE REFERENCES planner_meal_slots(id) ON DELETE CASCADE,
    recipe_id                uuid NOT NULL,                      -- soft FK to recipes.id; not enforced (cross-module)
    recipe_version_id        uuid NOT NULL,                      -- soft FK to recipe_versions.id
    recipe_branch_id         uuid NOT NULL,                      -- soft FK to recipe_branches.id
    servings                 integer NOT NULL,
    batch_cook_session_id    uuid,                               -- groups slots that share one cook
    augmentation_notes       varchar(512),                       -- Phase 2 plan-level additions
    augmentation_source      varchar(16),                        -- llm|user|null
    is_phase2_addition       boolean NOT NULL DEFAULT false      -- true when Phase 2 inserted this scheduled recipe
);
-- The grocery module aggregates ingredients per batch-cook session.
CREATE INDEX idx_planner_scheduled_recipes_batch
    ON planner_scheduled_recipes (batch_cook_session_id) WHERE batch_cook_session_id IS NOT NULL;
-- Reverse lookup: which plans currently use this recipe? Used by the recipe deletion guard.
CREATE INDEX idx_planner_scheduled_recipes_recipe
    ON planner_scheduled_recipes (recipe_id);
```

Foreign keys to other modules' tables are deliberately *not* enforced as DB-level FKs. Cross-module DB-level FKs would couple migrations and complicate module ordering at startup. We accept the looseness; the recipe deletion path checks for active plan references via the index above.

### V20260507120200 — Re-opt suggestions

```sql
CREATE TABLE planner_reopt_suggestions (
    id                       uuid PRIMARY KEY,
    household_id             uuid NOT NULL,
    week_start_date          date NOT NULL,
    plan_id                  uuid NOT NULL REFERENCES planner_plans(id),
    trigger_kind             varchar(32) NOT NULL,               -- provisions|nutrition|preference|household-settings|user
    trigger_event_id         uuid,
    affected_slot_ids        uuid[] NOT NULL DEFAULT '{}',
    summary                  varchar(255) NOT NULL,
    status                   varchar(16) NOT NULL DEFAULT 'pending',  -- pending|accepted|dismissed|expired
    expires_at               timestamptz,
    created_at               timestamptz NOT NULL,
    resolved_at              timestamptz
);
-- Hot read: notification module + UI list pending suggestions.
CREATE INDEX idx_planner_reopt_pending
    ON planner_reopt_suggestions (household_id, status, created_at DESC);
```

A re-opt *suggestion* is what the listener creates after filtering an upstream event for materiality. The user confirms via `reoptimisePlan(...)`, which transitions the suggestion to `accepted`. Suggestions are idempotent per `(household_id, week_start_date, trigger_event_id)` — duplicate triggers coalesce.

### V20260507120300 — Unique active plan constraint

```sql
-- Only one active plan per (household, week_start_date). Partial unique index.
-- Required by the lifecycle invariant; enforced at DB level so a concurrent accept can't double-active.
CREATE UNIQUE INDEX uq_planner_plans_active_per_household_week
    ON planner_plans (household_id, week_start_date)
    WHERE status = 'active';
```

This DB-level constraint pairs with the application-level single-flight (`LockService` advisory lock — see Concurrency). The lock prevents simultaneous generation; the partial unique index prevents simultaneous *acceptance* that bypasses the lock.

### Option A migration (alternative — not used unless user picks A)

If Option A is chosen instead, replace V20260507120100 with:

```sql
-- (Used only if user picks Option A.)
ALTER TABLE planner_plans ADD COLUMN plan_document jsonb NOT NULL;
-- GIN index for filter-on-slot queries; cost is justified because per-slot reads dominate.
CREATE INDEX idx_planner_plans_document_gin ON planner_plans USING gin (plan_document);
```

The `score_breakdown` and `rollup_summary` columns stay JSONB in either option.

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@Version` on every mutable aggregate root, `@CreatedDate`/`@LastModifiedDate` audit columns where applicable, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. JSONB columns mapped via `@Type(JsonType.class)` from `hypersistence-utils`.

| Entity | Notes |
|---|---|
| `Plan` | Aggregate root. Owns `@OneToMany` to `Day` (cascade ALL, orphanRemoval). `@Version Long version`. `score_breakdown` and `rollup_summary` mapped to `ScoreBreakdownDocument` and `RollupSummaryDocument` records via JSONB. |
| `Day` | Child of `Plan`. `@OneToMany` to `MealSlot`. No `@Version` — parent's version covers the aggregate. |
| `MealSlot` | Child. `@OneToOne` to `ScheduledRecipe` (optional — null for eating-out / fasting slots). `eaters` mapped to `UUID[]` via `@Type(ListArrayType.class)`. State transitions via `markSlotState` write the slot row directly *and* touch the parent `Plan.version` (see Concurrency). |
| `ScheduledRecipe` | Owned by a `MealSlot`. Cross-module IDs (`recipeId`, `recipeVersionId`, `recipeBranchId`) — soft refs only. |
| `ReoptSuggestion` | One row per pending re-opt prompt. Standalone aggregate. `@Version Long version`. |
| `ScoreBreakdownDocument` | JSONB record carried on `Plan`. Per-sub-score floats + composite. |
| `RollupSummaryDocument` | JSONB record carried on `Plan`. Mirrors the HLD's daily + weekly rollup shape. |

Module-local enums:

- `PlanStatus` (`DRAFT`, `GENERATED`, `ACTIVE`, `SUPERSEDED`, `COMPLETED`, `REJECTED`, `ABANDONED`)
- `SlotKind` (re-exported from `core.types.SlotKind`: `BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`, `CUSTOM`)
- `SlotState` (`PLANNED`, `COOKING`, `COOKED`, `EATEN`, `SKIPPED`)
- `PinnedReason` (`EATEN`, `COOKED`, `COOKING`, `SKIPPED`, `USER_PINNED`)
- `TriggerKind` (`USER_INITIATED`, `SCHEDULED_WEEKLY`, `MID_WEEK_REOPT`)
- `ReoptTriggerKind` (`PROVISIONS`, `NUTRITION`, `PREFERENCE`, `HOUSEHOLD_SETTINGS`, `USER`)
- `ReoptStatus` (`PENDING`, `ACCEPTED`, `DISMISSED`, `EXPIRED`)
- `AugmentationSource` (`LLM`, `USER`)

`SlotKind` and `MealKind` belong in `core.types` per [style-guide §core](style-guide.md#module-package-structure); the planner imports them.

---

## DTOs

All DTOs are Java records per the style guide.

```java
public record PlanDto(
    UUID id, UUID householdId, LocalDate weekStartDate,
    int generation, UUID replacesPlanId,
    PlanStatus status, TriggerKind trigger, UUID triggerEventId,
    boolean qualityWarning, boolean coldStart, boolean aiAugmented,
    UUID traceId, UUID decisionId,
    Instant acceptedAt, Instant completedAt,
    Instant rejectedAt, String rejectedReason,
    Instant abandonedAt, String abandonedReason,
    ScoreBreakdownDocument scoreBreakdown,
    RollupSummaryDocument rollupSummary,
    List<DayDto> days,
    long version, Instant createdAt, Instant updatedAt) {}

public record DayDto(
    UUID id, LocalDate date, String notes,
    List<MealSlotDto> slots) {}

public record MealSlotDto(
    UUID id, int slotIndex, SlotKind kind, String label,
    int timeBudgetMin, boolean shared, List<UUID> eaters,
    SlotState state, PinnedReason pinnedReason,
    ScheduledRecipeDto scheduledRecipe) {}

public record ScheduledRecipeDto(
    UUID id, UUID recipeId, UUID recipeVersionId, UUID recipeBranchId,
    int servings, UUID batchCookSessionId,
    String augmentationNotes, AugmentationSource augmentationSource,
    boolean phase2Addition) {}

public record ScoreBreakdownDocument(
    BigDecimal preference, BigDecimal nutrition, BigDecimal cost,
    BigDecimal variety, BigDecimal time, BigDecimal batch, BigDecimal provisions,
    BigDecimal composite,                           // weighted sum after gates
    boolean nutritionFloorGatePassed,
    boolean varietyGatePassed,
    String weightSchemeVersion                      // "v1-uniform" per HLD
) {}

public record RollupSummaryDocument(
    List<DailyRollupDocument> daily,
    WeeklyRollupDocument weekly) {}

public record DailyRollupDocument(
    LocalDate date,
    int kcal, BigDecimal proteinG, BigDecimal fatG, BigDecimal carbsG, BigDecimal fibreG,
    BigDecimal costGbp, int totalTimeMin,
    List<String> violations) {}

public record WeeklyRollupDocument(
    int kcalTotal,
    BigDecimal proteinAvgG, BigDecimal fatAvgG, BigDecimal carbsAvgG,
    BigDecimal costEstimateGbp, BigDecimal costConfidence,
    int staleIngredientCount,
    BigDecimal varietyIndex,
    int batchCookSessions,
    List<String> constraintViolations) {}
```

### Request records

```java
public record GeneratePlanRequest(
    @NotNull UUID householdId,
    @NotNull LocalDate weekStartDate,
    @NotNull TriggerKind trigger,
    UUID triggerEventId,                        // null for user-initiated
    boolean forceRegenerateIfActive             // requires confirmation upstream
) {}

public record ReoptRequest(
    @NotNull UUID planId,
    @NotNull LocalDate fromDate,                // re-opt scope start (inclusive)
    @NotNull ReoptTriggerKind trigger,
    UUID triggerEventId,
    UUID reoptSuggestionId                      // populated when accepting an existing suggestion
) {}

public record AcceptPlanRequest(@NotNull UUID planId) {}
public record RejectPlanRequest(@NotNull UUID planId, @Size(max = 255) String reason) {}
public record AbandonPlanRequest(@NotNull UUID planId, @Size(max = 255) String reason) {}
public record RevertToPlanRequest(@NotNull UUID targetHistoricalPlanId) {}
public record MarkSlotStateRequest(@NotNull UUID slotId, @NotNull SlotState newState) {}
```

### Re-opt suggestion DTO

```java
public record ReoptSuggestionDto(
    UUID id, UUID householdId, LocalDate weekStartDate, UUID planId,
    ReoptTriggerKind trigger, UUID triggerEventId,
    List<UUID> affectedSlotIds, String summary,
    ReoptStatus status, Instant expiresAt,
    Instant createdAt, Instant resolvedAt) {}
```

### Constraint feasibility DTOs

```java
public record FeasibilityCheckResultDto(
    boolean feasible,
    List<ConstraintConflictDto> conflicts,
    List<ResolutionOptionDto> resolutions) {}

public record ConstraintConflictDto(
    ConflictType type,                          // HOUSEHOLD_HARD_COLLISION | NUTRITION_VS_BUDGET | PROVISIONS_BOTTLENECK | OVER_SPECIFIED_PREFERENCES
    List<UUID> affectedSlotIds,
    String description) {}

public record ResolutionOptionDto(
    String key,                                 // "split_slot" | "drop_protein_floor_to_160" | …
    String description,
    int slotsRecovered,
    BigDecimal scoreRecovered) {}

public enum ConflictType {
    HOUSEHOLD_HARD_COLLISION, NUTRITION_VS_BUDGET, PROVISIONS_BOTTLENECK, OVER_SPECIFIED_PREFERENCES
}
```

---

## Mappers

MapStruct interfaces, `@Mapper(componentModel = "spring")`. One per entity-DTO pair. Custom mappings declared explicitly only where field names diverge.

```java
@Mapper(componentModel = "spring", uses = { DayMapper.class })
public interface PlanMapper {
    PlanDto toDto(Plan entity);
    List<PlanDto> toDtos(List<Plan> entities);
}

@Mapper(componentModel = "spring", uses = { MealSlotMapper.class })
public interface DayMapper {
    DayDto toDto(Day entity);
}

@Mapper(componentModel = "spring", uses = { ScheduledRecipeMapper.class })
public interface MealSlotMapper {
    MealSlotDto toDto(MealSlot entity);
    List<MealSlotDto> toDtos(List<MealSlot> entities);
}

@Mapper(componentModel = "spring")
public interface ScheduledRecipeMapper {
    ScheduledRecipeDto toDto(ScheduledRecipe entity);
}

@Mapper(componentModel = "spring")
public interface ReoptSuggestionMapper {
    ReoptSuggestionDto toDto(ReoptSuggestion entity);
    List<ReoptSuggestionDto> toDtos(List<ReoptSuggestion> entities);
}
```

---

## Repositories

Package-private (no `public`); cross-module access goes through service interfaces only.

```java
interface PlanRepository extends JpaRepository<Plan, UUID> {

    @EntityGraph(attributePaths = {"days", "days.slots", "days.slots.scheduledRecipe"})
    Optional<Plan> findWithDaysById(UUID id);

    @EntityGraph(attributePaths = {"days", "days.slots", "days.slots.scheduledRecipe"})
    List<Plan> findWithDaysByIdIn(List<UUID> ids);

    @EntityGraph(attributePaths = {"days", "days.slots", "days.slots.scheduledRecipe"})
    Optional<Plan> findWithDaysByHouseholdIdAndWeekStartDateAndStatus(
        UUID householdId, LocalDate weekStartDate, PlanStatus status);

    @EntityGraph(attributePaths = {"days", "days.slots", "days.slots.scheduledRecipe"})
    List<Plan> findWithDaysByHouseholdIdAndWeekStartDateOrderByGenerationDesc(
        UUID householdId, LocalDate weekStartDate);

    @EntityGraph(attributePaths = {"days", "days.slots", "days.slots.scheduledRecipe"})
    Page<Plan> findByHouseholdIdAndWeekStartDateBetweenOrderByWeekStartDateDescGenerationDesc(
        UUID householdId, LocalDate from, LocalDate to, Pageable pageable);

    int countByHouseholdIdAndWeekStartDate(UUID householdId, LocalDate weekStartDate);

    @Query("select count(sr) > 0 from ScheduledRecipe sr where sr.recipeId = :recipeId")
    boolean existsActiveScheduleForRecipe(@Param("recipeId") UUID recipeId);
}

interface MealSlotRepository extends JpaRepository<MealSlot, UUID> {
    Optional<MealSlot> findByIdAndPlanId(UUID slotId, UUID planId);
    List<MealSlot> findAllByPlanIdAndStateIn(UUID planId, Collection<SlotState> states);
    List<MealSlot> findAllByPlanIdOrderByDayOnDateAscSlotIndexAsc(UUID planId);
}

interface ReoptSuggestionRepository extends JpaRepository<ReoptSuggestion, UUID> {
    Optional<ReoptSuggestion> findByHouseholdIdAndWeekStartDateAndTriggerEventId(
        UUID householdId, LocalDate weekStartDate, UUID triggerEventId);
    Page<ReoptSuggestion> findByHouseholdIdAndStatusOrderByCreatedAtDesc(
        UUID householdId, ReoptStatus status, Pageable pageable);
    List<ReoptSuggestion> findAllByStatusAndExpiresAtBefore(ReoptStatus status, Instant cutoff);
}
```

`@EntityGraph` on every plan-detail read keeps the hot path to a single fetch (one join across `Plan` → `Day` → `MealSlot` → `ScheduledRecipe`). The `findByHouseholdIdAndWeekStartDateBetween…` method is used by `getPlansBetween` and exists *with* the entity graph because the UI's range view shows slot-state at a glance.

---

## Service Interfaces

Per the style guide, both module interfaces are implemented by a single `PlannerServiceImpl`.

### `PlanQueryService`

```java
public interface PlanQueryService {
    Optional<PlanDto> getActivePlan(UUID householdId, LocalDate weekStartDate);
    List<PlanDto> getPlanHistory(UUID householdId, LocalDate weekStartDate);
    Page<PlanDto> getPlansBetween(UUID householdId, LocalDate from, LocalDate to, Pageable pageable);
    Optional<PlanDto> getPlanById(UUID planId);
    List<PlanDto> getPlansByIds(List<UUID> planIds);

    // Re-opt suggestions — surfaced by the notification module + plan UI.
    Page<ReoptSuggestionDto> getPendingSuggestions(UUID householdId, Pageable pageable);
    Optional<ReoptSuggestionDto> getSuggestion(UUID suggestionId);

    // Constraint feasibility check — UI calls this before triggering generation when the user
    // hits "regenerate" so the resolution dialog can render even before Stage A starts.
    FeasibilityCheckResultDto checkFeasibility(UUID householdId, LocalDate weekStartDate);
}
```

Returning `Page<PlanDto>` from `getPlansBetween` deviates from the HLD's `List<PlanDto>` shape because the UI's history view paginates; **worth user review** but defended on UI grounds. Single-page fetch is `getPlansBetween(household, from, to, PageRequest.of(0, 100))`.

### `PlannerService`

```java
public interface PlannerService {
    /** Stage A→B→C→D pipeline. Single-flight per (householdId, weekStartDate) via LockService. */
    PlanDto generatePlan(GeneratePlanRequest request);

    /** Mid-week re-opt scoped from `fromDate` onward. Pinned slots preserved per PinningRules. */
    PlanDto reoptimisePlan(ReoptRequest request);

    /** generated → active. Touches the partial unique index. */
    PlanDto acceptPlan(AcceptPlanRequest request);

    /** generated → rejected. Terminal. */
    PlanDto rejectPlan(RejectPlanRequest request);

    /** Copy-forward revert. New plan created with generation = max+1, replaces_plan_id = current active. */
    PlanDto revertToPlan(RevertToPlanRequest request);

    /** active → abandoned. Terminal. */
    PlanDto abandonPlan(AbandonPlanRequest request);

    /** Slot-level state machine: planned → cooking → cooked → eaten | skipped. */
    void markSlotState(MarkSlotStateRequest request);

    /** User dismisses a re-opt suggestion. Pure status transition. */
    void dismissSuggestion(UUID suggestionId);
}
```

Update methods return the updated `PlanDto` per the style guide. Cross-module update services (`PreferenceUpdateService`, `NutritionUpdateService`, …) are deliberately **not** injected — the planner is read-only against the data models.

---

## Internal helpers

This section specifies the seam interfaces of the optimisation-loop helpers. Implementations live in `domain/service/internal/…`; what's shown is the contract Spring wires together inside `PlannerServiceImpl`.

### `ConstraintFeasibilityCheck`

```java
interface ConstraintFeasibilityCheck {
    FeasibilityCheckResultDto check(PlanCompositionContext context);
}
```

`PlanCompositionContext` is the read-only bundle assembled at the start of every generation: hard constraints, soft preference bundle, nutrition targets, provisions bundle, household settings, recipe pool snapshot. Loaded once per generation via the bundle DTOs ([§Read pattern](#read-pattern)).

`check` runs four passes per [meal-planner.md §Constraint Feasibility](../design/meal-planner.md#constraint-feasibility-check):

1. Per-slot pool size (`min_pool_per_slot = 3`, configurable via `PlannerProperties`).
2. Constrained-slot classification (mapped to a `ConflictType`).
3. Best-possible-plan simulation (single beam-search pass with width 1, no scoring) to test whether daily nutrition floors are clearable at all.
4. Resolution ranking — for each conflict, compute candidate resolutions (split slot, drop protein floor, raise budget, widen preference) and the slots/score recovered.

Returned to the controller via `checkFeasibility`. Also called *inside* `generatePlan` — if `feasible = false`, the planner does not attempt Stage A unless the user has already chosen a resolution path (carried in `GeneratePlanRequest` via a future-extension field; for v1, `feasible = false` returns immediately with `quality_warning = true` and an empty plan, leaving the user to either edit constraints or retry).

### `BeamSearchEngine` (Stage A)

```java
interface BeamSearchEngine {
    List<CandidatePlan> search(PlanCompositionContext context, BeamSearchConfig config);
}

record BeamSearchConfig(int width, int topN, int maxPoolPerSlot) {}
```

Default `width = 20`, `topN = 5`, `maxPoolPerSlot = 50` per [meal-planner.md §data volumes](../design/meal-planner.md#data-volumes--back-of-envelope). Shipped via `PlannerProperties` so timeout fallbacks can shrink the width.

Algorithm:

1. Build the post-hard-filter pool per slot. For each slot, ask `RecipeQueryService.search(...)` for recipes matching the slot kind (breakfast/lunch/dinner) + `time_budget_min`, then pass each recipe's ingredient mapping keys through `HardConstraintFilterService.check(userId, ...)` (or `checkForHousehold` for shared slots). Recipes whose ingredients fail or whose required equipment is not in the provisions bundle are dropped.
2. Initialise the beam with one empty partial plan.
3. Slot-by-slot in week order: expand each beam entry by the slot's pool, score the expanded partial plan, retain the top `width` by score.
4. After the last slot, return the top `topN` complete plans.

`HardFilterRunner` and `BeamPruner` are pure functions — `HardFilterRunner` produces a `Map<UUID slotId, List<RecipeDto>>`; `BeamPruner` is `(List<PartialPlan>, int) -> List<PartialPlan>`.

### `ScoringEngine`

```java
interface ScoringEngine {
    ScoreResult score(CandidatePlan plan, PlanCompositionContext context);
}

record ScoreResult(BigDecimal composite, ScoreBreakdownDocument breakdown) {}
```

Composes the seven sub-scores per [meal-planner.md §scoring](../design/meal-planner.md#scoring-function):

```
composite = (Σ weight_i · sub_score_i) · Π gate_i
```

with `gate_i ∈ {0, 1}` from `NutritionFloorGate` and `VarietyGate`. v1 weights are uniform `1/7` per the HLD's "Initial Weights v1" — stored under `mealprep.planner.scoring.weights.*` in `PlannerProperties` so they're tunable without redeploy. The weight scheme version is recorded on every plan (`scoreBreakdown.weightSchemeVersion = "v1-uniform"`).

Each `SubScoreCalculator` is package-private and takes the same shape:

```java
interface SubScoreCalculator {
    String name();                                                   // matches PlannerProperties weight key
    BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx);    // returns [0, 1]
}
```

Spring injects `List<SubScoreCalculator>` and `ScoringEngine` indexes by `name()`. This is the seam for the next section's seven sub-score formulas.

### Sub-score formulas — Locked (2026-05-07)

The interface (`SubScoreCalculator` returning `[0, 1]`) is fixed; the formulas below are committed. Numerical constants (e.g. variety targets, cost confidence threshold) live in `PlannerProperties` and are tuneable without redeploy. Weight calibration (`mealprep.planner.scoring.weights.*`) remains the post-launch tuning track.

#### `PreferenceSubScore` — embedding-based cosine similarity

```
per_recipe_score(recipe, taste_vector):
    if recipe.embedding is null OR taste_vector is null:
        return 0.5                                       // neutral fallback (cold start, embedding pending)
    cos = cosine_similarity(recipe.embedding, taste_vector)
    return (cos + 1) / 2                                 // map [-1, 1] → [0, 1]

PreferenceSubScore(plan):
    return mean(per_recipe_score(slot.recipe, taste_vector_for(slot.eaters))
                for slot in plan.slots)
```

For per-person slots: use that user's `taste_vector` from `SoftPreferenceBundleDto`. For shared slots: use the merged household taste vector from `HouseholdMergeService` (computed on demand by averaging member vectors per [lld/household.md](household.md)).

Cold-start behaviour: when `taste_vector_status = 'pending'` or `'failed'` for a user, OR when a recipe's embedding hasn't landed yet, the per-recipe score returns `0.5` (neutral). With many missing embeddings the plan-level score regresses toward neutral — the planner doesn't get pulled toward arbitrary recipes by missing data.

`PreferenceProvider` resolves embeddings via `PreferenceQueryService.getTasteVector(userId)` and the recipe's `RecipeVersionDto.embedding`. Implemented as Postgres `<=>` operator at the SQL level for batch scoring; in-memory fallback for cases where the calculator is invoked outside a DB context.

#### `NutritionSubScore` — goal-driven asymmetric penalty

```
direction_score(actual, target, direction):
    if target is null OR target == 0:
        return 1.0                                       // no target = no penalty contribution
    deviation = (actual - target) / target

    match direction:
        UPPER_LIMIT:    penalty = max(0, deviation)      // overshoot bad, undershoot fine
        LOWER_FLOOR:    penalty = max(0, -deviation)     // undershoot bad, overshoot fine
        BOTH_BOUNDED:   penalty = abs(deviation)         // hit the target

    return max(0, 1 - penalty)

NutritionSubScore(plan, targets):
    macros = [calories, protein, fat, carbs, fibre, saturated_fat (if set)]
    return mean(direction_score(plan.weekly[m], targets[m].targetG, targets[m].direction)
                for m in macros if targets[m] is configured)
```

`direction` is read from `MacroTargetDto.direction` per [lld/nutrition.md §Goal-driven defaults](nutrition.md). The soft sub-score is **complementary to `NutritionFloorGate`** — the gate kills plans that breach hard floors (binary), this score rewards plans that hit targets in the right direction (continuous). A plan can clear the gate and still score poorly here, e.g. by overshooting calories on a `LOSE_WEIGHT` goal.

Per-day variance lives in the floor gate, not here. This score works on weekly aggregates.

#### `CostSubScore` — confidence-weighted cost vs budget

```
raw_cost_fit = clamp(1 - (estimated_weekly_cost / weekly_budget), 0, 1)
mean_confidence = confidence_weighted_mean(price_history.confidence
                                           for ingredient in plan.ingredients)
CostSubScore(plan) = 0.5 + (raw_cost_fit - 0.5) · mean_confidence
```

When `mean_confidence` is low (cold start, stale data), the score regresses toward `0.5` (neutral) — uninformed cost data does not pull plan selection toward stale prices. Reads from `ProvisionForPlannerBundleDto.supplierPricesByMappingKey` and the grocery module's price-history view per [lld/grocery.md §Tier 4](grocery.md#tier-4-price-history). **Locked.**

#### `VarietySubScore` — target-based per-dimension distinct count

```
per_dimension_score(plan, dimension, target):
    distinct = count_distinct(slot.recipe.tags[dimension] for slot in plan.slots)
    return min(1.0, distinct / target)

VarietySubScore(plan):
    return mean(
        per_dimension_score(plan, "cuisine", target=5),
        per_dimension_score(plan, "protein", target=4),
        per_dimension_score(plan, "cooking_method", target=3)
    )
```

Targets (5 / 4 / 3) are configurable via `mealprep.planner.scoring.variety.targets.*` and reflect "good variety for a typical week," not "every slot unique." A plan with 5 distinct cuisines, 4 distinct proteins, and 3 distinct cooking methods scores 1.0 — even with batch cooking spreading those across 21 slots. The `max_repeat = 2` per-recipe cap remains in the multiplicative `VarietyGate`, not here. **Locked.**

#### `BatchSubScore` — fewer cook sessions = better

```
BatchSubScore(plan):
    return 1 - (count_distinct(slot.batch_cook_session_id for slot in plan.slots) / len(plan.slots))
```

A week of one mega-batch cook scores near 1.0; a week of one cook per slot scores 0. Users who prefer cooking fresh daily zero out `w_batch` in `PlannerProperties` rather than complicating the formula — keeps the calculator simple and the per-user preference handled at the weight layer. **Locked.**

`batch_cook_session_id` is assigned during composition (per `RecipeMetadataDto.batchCookable` + lifestyle-config prep-day flags); this sub-score reads it.

#### `ProvisionsSubScore` — pantry utilisation, expiry-weighted

```
ProvisionsSubScore(plan, inventory):
    if pantry_tracking_enabled is false:
        return 0.5                                       // neutral when feature disabled

    covered_value = sum(min(demand[i], inventory[i].quantity) · waste_value(inventory[i])
                        for i in plan.ingredients if i in inventory)
    max_value     = sum(demand[i] · max_waste_value
                        for i in plan.ingredients)

    return clamp(covered_value / max_value, 0, 1)
```

`waste_value(item)` increases for items closer to expiry (linear scale: 1.0 for >7d, 2.0 for ≤3d, 3.0 for ≤1d). `max_waste_value = 3.0`. Disabled-pantry case returns `0.5` neutral per [lld/preference.md §PantryTracking](preference.md#lifestyle-config). **Locked.**

#### `TimeSubScore` — linear penalty for over-budget slots

```
slot_score(slot):
    if slot.recipe.total_time_mins ≤ slot.time_budget_min:
        return 1.0
    overshoot_ratio = (slot.recipe.total_time_mins - slot.time_budget_min) / slot.time_budget_min
    return max(0, 1 - overshoot_ratio)

TimeSubScore(plan):
    return mean(slot_score(slot) for slot in plan.slots)
```

A slot exactly at budget scores 1.0; 1.5× over scores 0.5; 2× over scores 0. The hard-filter at `BeamSearchConfig.maxTimeOvershootRatio = 1.5×` still kills extreme cases before they enter scoring; this gradient applies within the surviving range. **Locked.**

### `RollupBuilder` (Stage B)

```java
interface RollupBuilder {
    RollupSummaryDocument build(CandidatePlan plan, PlanCompositionContext context);
}
```

Per-candidate flat summary: per-day macros + cost + total time + violations; plus weekly aggregates. Pure function over the candidate plan and context — no DB lookups (all data already in `PlanCompositionContext`).

### `StageCInvoker` (Stage C — pick of N)

```java
interface StageCInvoker {
    StageCResult pickOne(List<CandidatePlanRollupDto> candidates,
                         PlanCompositionContext context, UUID traceId);
}

record StageCResult(int chosenIndex, String reasoning, AugmentationSource source) {}
```

Calls `AiService.execute(StageCPickTask)` with the candidates' rollups and the constraint summary. The task type:

```java
final class StageCPickTask implements AiTask<StageCPickResponse> {
    @Override public TaskType getTaskType() { return TaskType.PLAN_COMPOSITION; }   // mid tier (HLD §AI Model Tiers)
    @Override public String getSystemPrompt() { /* deferred — Out of Scope */ }
    @Override public PromptRef getUserPromptRef() {
        return new PromptRef("planner/stage-c-pick", Optional.empty());
    }
    @Override public Map<String, Object> getContext() {
        return Map.of(
            "candidates", candidatesRollups,                  // List<CandidatePlanRollupDto>
            "constraints_summary", constraintsSummary,        // String paragraph
            "household_size", householdSize,
            "week_start", weekStartDate.toString(),
            "trigger", trigger.name()
        );
    }
    @Override public ToolDefinition getToolSchema() { /* derived from StageCPickResponse via jsonschema-generator */ }
    @Override public Class<StageCPickResponse> getResponseType() { return StageCPickResponse.class; }
    @Override public UUID getUserId()  { return primaryUserId; }
    @Override public UUID getTraceId() { return traceId; }
    @Override public Optional<Duration> getTimeoutOverride() { return Optional.of(Duration.ofSeconds(20)); }
}

public record StageCPickResponse(
    int chosenIndex,                                   // 0..N-1
    String reasoning                                   // free-text, recorded in decision log
) {}
```

**Prompt content deferred — Out of Scope per [§Out of Scope](#out-of-scope).** The system message + user template body live under `src/main/resources/prompts/planner/stage-c-pick.txt` (per [lld/ai.md §Prompt loading](ai.md)), to be authored as a separate prompt-engineering exercise with its own eval set. This LLD specifies what's wired around the prompt, not the prompt itself.

On `AiUnavailable` (cost cap, key missing): `StageCInvoker` returns `StageCResult(chosenIndex = 0, reasoning = "AI ranking unavailable; deterministic top-scored candidate selected.", source = LLM)` — i.e. the top-scored candidate per the deterministic search, with `aiAugmented = false` flagged on the resulting plan. This is the **skip-and-flag** pattern from [style-guide §AI Service](style-guide.md#ai-service--graceful-degradation).

On `TransientAiFailureException`: same deterministic fallback — the failure is logged WARN and the plan ships flagged.

### `Phase2Augmenter`

```java
interface Phase2Augmenter {
    AugmentationResult augment(CandidatePlan chosenPlan,
                               PlanCompositionContext context, UUID traceId);
}

record AugmentationResult(
    List<Augmentation> applied,                 // post-filter — only those that survived the verifier
    List<Augmentation> discardedByVerifier,
    List<RefineDirectiveDto> emittedDirectives  // forwarded to OptimiserService (Stage D)
) {}

sealed interface Augmentation
    permits AddSnackAugmentation, IngredientSwapAugmentation, RepairAugmentation {}
```

The `Phase2AugmentationTask`:

```java
final class Phase2AugmentationTask implements AiTask<Phase2AugmentationResponse> {
    @Override public TaskType getTaskType() { return TaskType.PLAN_AUGMENTATION; }   // frontier tier
    @Override public String getSystemPrompt() { /* deferred — Out of Scope */ }
    @Override public PromptRef getUserPromptRef() {
        return new PromptRef("planner/phase2-augmentation", Optional.empty());
    }
    @Override public Map<String, Object> getContext() {
        return Map.of(
            "chosen_plan", chosenPlanRollup,
            "constraints_summary", constraintsSummary,
            "nutrition_gaps", nutritionGapsPerDay,
            "max_augmentations", 5,
            "max_refine_directives", 2
        );
    }
    @Override public Class<Phase2AugmentationResponse> getResponseType() { return Phase2AugmentationResponse.class; }
    /* … */
}

public record Phase2AugmentationResponse(
    List<AugmentationProposal> augmentations,
    List<RefineDirectiveProposal> refineDirectives) {}
```

**Prompt content deferred — Out of Scope.** Same file pattern: `src/main/resources/prompts/planner/phase2-augmentation.txt`.

Limits per [meal-planner.md §Phase 2 limits](../design/meal-planner.md#limits): max 5 augmentations, max 2 refine-directives. The augmenter takes the first N if the LLM exceeds either limit.

Every proposed augmentation passes through `AugmentationVerifier`, which runs the *same* `HardConstraintFilterService.check(...)` used by Stage A. Augmentations that fail are discarded silently and logged WARN — the LLM is never trusted to remember constraints.

On `AiUnavailable`: `AugmentationResult(applied = [], discardedByVerifier = [], emittedDirectives = [])` and the plan ships with `aiAugmented = false`. **Skip-and-flag.**

### `MidWeekReoptCoordinator`

```java
interface MidWeekReoptCoordinator {
    PlanDto reoptimise(ReoptRequest request, Plan currentActive,
                       PlanCompositionContext context);
}
```

Drives the same A→B→C→D pipeline scoped from `request.fromDate` onward. `PinningRules` derives the pinned slots from current state per [meal-planner.md §pinning rules](../design/meal-planner.md#pinning-rules):

| Slot state | Pinning |
|---|---|
| `EATEN`, `COOKED`, `COOKING` | Pinned. `pinnedReason` set. Score contribution preserved. |
| `PLANNED` (in the past, never logged) | Pinned to original; user can manually mark `SKIPPED`. |
| `PLANNED` (in the future, ≥ `request.fromDate`) | Regenerable — the search space. |
| `SKIPPED` | Pinned as skipped. Macro/cost contribution = 0. |

The new plan inherits pinned slots verbatim and re-runs Stage A only over regenerable slots. `Phase2Augmenter` runs on the recomposed plan as in initial generation. The reoptimisation prompt for Stage C mentions this is a re-opt (different reasoning frame per the optimisation-loop HLD); the prompt template handles that via context (`"trigger" = "MID_WEEK_REOPT"`).

---

## Listeners — converting external events to ReoptSuggestedEvent

The planner subscribes to four upstream events and converts each to a `ReoptSuggestedEvent` after filtering for materiality. Listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)` per the style guide. **Listeners do not auto-replace the active plan** — they create a `ReoptSuggestion` row and publish a `ReoptSuggestedEvent` for the notification module + UI to surface. The user confirms via `reoptimisePlan(...)`.

```java
@Component
@RequiredArgsConstructor
class PlannerReoptListeners {
    private final ReoptScopeBuilder scopeBuilder;
    private final ReoptSuggestionRepository suggestionRepo;
    private final ApplicationEventPublisher publisher;
    // …

    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onProvisionChanged(ProvisionChangedEvent event) { /* …filter + suggest */ }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onNutritionDiverged(NutritionIntakeDivergedEvent event) { /* … */ }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onPreferenceChanged(PreferenceChangedEvent event) { /* … */ }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onHardConstraintsChanged(HardConstraintsChangedEvent event) { /* …always material */ }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onHouseholdSettingsChanged(HouseholdSettingsChangedEvent event) { /* … */ }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onEquipmentChanged(EquipmentChangedEvent event)   { /* … */ }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onBudgetChanged(BudgetChangedEvent event)         { /* … */ }
}
```

### Trigger filters

Per [meal-planner.md §triggers](../design/meal-planner.md#triggers), not every event triggers a re-opt:

| Event | Material if… |
|---|---|
| `ProvisionChangedEvent` (sealed) | `ItemSpoiledEvent` or `ItemRanOutEvent` or `SubstitutionAcceptedEvent` affecting an unconsumed slot's recipe. `ItemAddedFromGroceryEvent` filtered out (stocking-up doesn't trigger). |
| `NutritionIntakeDivergedEvent` | `event.varianceBps ≥ 1500` (≥ 15% on any macro per HLD; threshold configurable). |
| `PreferenceChangedEvent` | Always considered (taste change → suggest). Soft — surfaced as a low-priority suggestion. |
| `HardConstraintsChangedEvent` | **Always material.** Per the HLD, hard-constraint changes auto-suggest re-opt. Suggestion priority: high. |
| `HouseholdSettingsChangedEvent` | Material when `changedFieldPaths` includes `slotDefaults.*`, `mealStructure.*`, `eatingWindow.*`. |
| `EquipmentChangedEvent` | Material when an equipment item that any active-plan recipe requires becomes unavailable. |
| `BudgetChangedEvent` | Material when the new weekly target differs from the active plan's `cost_estimate_gbp` by ≥ `tolerance_over`. |

`ReoptScopeBuilder` resolves the affected slot IDs (the slots whose recipes use the spoiled ingredient, the days after the diverged date, etc.) and returns them in `ReoptSuggestion.affectedSlotIds`.

### Idempotency and coalescing

Suggestions are unique per `(household_id, week_start_date, trigger_event_id)`. A re-fire of the same event is a no-op. Multiple distinct events for the same week within ~5 minutes coalesce into the most recent suggestion's `summary` (the listener updates the existing row rather than creating a new one if `created_at` is within a debounce window — `PlannerProperties.suggestionDebounceWindow`, default 5 minutes).

A `ReoptSuggestion.expires_at` is set to `weekStartDate + 7 days` — a week-old plan can no longer be regenerated; suggestions auto-expire via a `@Scheduled` sweep.

---

## REST Controllers

All endpoints under `/api/v1/plans/...` and `/api/v1/meal-slots/...`. `userId` resolved server-side from auth context per [technical-architecture.md §Frontend-Backend Contract](../design/technical-architecture.md#frontend-backend-contract). The controller split follows the pilot's precedent (multi-controller when sub-resources are clear).

### `PlansController` — `/api/v1/plans`

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/active?householdId=&weekStartDate=` | — | `PlanDto` | 200 / 404 |
| GET    | `/history?householdId=&weekStartDate=` | — | `List<PlanDto>` | 200 |
| GET    | `?householdId=&from=&to=&page=&size=` | — | `Page<PlanDto>` | 200 |
| GET    | `/{planId}` | — | `PlanDto` | 200 / 404 |
| POST   | `/generate` | `GeneratePlanRequest` | `PlanDto` | 201 / 400 / 409 / 422 |
| POST   | `/reoptimise` | `ReoptRequest` | `PlanDto` | 200 / 400 / 404 / 409 / 422 |
| POST   | `/{planId}/accept` | — | `PlanDto` | 200 / 404 / 409 |
| POST   | `/{planId}/reject` | `RejectPlanRequest` | `PlanDto` | 200 / 404 / 409 |
| POST   | `/{planId}/abandon` | `AbandonPlanRequest` | `PlanDto` | 200 / 404 / 409 |
| POST   | `/revert` | `RevertToPlanRequest` | `PlanDto` | 201 / 404 / 409 / 422 |
| GET    | `/feasibility?householdId=&weekStartDate=` | — | `FeasibilityCheckResultDto` | 200 |
| GET    | `/suggestions?householdId=&page=&size=` | — | `Page<ReoptSuggestionDto>` | 200 |
| POST   | `/suggestions/{suggestionId}/dismiss` | — | — | 204 / 404 |

`POST /generate` returns 201 because it creates a new resource. `POST /reoptimise` creates a new resource too but the URL doesn't carry the new ID — keeping the response 200 with the new plan in the body matches the pattern in [lld/preference.md](preference.md) where `apply…` returns 200 with the post-apply DTO. **Worth user review.**

### `MealSlotsController` — `/api/v1/meal-slots`

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/{slotId}` | — | `MealSlotDto` | 200 / 404 |
| POST   | `/{slotId}/state` | `MarkSlotStateRequest` | — | 204 / 404 / 409 / 422 |

Slot state is the only slot-level write. State transitions other than `markSlotState` are not exposed — slot recipe content is immutable per the lifecycle, and the only path to change a slot's recipe is plan regeneration.

### `AdminPlannerController` — `/api/v1/admin/planner`

Decision-log queries for administrative + debugging use. Auth-gated to admin role.

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/decisions?planId=` | — | `List<DecisionLogEntryDto>` | 200 |
| GET    | `/decisions/{decisionId}/trace` | — | `List<DecisionLogEntryDto>` | 200 |

Backed by `core.DecisionLogService.getByPlanId(...)` and `core.DecisionLogService.getByTraceId(...)` — the planner does not own the decision log table; it only exposes a narrow read endpoint scoped to plans. **Worth user review** that the admin endpoint belongs here vs in core's own admin controller; placed here because the natural query is "what was decided about *this plan*".

### Error responses

All error responses use RFC 9457 `ProblemDetail`. Module-specific exceptions (handled in the project-wide `GlobalExceptionHandler`):

| Exception | Status | `type` URI |
|---|---|---|
| `PlanNotFoundException` | 404 | `/problems/plan-not-found` |
| `ReoptSuggestionNotFoundException` | 404 | `/problems/reopt-suggestion-not-found` |
| `MealSlotNotFoundException` | 404 | `/problems/meal-slot-not-found` |
| `InvalidPlanStateTransitionException` | 409 | `/problems/invalid-plan-state-transition` |
| `InvalidSlotStateTransitionException` | 409 | `/problems/invalid-slot-state-transition` |
| `ConcurrentGenerationInProgressException` | 409 | `/problems/concurrent-generation` |
| `PlanInfeasibleException` | 422 | `/problems/plan-infeasible` |
| `RevertTargetNotInHistoryException` | 422 | `/problems/revert-target-invalid` |
| `OptimisticLockException` (JPA) | 409 | `/problems/optimistic-lock` |
| `MethodArgumentNotValidException` | 400 | `errors[]` extension on ProblemDetail |

Module root: `PlannerException extends MealPrepException`.

---

## Validation

Standard Jakarta annotations applied at request-record level: `@NotNull`, `@NotBlank`, `@Size`, `@Min` / `@Max` (e.g. `@Min(1) @Max(100)` on pagination size).

Custom validators in `validation/`:

- **`@ValidPlanGenerationRequest`** (class-level on `GeneratePlanRequest`) — asserts `weekStartDate` is a Monday (configurable via `PlannerProperties.weekStartDayOfWeek` — default `MONDAY`) and `weekStartDate` is not more than 8 weeks in the past or 4 weeks in the future.
- **`@ValidSlotState`** (on `MarkSlotStateRequest`) — asserts the requested transition is allowed by `PlanStateMachine` (e.g. `EATEN → COOKING` is rejected at validation time, not at the service layer, so the controller returns 400 not 422).

State-machine validation is deliberately split: simple per-record allowed-state checks live as Jakarta validators (400); cross-aggregate checks (is the plan still active? is the slot pinned because it's already eaten?) live in the service and throw 409.

---

## Events

### Published

```java
public sealed interface PlannerEvent
    extends MealPrepEvent
    permits PlanGeneratedEvent, PlanAcceptedEvent, PlanSupersededEvent,
            PlanCompletedEvent, PlanRejectedEvent, PlanAbandonedEvent,
            ReoptTriggeredEvent, ReoptSuggestedEvent {

    UUID planId();
    UUID traceId();
    Instant occurredAt();
}

public record PlanGeneratedEvent(
    UUID planId, UUID householdId, LocalDate weekStartDate, int generation,
    TriggerKind trigger, UUID triggerEventId, UUID decisionId,
    boolean coldStart, boolean aiAugmented, boolean qualityWarning,
    UUID traceId, Instant occurredAt
) implements PlannerEvent {}

public record PlanAcceptedEvent(
    UUID planId, UUID householdId, LocalDate weekStartDate,
    UUID traceId, Instant occurredAt
) implements PlannerEvent {}

public record PlanSupersededEvent(
    UUID planId, UUID replacedByPlanId, UUID householdId, LocalDate weekStartDate,
    UUID traceId, Instant occurredAt
) implements PlannerEvent {}

public record PlanCompletedEvent(
    UUID planId, UUID householdId, LocalDate weekStartDate,
    UUID traceId, Instant occurredAt
) implements PlannerEvent {}

public record PlanRejectedEvent(
    UUID planId, UUID householdId, LocalDate weekStartDate, String reason,
    UUID traceId, Instant occurredAt
) implements PlannerEvent {}

public record PlanAbandonedEvent(
    UUID planId, UUID householdId, LocalDate weekStartDate, String reason,
    UUID traceId, Instant occurredAt
) implements PlannerEvent {}

public record ReoptTriggeredEvent(
    UUID planId, UUID householdId, LocalDate weekStartDate,
    ReoptTriggerKind trigger, UUID triggerEventId,
    UUID traceId, Instant occurredAt
) implements PlannerEvent {}

public record ReoptSuggestedEvent(
    UUID planId, UUID householdId, LocalDate weekStartDate,
    UUID suggestionId, ReoptTriggerKind trigger, UUID triggerEventId,
    List<UUID> affectedSlotIds, String summary,
    UUID traceId, Instant occurredAt
) implements PlannerEvent {}
```

`PlannerEvent` extends `MealPrepEvent` (sealed marker in `core.events` per [style-guide §core](style-guide.md#module-package-structure)) so cross-cutting infrastructure (decision log, notification listeners) can pattern-match on the planner family.

`PlanCompletedEvent` is fired by a `@Scheduled` weekly sweep that runs every Monday morning and transitions the previous week's `ACTIVE` plans whose all slots are in terminal state to `COMPLETED`. If any slot is still `PLANNED` after the week's end, the plan stays `ACTIVE` until the user marks it explicitly — the sweep does not auto-abandon.

Listeners across the system: notification (alerts the household), grocery (handles regenerated plans — invalidates the shopping list), nutrition (auto-confirm intake on `MealCookedEvent`, which is *not* a planner event but is published by the cook listener that the planner installs).

### Consumed

Per [§Listeners](#listeners--converting-external-events-to-reoptsuggestedevent) above. The planner consumes:

- `ProvisionChangedEvent` (sealed) and siblings
- `NutritionIntakeDivergedEvent`
- `PreferenceChangedEvent`, `HardConstraintsChangedEvent`
- `HouseholdSettingsChangedEvent`
- `EquipmentChangedEvent`, `BudgetChangedEvent`

It does **not** consume `MealCookedEvent` or `MealConsumedEvent` directly — the cook listener that owns slot-state transitions for cook events lives in this module (`MealCookedListener`) and calls `markSlotState(...)` internally; that's an internal wiring detail, not a cross-module subscription.

---

## Read pattern

Per the [Bundle-DTO-for-planner convention](style-guide.md#bundle-dto-for-planner-convention), every domain module the planner queries during plan composition exposes a single bundled read method. The planner builds `PlanCompositionContext` once per generation:

```java
record PlanCompositionContext(
    UUID householdId,
    LocalDate weekStartDate,
    Household household,                                               // members + settings
    Map<UUID, HardConstraintsDto> hardConstraintsByUserId,             // PreferenceQueryService.getHardConstraintsByUserIds
    Map<UUID, SoftPreferenceBundleDto> softPrefsByUserId,              // PreferenceQueryService.getSoftPreferencesByUserIds
    MergedSoftPreferencesDto mergedHouseholdPrefs,                     // HouseholdMergeService.mergeSoftPreferencesForUsers
    Map<UUID, NutritionForPlannerBundleDto> nutritionByUserId,         // NutritionQueryService.getForPlannerByUserIds (when added)
    ProvisionForPlannerBundleDto provisions,                           // ProvisionQueryService.getBundle
    HouseholdSettingsDto householdSettings,                            // HouseholdQueryService.getSettings
    LifestyleConfigDocument lifestyle,                                 // PreferenceQueryService.getLifestyleConfig (primary user)
    RecipePoolSnapshot recipePool,                                     // RecipeQueryService.search → cached for the run
    Map<String, BigDecimal> ingredientPriceConfidenceByMappingKey,     // GroceryQueryService.getPriceConfidence (planned)
    UUID traceId,
    UUID decisionId
) {}
```

`RecipePoolSnapshot` is a planner-owned snapshot — recipes loaded once and *not* re-read mid-flight per [meal-planner.md §failure modes](../design/meal-planner.md#failure-modes) ("Plan composed against snapshot taken at Stage A start"). Concurrent recipe edits are caught at slot rendering, not at composition.

The planner injects (via `PlannerModule` constructor):

| Injects | Purpose |
|---|---|
| `PreferenceQueryService` | hard/soft constraints, lifestyle |
| `HardConstraintFilterService` | the deterministic safety net used by Stage A |
| `NutritionQueryService` + `NutritionFloorGateService` | targets, divergence threshold, multiplicative gate |
| `ProvisionQueryService` | inventory, equipment, budget, supplier prices |
| `HouseholdQueryService` + `HouseholdMergeService` | members, settings, merged-preference shared-slot computation |
| `RecipeQueryService` | candidate pool |
| `OptimiserService` (adaptation pipeline) | Stage D refine-directives |
| `AiService` | Stage C pick + Phase 2 augmentation |
| `core.DecisionLogService` | one row per loop iteration (decision_log table per [optimisation-loop.md](../design/optimisation-loop.md#decision-log)) |
| `core.LockService` | single-flight per `(household, week)` |

No update-service injections — the planner is read-only against the data models.

---

## Business Logic Flows

### Flow 1: Plan generation

`POST /api/v1/plans/generate` → `generatePlan(request)`. Service-impl method is **not** annotated `@Transactional` at the top — the AI calls in Stages C and D must be outside a transaction per [style-guide §AI Service](style-guide.md#ai-service--graceful-degradation) (Tier 1 decision: AI calls in-transaction = no). Sub-operations have their own `@Transactional` boundaries.

1. Acquire single-flight lock: `core.LockService.tryLock("planner:generate", "%s:%s".formatted(householdId, weekStartDate))`. If lock unavailable → `ConcurrentGenerationInProgressException` (409).
2. Determine generation number and predecessor: `generation = max(existing) + 1`, `replacesPlanId = findActiveByHouseholdAndWeek(...)`.
3. Build `PlanCompositionContext` (one round trip per service via the bundle DTOs — see [Read pattern](#read-pattern)).
4. Run `ConstraintFeasibilityCheck.check(context)`. If `feasible = false` and `request.forceRegenerateIfActive = false`, return early with a draft plan flagged `qualityWarning = true`. The user-facing flow runs the feasibility check *first* via `GET /feasibility` so this in-flight branch is rare.
5. Run cold-start gate: catalogue-size check. If below threshold, trigger `RecipeDiscovery` + `RecipeGeneration` pre-step via the recipe module ([adaptation-pipeline.md](adaptation-pipeline.md) — concurrent agent; reference at the interface level). Sets `coldStart = true`.
6. **Decision-log row 1.** Write to `decision_log` via `core.DecisionLogService.write(...)` with `scale = "week"`, `triggered_by = "user"`, iteration 1.
7. **Stage A.** `BeamSearchEngine.search(context, config)` returns `List<CandidatePlan>` of length up to `topN`.
8. **Stage B.** `RollupBuilder.build(plan, context)` for each candidate.
9. **Stage C.** `StageCInvoker.pickOne(...)` — outside any tx. On `AiUnavailable`, deterministic top-scored fallback. Records reasoning in the decision log.
10. **Phase 2.** `Phase2Augmenter.augment(...)` — outside any tx. Augmentations verified against `HardConstraintFilterService` post-hoc; failures discarded silently and logged WARN.
11. **Stage D (optional).** For each `RefineDirectiveDto`, call `OptimiserService.adapt(directive)` synchronously, wait for the adapted recipe, re-run Stage A on the affected slot. Bounded by iteration budget (3 cycles per [optimisation-loop.md §Iteration budget](../design/optimisation-loop.md#iteration-budget)).
12. **Persist.** Open a `@Transactional` write block: insert `Plan` + cascade `Day`, `MealSlot`, `ScheduledRecipe`. Status starts as `GENERATED`. Score breakdown + rollup serialised to JSONB columns.
13. Publish `PlanGeneratedEvent` after commit.
14. Release the lock (always, even on failure — finally block).
15. Return the `PlanDto`.

Steps 7–11 are deterministic + AI; steps 12–13 are the only DB write boundary. Trace ID is set once at step 1 and threaded via `MDC` and method args across every helper.

### Flow 2: Mid-week re-optimisation

`POST /api/v1/plans/reoptimise` → `reoptimisePlan(request)`. Same outer shape as Flow 1.

1. Acquire single-flight lock on `(householdId, weekStartDate)`.
2. Load the current active plan with `findWithDaysByHouseholdIdAndWeekStartDateAndStatus(..., ACTIVE)`.
3. Build `PlanCompositionContext`.
4. `MidWeekReoptCoordinator.reoptimise(request, currentActive, context)` — drives the same A→B→C→D pipeline but:
   - `PinningRules.derive(currentActive, request.fromDate)` produces the pinned-slot map.
   - `BeamSearchEngine.search(context, config)` is called with the pinned slots' scheduled recipes pre-filled — the search runs only over regenerable slots.
   - Stage C's task context carries `"trigger" = "MID_WEEK_REOPT"` so the prompt knows the reasoning frame is "preserve continuity, fix the disruption."
5. Persist the new plan with `generation = current + 1`, `replacesPlanId = current.id`.
6. Transition the current plan: `ACTIVE → SUPERSEDED`. Updated in the same write tx. Partial unique index allows the new plan to take `ACTIVE` immediately after.
7. Publish `PlanSupersededEvent` for the old, `PlanGeneratedEvent` (via `acceptPlan` if the user pre-confirmed the suggestion, otherwise the new plan stays in `GENERATED` awaiting accept) for the new.
8. If `request.reoptSuggestionId` is non-null, transition the suggestion to `ACCEPTED`.

The HLD is intentionally explicit ("user always confirms"). For v1, `reoptimisePlan` produces a `GENERATED` plan and the user clicks accept to promote it. The notification-driven flow is: event → suggestion → user clicks "regenerate" in UI → `reoptimisePlan` → `GENERATED` → user clicks "accept" → `acceptPlan` → `ACTIVE`. Two confirmations is the safe default; collapsing them into one is **worth user review** (the HLD is silent).

### Flow 3: Accept / reject / abandon

Each is one method, `@Transactional`, transitions the plan via `PlanStateMachine`, publishes the corresponding event.

`acceptPlan`: `GENERATED → ACTIVE`. Touches the partial unique index — if a different active plan exists for the same `(household, week)` the unique constraint fires and JPA raises `DataIntegrityViolationException`. Mapped to 409. (Should never happen in practice because `generatePlan` supersedes the current active inside the lock.)

`rejectPlan`: `GENERATED → REJECTED`. Sets `rejectedReason`. Idempotent — re-rejecting a rejected plan is a no-op; rejecting a non-`GENERATED` plan throws `InvalidPlanStateTransitionException`.

`abandonPlan`: `ACTIVE → ABANDONED`. Sets `abandonedReason`. Mid-week revert is a different flow.

### Flow 4: Revert

`POST /api/v1/plans/revert` → `revertToPlan(request)`. Copy-forward semantics per [meal-planner.md §revert](../design/meal-planner.md#plan-history-and-revert).

1. Acquire single-flight lock.
2. Load `targetHistoricalPlan` by ID. If `targetHistoricalPlan.householdId` differs from the caller's household → `RevertTargetNotInHistoryException` (422).
3. Load the current active plan for the same `(household, weekStartDate)`.
4. Create a new plan: `generation = currentActive.generation + 1`, `replacesPlanId = currentActive.id`, status `GENERATED`. Days/slots/scheduled-recipes copied from `targetHistoricalPlan`. `score_breakdown` and `rollup_summary` recomputed in case the data models have changed since the target was generated.
5. Run `HardConstraintFilterService` over every copied scheduled recipe. Any that now fails (allergy added, ingredient now banned) is removed from the slot and the slot's `state` is reset to `PLANNED` with `scheduledRecipe = null`. Per HLD: surface a warning ("3 ingredients no longer available") via the response payload (TBD; UI consumes the rollup summary to render).
6. Run `Phase2Augmenter` over the copied plan to fill any newly empty slots from step 5.
7. Persist; transition the current active to `SUPERSEDED`; promote the new plan to `GENERATED` (user accepts as Flow 3).

### Flow 5: Slot state transitions

`POST /api/v1/meal-slots/{slotId}/state` → `markSlotState(request)`. `@Transactional`.

1. Load the slot via `findByIdAndPlanId(slotId, planId)`. Reject if not in an active plan.
2. `PlanStateMachine.allowedSlotTransition(currentState, newState)` — pure function, throws `InvalidSlotStateTransitionException` (409) on violation.
3. Update the slot's `state`, set `pinnedReason` if relevant.
4. Bump the parent `Plan.version` via `entityManager.lock(plan, OPTIMISTIC_FORCE_INCREMENT)`. This causes a concurrent re-opt running off the read-then-decide pattern to abort with 409 if a slot transitions mid-flight.
5. Publish no planner event — slot-state transitions are observed by the cook listener / nutrition logger via the `MealCookedEvent` path that triggered this call. (`markSlotState` is the *output* of those events, not a re-publication.)

Allowed slot transitions:

```
PLANNED → COOKING | SKIPPED
COOKING → COOKED  | SKIPPED
COOKED  → EATEN
EATEN, SKIPPED — terminal
```

A `MealCookedEvent` consumed elsewhere triggers `markSlotState(slotId, COOKING)` then `markSlotState(slotId, COOKED)` in two transactions. The cook listener owns that orchestration — see `MealCookedListener` in `internal/`.

### Flow 6: Re-opt suggestion lifecycle

Listener fires (Flow per [§Listeners](#listeners--converting-external-events-to-reoptsuggestedevent) above):

1. `@TransactionalEventListener(AFTER_COMMIT)` receives event.
2. Materiality filter. If not material, return.
3. Lookup existing `ReoptSuggestion` by `(householdId, weekStartDate, triggerEventId)`. If found and `status = PENDING` and within debounce window → update `affectedSlotIds`, return.
4. Otherwise insert a new `ReoptSuggestion` with `status = PENDING`, `expiresAt = weekStartDate + 7 days`.
5. Publish `ReoptSuggestedEvent`. The notification module surfaces it.

User clicks "regenerate" in the UI → `reoptimisePlan(...)` (Flow 2). User clicks "dismiss" → `dismissSuggestion(...)` → status `DISMISSED`. A `@Scheduled` job sweeps `findAllByStatusAndExpiresAtBefore(PENDING, now)` daily and transitions stale suggestions to `EXPIRED`.

### Flow 7: Stage D refine-directive

Inside Flow 1 step 11. Pre-conditions: Phase 2 emitted at least one `RefineDirectiveDto` and the iteration count is below 3.

1. For each directive, `OptimiserService.adapt(directive)` is called *synchronously*. The optimiser belongs to the adaptation pipeline ([lld/adaptation-pipeline.md](adaptation-pipeline.md)).
2. The optimiser returns either an adapted recipe ID (success) or an `InfeasibleDirective` signal. On success, swap the affected slot's `scheduledRecipe.recipeVersionId` to the adapted version.
3. Re-run `BeamSearchEngine.search(...)` *only over the affected slot* (single-slot beam-search, width 1) to pick up the new recipe in scoring context.
4. Decision-log row written for the iteration.
5. Loop until budget (3 cycles) exhausted or no more directives emitted.

`OptimiserService` injection is the only update-service-shaped dependency; it is *not* a data-model update — it produces an adapted *recipe*, not a constraint mutation.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All service-impl writes; reads are `readOnly = true`. `generatePlan` and `reoptimisePlan` are *not* `@Transactional` at the outermost — the AI calls in Stages C and D run outside any tx. Sub-steps have their own boundaries. |
| Single-flight per `(household_id, week_start_date)` | `core.LockService.tryLock("planner:generate", ...)` wrapping `pg_try_advisory_xact_lock`. Always paired with a `try/finally` release. |
| Optimistic locking | `@Version Long version` on `Plan` and `ReoptSuggestion`. Children inherit via the parent — `markSlotState` does `entityManager.lock(plan, OPTIMISTIC_FORCE_INCREMENT)` so concurrent re-opt aborts. |
| Pessimistic locking | None at row level. Advisory lock is the only pessimistic mechanism. |
| Cross-module write transactions | The planner does no cross-module writes (read-only against data models). Stage D's `OptimiserService.adapt(...)` is a synchronous call to a different module's tx; the planner doesn't span the boundary. |
| Read snapshot | `RecipePoolSnapshot` cached per generation — recipes loaded once and pinned. Concurrent recipe edits are caught at *slot rendering* (the slot still serves the old version ID; the recipe module's version-history endpoint is what reveals the drift). |
| Listener tx | `@TransactionalEventListener(phase = AFTER_COMMIT)`. Listener writes to `planner_reopt_suggestions` open a fresh tx (REQUIRES_NEW). The listener is **not** in the publisher's tx — events fire after commit. |
| Single-active invariant | DB-level partial unique index `uq_planner_plans_active_per_household_week`. App-level `acceptPlan` transitions the predecessor to `SUPERSEDED` in the same tx — the unique index is the safety net. |

---

## Failure Modes

| Failure | When | Response |
|---|---|---|
| No viable plan (Stage A returns empty) | Constraints over-restrictive | `ConstraintFeasibilityCheck` runs first; if feasibility fails, the plan is generated with `qualityWarning = true` and unfilled slots, never silently degraded. |
| All plans score below threshold (top score < 0.4) | Soft constraints over-restrictive | Surface relaxation suggestions ranked by score recovered per unit constraint loosened (via `FeasibilityCheckResultDto.resolutions`). |
| `AiUnavailable` at Stage C | Cost cap, key missing | Skip-and-flag: deterministic top-scored candidate, `aiAugmented = false`, `reasoning = "AI ranking unavailable"`. Per [style-guide §AI Service](style-guide.md#ai-service--graceful-degradation). |
| `AiUnavailable` at Phase 2 | Same | Skip-and-flag: empty augmentation list, plan ships unaugmented. |
| `TransientAiFailureException` at Stage C | Network blip, parse failure | Logged WARN; deterministic fallback. Same outcome as `AiUnavailable` for the user. |
| Phase 2 returns invalid output (allergen, malformed JSON) | LLM error | `AugmentationVerifier` discards the bad augmentation silently and logs WARN. Per HLD failure-modes "retry once with explicit constraint reminder, then accept the un-augmented plan" — the corrective re-prompt is owned by `AiService`'s retry policy ([lld/ai.md §Flow 1](ai.md)). |
| Concurrent generation on same scope | User clicks regenerate twice, or event arrives during user-initiated run | `LockService.tryLock` fails → `ConcurrentGenerationInProgressException` (409). |
| Generation timeout (Stage A > 30s) | Catalogue too large | `BeamSearchEngine` honours `BeamSearchConfig.timeout`; on timeout, retry once with width halved; on second timeout, degrade to greedy selection (width 1). Plan flagged with `qualityWarning = true` for the latter. |
| Stage C timeout (LLM > 20s) | API slow | `AiTask.getTimeoutOverride() = 20s`; `AiService` retries per its policy then surfaces `TransientAiFailureException`. Falls back as above. |
| Plan goes stale during composition (concurrent recipe edit) | Concurrent recipe edit | Plan composed against `RecipePoolSnapshot`; doesn't re-read mid-flight. Stale recipes caught at slot rendering. |
| Mid-week re-opt during active Phase 1 | Race between event listener and user-initiated regeneration | Listener writes the suggestion row only; no re-opt run. The user-initiated run completes first. The next user click on the suggestion is what runs the re-opt. |
| Refine-directive infeasible at recipe level | Optimiser can't satisfy directive | `OptimiserService.adapt` returns `InfeasibleDirective`; planner picks a different candidate from the top-N or stops refining. |
| Iteration budget exhausted with directive still pending | Stage D loop hit 3 cycles | Accept current candidate; log unmet directive in `decision_log` with `emitted_directive` populated and `chosen.source = "iteration-budget-exhausted"`. |
| `OptimisticLockException` on `markSlotState` | Concurrent re-opt force-incremented the plan | 409 ProblemDetail; UI re-fetches and retries. |
| Revert target lacks a recipe still in catalogue | Recipe archived since target plan generated | The slot becomes `scheduledRecipe = null`; Phase 2 fills it in. If Phase 2 cannot, the slot ships empty with `qualityWarning = true`. |
| Listener processes the same event twice | Spring delivery is at-least-once | Idempotent via the suggestion's unique key `(household, week, triggerEventId)`. |

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Names follow `methodName_scenario_expected`. Test code itself is out of scope here; the list below is the spec.

### Unit

| Class | Verifies |
|---|---|
| `PlannerServiceImplTest` | All `PlanQueryService` and `PlannerService` happy paths and error mappings, with mocked repositories, mocked `BeamSearchEngine`, mocked `StageCInvoker`/`Phase2Augmenter`, mocked `LockService`. |
| `PlanStateMachineTest` | Pure logic. Every legal and illegal plan transition and slot transition. Includes `markSlotState` matrix. |
| `BeamSearchEngineTest` | Beam invariant: at every step, beam size ≤ width; correct top-N order at termination. Empty pool → empty result. Pinned slots are respected. |
| `HardFilterRunnerTest` | Allergic ingredient filtered. Equipment unavailable filtered. Time overshoot ≥ 1.5× filtered. Per-eater filter for shared slots. |
| `ScoringEngineTest` | Composite is the sum of weighted sub-scores when both gates pass. Composite is 0 when either gate fails. Weights from properties are applied (round-trip property changes). |
| `PreferenceSubScoreTest` | Cosine-similarity placeholder produces deterministic outputs on canonical fixtures (small taste profile + small recipe set). |
| `NutritionSubScoreTest` | Convergence math: perfect-target plan scores 1.0; double-target plan scores 0; mixed plan reproduces the formula. |
| `CostSubScoreTest` | Confidence regression collapses score to 0.5 when mean confidence is 0; preserves raw score when mean confidence is 1. |
| `VarietySubScoreTest` | Distinct (cuisine, protein, method) tuples / slot count. Identical-recipe plan scores 1/N. |
| `BatchSubScoreTest` | One-session plan scores high; one-cook-per-slot plan scores 0. |
| `ProvisionsSubScoreTest` | Pantry coverage formula. PantryTracking off → returns 0.5. |
| `TimeSubScoreTest` | Fraction of slots within budget. Edge: zero slots returns 1.0 (vacuous truth). |
| `RollupBuilderTest` | Daily totals sum to weekly. Cost confidence aggregated correctly. Stale-ingredient count populated. |
| `ConstraintFeasibilityCheckTest` | Pool-size threshold detects under-3-candidates slots. Conflict-type classification deterministic. Resolution ranking. |
| `AugmentationVerifierTest` | Allergen-introducing augmentation discarded. Time-budget-exceeding augmentation discarded. Valid augmentation passes. |
| `PinningRulesTest` | Eaten/cooked/cooking → pinned. Past planned → pinned. Future planned → regenerable. Skipped → pinned-skipped. |
| `PlannerReoptListenersTest` | Materiality filter for each event type. Idempotency on duplicate triggerEventId. Debounce window updates existing suggestion. |
| `StageCInvokerTest` | Happy path returns chosen index + reasoning. `AiUnavailable` returns deterministic top-scored fallback. `TransientAiFailureException` falls back to deterministic. Out-of-range chosenIndex falls back. |
| `Phase2AugmenterTest` | Augmentation cap (5) enforced. Refine-directive cap (2) enforced. Verifier discards forwarded correctly. |
| `PlanGenerationCounterTest` | First plan: generation = 1. Subsequent: generation = max + 1 with `replacesPlanId` set. |

### Integration

| Class | Verifies |
|---|---|
| `PlansControllerIT` | Full HTTP cycle over MockMvc: GET active/by-id/history/range (200/404), generate (201), accept/reject/abandon (200/404/409), revert (201), pagination, ProblemDetail shape, single-flight 409 on concurrent generate. |
| `MealSlotsControllerIT` | GET (200/404), state transitions (204/409 illegal transition/422 cross-aggregate), parent-version bump observed. |
| `AdminPlannerControllerIT` | Decision-log queries return rows tied to the plan's trace. Auth-gating (admin only). |
| `PlannerServiceIT` | Service-layer end-to-end against real DB: full A→B→C→D pipeline with `TestAiService` returning canned candidates. Plan persisted with all four nested levels. `PlanGeneratedEvent` published exactly once after commit. |
| `MidWeekReoptIT` | Mid-week re-opt preserves pinned slots; new plan replaces active; `PlanSupersededEvent` payload carries old + new IDs. |
| `RevertIT` | Copy-forward revert creates a new plan with `generation = max+1`; old active becomes `SUPERSEDED`; `HardConstraintFilterService` strips now-banned recipes; warning surfaced. |
| `ListenerIT` | `ProvisionChangedEvent.ItemSpoiledEvent` for an unconsumed slot creates a `PENDING` suggestion; same event re-fired updates the existing suggestion (no duplicate). `ItemAddedFromGroceryEvent` does not create a suggestion. `HardConstraintsChangedEvent` always creates one. |
| `SingleFlightIT` | Two concurrent `generatePlan` calls on the same `(household, week)` — second receives 409 `ConcurrentGenerationInProgressException`. |
| `UniqueActiveConstraintIT` | Manually inserting a second `ACTIVE` plan for the same `(household, week)` violates the partial unique index. |
| `FlywayMigrationIT` | Boots Postgres, runs all planner migrations, validates schema matches the JPA mapping (`spring.jpa.hibernate.ddl-auto = validate`). |
| `EventPublicationIT` | `PlanGeneratedEvent`, `PlanAcceptedEvent`, `PlanSupersededEvent`, `PlanCompletedEvent`, `ReoptSuggestedEvent` each published exactly once after commit. Listener failures don't roll back planner state. |
| `AiUnavailableDegradationIT` | `TestAiService` returns `AiUnavailable`; plan generates with `aiAugmented = false`, deterministic top-scored candidate selected, no augmentations. |
| `ScoringWeightConfigIT` | Override `mealprep.planner.scoring.weights.preference` in test profile and verify `ScoringEngine` picks up the new value (round-trip via `PlannerProperties`). |
| `DecisionLogIntegrationIT` | Each iteration of the loop writes one row to `decision_log`. `parent_decision_id` chain reconstructible across Stage A → Stage D refines. Trace ID propagates. |

---

## Configuration

```java
@ConfigurationProperties(prefix = "mealprep.planner")
@Validated
public record PlannerProperties(
    DayOfWeek weekStartDayOfWeek,                       // default MONDAY
    @Min(1) int beamWidth,                              // default 20
    @Min(1) int topN,                                   // default 5
    @Min(1) int minPoolPerSlot,                         // default 3
    @Min(1) int maxPoolPerSlot,                         // default 50
    @DecimalMin("1.0") @DecimalMax("3.0")
    BigDecimal maxTimeOvershootRatio,                   // default 1.5
    @Min(1) int maxAugmentations,                       // default 5
    @Min(0) int maxRefineDirectives,                    // default 2
    @Min(1) int iterationBudget,                        // default 3
    Duration stageATimeout,                             // default 30s
    Duration stageCTimeout,                             // default 20s
    Duration suggestionDebounceWindow,                  // default 5m
    int nutritionDivergenceThresholdBps,                // default 1500 (15%)
    int catalogueMinSizeMultiplier,                     // default 3 (per slot)
    ScoringWeights weights
) {
    public record ScoringWeights(
        BigDecimal preference, BigDecimal nutrition, BigDecimal cost,
        BigDecimal variety, BigDecimal time, BigDecimal batch, BigDecimal provisions
    ) {}
}
```

```properties
mealprep.planner.beam-width=20
mealprep.planner.top-n=5
mealprep.planner.min-pool-per-slot=3
mealprep.planner.max-pool-per-slot=50
mealprep.planner.max-time-overshoot-ratio=1.5
mealprep.planner.max-augmentations=5
mealprep.planner.max-refine-directives=2
mealprep.planner.iteration-budget=3
mealprep.planner.stage-a-timeout=PT30S
mealprep.planner.stage-c-timeout=PT20S
mealprep.planner.suggestion-debounce-window=PT5M
mealprep.planner.nutrition-divergence-threshold-bps=1500
mealprep.planner.catalogue-min-size-multiplier=3
# v1 uniform weights — Initial Weights v1 per HLD §scoring
mealprep.planner.weights.preference=0.143
mealprep.planner.weights.nutrition=0.143
mealprep.planner.weights.cost=0.143
mealprep.planner.weights.variety=0.143
mealprep.planner.weights.time=0.143
mealprep.planner.weights.batch=0.143
mealprep.planner.weights.provisions=0.143
```

---

## Decisions worth flagging (consolidated)

These are the spots where the LLD has chosen something the HLD didn't pin, or where the HLD's text leaves room. Each is repeated in context above.

1. **Plan storage shape: Option B (normalised) — LOCKED (2026-05-07).** Option A migration preserved as documentation, does not ship.
2. **Sub-score formulas (seven of them).** All locked 2026-05-07. Numerical constants tunable via `PlannerProperties` without redeploy; weights remain on the post-launch calibration track.
3. **Stage C and Phase 2 prompt content.** `StageCPickTask` and `Phase2AugmentationTask` types specified; prompt bodies deferred under `prompts/planner/*.txt`. **Out of scope.**
4. **`getPlansBetween` returning `Page<PlanDto>` instead of `List<PlanDto>`.** UI paginates. **Worth user review.**
5. **`POST /reoptimise` returning 200 instead of 201.** Mirrors the apply-pattern in [lld/preference.md](preference.md) (return updated DTO). **Worth user review.**
6. **`AdminPlannerController` location.** Decision-log queries scoped to plans live in this module rather than core. **Worth user review.**
7. **Two-step re-opt confirmation (suggestion → reoptimise → accept).** v1 default; collapsing to one step is **worth user review.**
8. **`HardConstraintsChangedEvent` always material; `PreferenceChangedEvent` always material at low priority.** Per HLD safety wording. The remaining filters use the materiality threshold from properties.

---

## Out of Scope

Deferred deliberately — these belong elsewhere or to a later phase:

- **Specific scoring formula math.** Each sub-score has a placeholder and is flagged for calibration after ~10 generated plans. The interface is fixed; the bodies tune.
- **Plan storage shape final decision.** Both options analysed and migrations specified. The user picks.
- **Stage C LLM prompt content.** `StageCPickTask` defines the AiTask shape, `getContext()` keys, and the response record. Prompt body is a separate prompt-engineering exercise per [lld/ai.md §Out of Scope](ai.md).
- **Phase 2 augmentation prompt content.** Same — `Phase2AugmentationTask` shape specified; body deferred.
- **Specific weight values.** v1 uniform `1/7` per HLD's "Initial Weights v1". Calibration is implementation-phase work.
- **Frontend / UI / API consumer concerns.** Calendar view, plan presentation, reasoning-text rendering — Figma phase, then frontend LLD.
- **Multi-week / monthly horizon plans.** v1 is week-scoped per HLD.
- **Shopping list calculation.** Owned by the grocery module per HLD; the planner emits the plan.
- **Decision-log table DDL.** Lives in [lld/core.md](core.md) (concurrent agent). Planner depends on `core.DecisionLogService` at the interface level.
- **Per-household weight tuning.** v1 uses global weights; per-household would require per-row weights and a calibration UI.
- **Cost projection beyond confidence-weighted aggregates.** Live supplier price lookup at compose time is the grocery module's concern; the planner reads cached confidence-weighted history only.
- **Notification copy.** Exact text for re-opt suggestions, completion alerts — UX writing phase.
- **Authorisation rules** for who in a household can `acceptPlan` / `revertToPlan` / `abandonPlan`. Pre-supposes the household role model is finalised; for v1 any household member can accept/reject. Owned by the auth + household modules.
