# Nutrition Module — LLD

*Implementation specification for the Nutrition Model: macro/micro targets, intake tracking (planned vs actual), USDA / Open Food Facts ingredient mapping, food/mood journal, and the propose/accept queue for health-platform dietary directives. Translates [nutrition-model.md](../design/nutrition-model.md) into a buildable Spring Boot module.*

## Scope

This document specifies the `nutrition` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers, REST controllers, validation, events, business-logic flows, transaction boundaries, and test plan. Conventions defer to [lld/style-guide.md](style-guide.md).

The HLD's four concerns map to four persistence shapes plus a deterministic safety surface:

| Concern | Storage | Mutability | Update path |
|---|---|---|---|
| Nutritional targets | `nutrition_targets` aggregate (relational + child tables) | User-only; directives propose via queue | `updateTargets`, `acceptHealthDirective` |
| Intake tracking | `nutrition_intake_*` per-day/per-slot/per-snack rows | User + system (plan pre-fill, `MealCookedEvent` auto-confirm) | `confirmFromPlan` / `overrideIntake` / `editIntake` / `logSnack` / `skipMeal` |
| Ingredient mapping cache | `nutrition_ingredient_mapping` (JSONB nutrition payload) | Pipeline writes; user corrections | `correctIngredientMapping`, internal `IngredientMappingPipeline` |
| Food/mood journal | `nutrition_food_mood_journal` | User-only | `upsertJournalEntry` |
| Health directives queue | `nutrition_health_directives` | Inbound from platform; user accept/reject | `acceptHealthDirective` / `rejectHealthDirective` |

The deterministic floor enforcement consumed by the planner's scoring multiplicative gate (per [meal-planner.md §scoring](../design/meal-planner.md#scoring-function)) is exposed as `NutritionFloorGateService` — narrow so the planner injects only the gate. Recipe nutrition calculation (per [recipe-system.md](../design/recipe-system.md)) is `NutritionCalculationService`, called by the recipe module on save and on `RecipeEvolvedEvent`.

---

## Package Layout

```
com.example.mealprep.nutrition/
├── NutritionModule.java                       facade re-exporting the four public service interfaces
├── api/
│   ├── controller/                            TargetsController, IntakeController, JournalController,
│   │                                          IngredientLookupController, HealthDirectivesController
│   ├── dto/                                   records (see DTOs section)
│   └── mapper/                                TargetsMapper, IntakeMapper, JournalMapper,
│                                              IngredientMappingMapper, HealthDirectiveMapper
├── domain/
│   ├── entity/                                JPA entities (see Entities section)
│   ├── repository/                            Spring Data interfaces — package-private
│   └── service/
│       ├── NutritionQueryService.java         public
│       ├── NutritionUpdateService.java        public
│       ├── NutritionCalculationService.java   public (re-exported)
│       ├── NutritionFloorGateService.java     public (re-exported)
│       ├── NutritionServiceImpl.java          single impl of all four
│       └── internal/
│           ├── IngredientMappingPipeline       cache → AI parse → USDA/OFF → AI match → store
│           ├── UsdaApiClient                   FoodData Central client (Resilience4j @Retry + @RateLimiter)
│           ├── OpenFoodFactsClient             secondary, branded fallback
│           ├── IntakeAggregator                daily/weekly rollups
│           ├── DivergenceDetector              threshold logic + event emission
│           ├── DirectiveSafetyGate             deterministic checks before applying a directive
│           ├── DirectiveApplier                accepted-directive write fan-out (targets / preference)
│           └── IntakeKeyNormaliser             wrapper over Preference's IngredientKey normaliser
├── event/                                     NutritionTargetsChangedEvent, IntakeLoggedEvent,
│                                               NutritionIntakeDivergedEvent,
│                                               HealthDirectiveReceivedEvent, HealthDirectiveAcceptedEvent
├── exception/                                 module-root + per-failure subclasses (see Errors)
├── validation/                                @ValidEatingWindow, @ValidPerMealDistribution,
│                                              @ValidActivityProfile, @ValidDirectiveInstruction
└── config/                                    NutritionJsonConfig, UsdaApiConfig, OpenFoodFactsConfig
```

The four public service interfaces are intentionally narrow: `NutritionQueryService` for read fan-out (planner, optimiser, notification); `NutritionUpdateService` for the write surface (logger UI, feedback module, health platform inbound); `NutritionCalculationService` called only by the recipe module (no user-state dependency); `NutritionFloorGateService` called only by the planner's deterministic scoring gate.

---

## Database

Migrations under `src/main/resources/db/migration/` with the project-wide timestamp scheme. Tables prefixed `nutrition_`.

```
V20260502120000__nutrition_create_targets.sql
V20260502120100__nutrition_create_per_meal_distribution.sql
V20260502120200__nutrition_create_micro_targets.sql
V20260502120300__nutrition_create_eating_window_and_activity.sql
V20260502120400__nutrition_create_intake_log.sql
V20260502120500__nutrition_create_food_mood_journal.sql
V20260502120600__nutrition_create_ingredient_mapping.sql
V20260502120700__nutrition_create_health_directives.sql
V20260502120800__nutrition_create_targets_audit.sql
R__nutrition_seed_dri_defaults.sql                       (DRI defaults by age/sex)
R__nutrition_seed_quantity_conversions.sql               ("1 chicken breast = 170g" etc.)
```

### V20260502120000 — Targets aggregate root

```sql
CREATE TABLE nutrition_targets (
    id                              uuid PRIMARY KEY,
    user_id                         uuid NOT NULL UNIQUE,
    -- High-level goal sets sensible defaults for enforcement_direction per macro
    -- (locked 2026-05-07). User may override individual macros below.
    goal                            varchar(16) NOT NULL DEFAULT 'MAINTAIN',  -- LOSE_WEIGHT | MAINTAIN | GAIN_WEIGHT
    daily_calorie_target            integer NOT NULL,
    calorie_tolerance_under         integer NOT NULL DEFAULT 100,
    calorie_tolerance_over          integer NOT NULL DEFAULT 150,
    calorie_enforcement             varchar(24) NOT NULL DEFAULT 'weekly_average',
    calorie_direction               varchar(16) NOT NULL DEFAULT 'BOTH_BOUNDED',   -- UPPER_LIMIT | LOWER_FLOOR | BOTH_BOUNDED
    protein_target_g                numeric(6,1) NOT NULL,
    protein_floor_g                 numeric(6,1),                            -- nullable; only when enforcement = daily_floor
    protein_enforcement             varchar(24) NOT NULL DEFAULT 'daily_floor',
    protein_direction               varchar(16) NOT NULL DEFAULT 'LOWER_FLOOR',
    carbs_target_g                  numeric(6,1) NOT NULL,
    carbs_floor_g                   numeric(6,1),
    carbs_enforcement               varchar(24) NOT NULL DEFAULT 'weekly_average',
    carbs_direction                 varchar(16) NOT NULL DEFAULT 'BOTH_BOUNDED',
    fat_target_g                    numeric(6,1) NOT NULL,
    fat_floor_g                     numeric(6,1),
    fat_enforcement                 varchar(24) NOT NULL DEFAULT 'weekly_average',
    fat_direction                   varchar(16) NOT NULL DEFAULT 'BOTH_BOUNDED',
    fibre_target_g                  numeric(6,1) NOT NULL DEFAULT 30,
    fibre_enforcement               varchar(24) NOT NULL DEFAULT 'weekly_average',
    fibre_direction                 varchar(16) NOT NULL DEFAULT 'LOWER_FLOOR',
    sat_fat_target_g                numeric(6,1),                            -- nullable; opt-in target
    sat_fat_direction               varchar(16) NOT NULL DEFAULT 'UPPER_LIMIT',  -- health-driven; goal-independent
    notes                           varchar(512),
    optimistic_version              bigint NOT NULL DEFAULT 0,
    created_at                      timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    -- Tracks which macros the user has manually overridden vs received from goal defaults.
    -- A bitset would be cheaper but a small text array is more debuggable. ~7 entries max.
    user_overridden_directions      text[] NOT NULL DEFAULT '{}'
);
-- Hot read: getTargets (one row) and the planner's per-run aggregate fetch.
CREATE UNIQUE INDEX idx_nutr_targets_user ON nutrition_targets (user_id);
```

### V20260502120100 / V20260502120200 — Per-meal distribution and micro targets

```sql
CREATE TABLE nutrition_per_meal_distribution (
    id              uuid PRIMARY KEY,
    targets_id      uuid NOT NULL REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    meal_slot       varchar(24) NOT NULL,                    -- breakfast | lunch | dinner | snacks
    calorie_target  integer NOT NULL,
    protein_target_g numeric(6,1) NOT NULL,
    UNIQUE (targets_id, meal_slot)
);
CREATE INDEX idx_nutr_per_meal_targets ON nutrition_per_meal_distribution (targets_id);

CREATE TABLE nutrition_micro_targets (
    id              uuid PRIMARY KEY,
    targets_id      uuid NOT NULL REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    nutrient_key    varchar(48) NOT NULL,                    -- iron_mg | zinc_mg | vitamin_b12_mcg | sodium_mg | ...
    target_value    numeric(10,3),
    upper_limit     numeric(10,3),                           -- nullable; only ceilings (sodium etc.)
    source_preference varchar(24), notes varchar(255),
    UNIQUE (targets_id, nutrient_key)
);
-- Both bulk-loaded with parent via @EntityGraph in getTargets.
CREATE INDEX idx_nutr_micro_targets_parent ON nutrition_micro_targets (targets_id);
```

### V20260502120300 — Eating window and activity profile

```sql
CREATE TABLE nutrition_eating_window (
    id                              uuid PRIMARY KEY,
    targets_id                      uuid NOT NULL UNIQUE REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    enabled                         boolean NOT NULL DEFAULT false,
    window_start                    time, window_end         time,
    notes                           varchar(255)
);

CREATE TABLE nutrition_activity_adjustment (
    id                              uuid PRIMARY KEY,
    targets_id                      uuid NOT NULL REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    activity_level                  varchar(24) NOT NULL,    -- rest_day | light_activity | training_day | heavy_training
    calorie_modifier                integer NOT NULL DEFAULT 0,
    carb_modifier_g                 integer NOT NULL DEFAULT 0,
    UNIQUE (targets_id, activity_level)
);
CREATE INDEX idx_nutr_activity_adjustment_parent ON nutrition_activity_adjustment (targets_id);

CREATE TABLE nutrition_daily_activity_log (
    id                              uuid PRIMARY KEY,
    user_id                         uuid NOT NULL, on_date  date NOT NULL,
    activity_level                  varchar(24) NOT NULL, notes varchar(255),
    created_at                      timestamptz NOT NULL,
    UNIQUE (user_id, on_date)
);
-- Read by the planner per-day and by the daily aggregator.
CREATE INDEX idx_nutr_daily_activity_user_date ON nutrition_daily_activity_log (user_id, on_date DESC);
```

### V20260502120400 — Intake log

The HLD's intake structure is a per-day, per-slot mapping with `planned`, `actual`, and a snack list. Modelled as one row per slot (planned + actual collapsed) plus a separate child table for snacks. The HLD does not prescribe row-shape or event-sourcing. **Choosing same-row** for slot planned + actual: the day's UI is "list of slots with planned-vs-actual side-by-side"; same-row reduces the per-day query to one join. The audit table preserves the history that event-sourcing would have given for free.

```sql
CREATE TABLE nutrition_intake_day (
    id                  uuid PRIMARY KEY,
    user_id             uuid NOT NULL, on_date date NOT NULL,
    plan_id             uuid,                                 -- snapshot of plan when first pre-filled
    optimistic_version  bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE (user_id, on_date)
);
-- Hot read: getIntakeForDay; getRecentIntakeTotals (range scan).
CREATE INDEX idx_nutr_intake_day_user_date ON nutrition_intake_day (user_id, on_date DESC);

CREATE TABLE nutrition_intake_slot (
    id                  uuid PRIMARY KEY,
    intake_day_id       uuid NOT NULL REFERENCES nutrition_intake_day(id) ON DELETE CASCADE,
    meal_slot           varchar(24) NOT NULL,                 -- breakfast | lunch | dinner
    planned_recipe_id   uuid,                                 -- cross-module ref, not FK
    planned_calories    integer,
    planned_protein_g   numeric(6,1), planned_carbs_g numeric(6,1),
    planned_fat_g       numeric(6,1), planned_fibre_g numeric(6,1),
    planned_micros      jsonb,
    actual_status       varchar(24) NOT NULL DEFAULT 'pending',
                                                              -- pending | confirmed | overridden | edited | skipped
    actual_calories     integer,
    actual_protein_g    numeric(6,1), actual_carbs_g numeric(6,1),
    actual_fat_g        numeric(6,1), actual_fibre_g numeric(6,1),
    actual_micros       jsonb,
    override_free_text  varchar(512), overridden_at timestamptz,
    UNIQUE (intake_day_id, meal_slot)
);
CREATE INDEX idx_nutr_intake_slot_day ON nutrition_intake_slot (intake_day_id);

CREATE TABLE nutrition_intake_snack (
    id                      uuid PRIMARY KEY,
    intake_day_id           uuid NOT NULL REFERENCES nutrition_intake_day(id) ON DELETE CASCADE,
    ingredient_mapping_key  varchar(255),
    free_text               varchar(255) NOT NULL,
    quantity_g              numeric(8,1) NOT NULL,
    calories                integer NOT NULL,
    protein_g               numeric(6,1) NOT NULL, carbs_g numeric(6,1) NOT NULL,
    fat_g                   numeric(6,1) NOT NULL, fibre_g numeric(6,1),
    micros                  jsonb,
    source                  varchar(24) NOT NULL,             -- usda | open_food_facts | manual | preference_accompaniment
    logged_at               timestamptz NOT NULL
);
CREATE INDEX idx_nutr_intake_snack_day ON nutrition_intake_snack (intake_day_id);

CREATE TABLE nutrition_intake_audit (
    id                  uuid PRIMARY KEY,
    intake_day_id       uuid NOT NULL REFERENCES nutrition_intake_day(id) ON DELETE CASCADE,
    actor_user_id       uuid NOT NULL,
    action              varchar(32) NOT NULL,                 -- prefill | confirm | override | edit | skip | snack_add | snack_remove
    meal_slot           varchar(24), snack_id uuid,
    previous_value_json jsonb, new_value_json jsonb,
    occurred_at         timestamptz NOT NULL
);
CREATE INDEX idx_nutr_intake_audit_day_time ON nutrition_intake_audit (intake_day_id, occurred_at DESC);
```

### V20260502120500 — Food/mood journal

```sql
CREATE TABLE nutrition_food_mood_journal (
    id                  uuid PRIMARY KEY,
    user_id             uuid NOT NULL, on_date date NOT NULL,
    meal_slot           varchar(24),                          -- nullable: mid-afternoon entry not slot-tied
    journal_entry       text NOT NULL,
    logged_at           timestamptz NOT NULL,
    optimistic_version  bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE (user_id, on_date, meal_slot)
);
-- Per-day fetch + recent-entries listing.
CREATE INDEX idx_nutr_food_mood_user_date ON nutrition_food_mood_journal (user_id, on_date DESC);
-- Feedback System loads recent entries to enrich classifier context.
CREATE INDEX idx_nutr_food_mood_user_logged_at ON nutrition_food_mood_journal (user_id, logged_at DESC);
```

The unique constraint with nullable `meal_slot` follows Postgres semantics: nulls aren't considered equal, so multiple null-slot entries per day are permitted (matches the HLD's "free-text journal tied to meal slots" plus the implied untied entries).

### V20260502120600 — Ingredient mapping cache

```sql
CREATE TABLE nutrition_ingredient_mapping (
    id                  uuid PRIMARY KEY,
    search_term         varchar(255) NOT NULL UNIQUE,        -- always lowercase + trimmed via IntakeKeyNormaliser
    source              varchar(24) NOT NULL,                -- usda | open_food_facts | manual
    external_id         varchar(64),                         -- USDA FDC id (text for future sources)
    nutrition_per_100g  jsonb NOT NULL,                      -- mirrored by IngredientNutritionDocument
    default_piece_grams integer,                             -- for "1 chicken breast" style
    confidence          numeric(4,3) NOT NULL,
    needs_review        boolean NOT NULL DEFAULT false,
    last_verified_at    timestamptz,
    created_at          timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
-- Hot path: every recipe save and every snack log.
CREATE UNIQUE INDEX idx_nutr_ingredient_mapping_search_term ON nutrition_ingredient_mapping (search_term);
-- Backs the needs-review admin/feedback view (partial index keeps it tiny).
CREATE INDEX idx_nutr_ingredient_mapping_needs_review ON nutrition_ingredient_mapping (needs_review) WHERE needs_review = true;
```

`nutrition_per_100g` is JSONB: the micronutrient set is wide (~25 fields), per-source sparse, and accessed as a whole document by the calculation pipeline — matches the style guide's JSONB criterion.

### V20260502120700 — Health directives queue

`(source_platform, external_directive_id)` is the natural key for idempotent inbound delivery. Status machine: `pending_review` → `accepted` | `rejected` | `superseded` | `expired`. `directive_type` ∈ {`ingredient_restriction`, `target_adjustment`, `macro_rebalance`, `elimination_trial`, `reintroduction_protocol`, `sensitivity_downgrade`}. `instruction_payload` is JSONB to preserve the source platform's full message verbatim for audit.

```sql
CREATE TABLE nutrition_health_directives (
    id                              uuid PRIMARY KEY,
    user_id                         uuid NOT NULL,
    external_directive_id           varchar(128) NOT NULL,
    source_platform                 varchar(64) NOT NULL,
    received_at                     timestamptz NOT NULL,
    status                          varchar(24) NOT NULL DEFAULT 'pending_review',
    directive_type                  varchar(48) NOT NULL,
    evidence_summary                text,
    evidence_confidence             varchar(16),
    instruction_payload             jsonb NOT NULL,
    maps_to_model                   varchar(24) NOT NULL,    -- preference_model | nutrition_model
    maps_to_tier                    varchar(48),
    temporary                       boolean NOT NULL DEFAULT true,
    auto_expires_at                 timestamptz,
    decided_at                      timestamptz,
    decided_by_user_id              uuid,
    user_modification_json          jsonb,
    rejection_reason                varchar(255),
    safety_gate_verdict             varchar(16),             -- passed | blocked | passed_with_warnings
    safety_gate_findings            jsonb,
    optimistic_version              bigint NOT NULL DEFAULT 0,
    created_at                      timestamptz NOT NULL,
    updated_at                      timestamptz NOT NULL,
    UNIQUE (source_platform, external_directive_id)
);
-- Review queue listing.
CREATE INDEX idx_nutr_directives_user_status ON nutrition_health_directives (user_id, status, received_at DESC);
-- Auto-expiry sweep job.
CREATE INDEX idx_nutr_directives_auto_expires ON nutrition_health_directives (auto_expires_at) WHERE auto_expires_at IS NOT NULL;
```

### V20260502120800 — Targets audit

```sql
CREATE TABLE nutrition_targets_audit (
    id                  uuid PRIMARY KEY,
    targets_id          uuid NOT NULL REFERENCES nutrition_targets(id) ON DELETE CASCADE,
    actor_user_id       uuid NOT NULL,
    actor_kind          varchar(24) NOT NULL,                -- user | health_directive | feedback
    source_directive_id uuid,                                -- non-null when actor_kind = health_directive
    field_path          varchar(96) NOT NULL,                -- "calorie_target" | "micro.iron_mg.target" | "per_meal.lunch.protein_g"
    previous_value_json jsonb NOT NULL, new_value_json jsonb NOT NULL,
    occurred_at         timestamptz NOT NULL
);
CREATE INDEX idx_nutr_targets_audit_targets_time ON nutrition_targets_audit (targets_id, occurred_at DESC);
```

### Repeatable migrations

- `R__nutrition_seed_dri_defaults.sql` — DRI micro defaults keyed by `(age_group, sex)`. Loaded by `initialiseTargets` to seed `nutrition_micro_targets`.
- `R__nutrition_seed_quantity_conversions.sql` — common ingredient → grams conversions ("1 chicken breast = 170g", "1 tin chickpeas drained = 240g", "1 tbsp oil = 14g") used by the AI parsing step's grounding context.

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@Version` on every mutable aggregate root, `@CreatedDate`/`@LastModifiedDate` audit columns, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. JSONB columns mapped via `@Type(JsonType.class)` from `hypersistence-utils`.

| Entity | Notes |
|---|---|
| `NutritionTargets` | Aggregate root. `userId` unique. `@OneToMany` (cascade ALL, orphanRemoval) for `PerMealDistributionEntry`, `MicroTarget`, `ActivityAdjustment`. `@OneToOne` to `EatingWindow`. `@Version`. |
| `PerMealDistributionEntry`, `MicroTarget`, `ActivityAdjustment`, `EatingWindow` | Children. `@ManyToOne(fetch = LAZY)` to parent via `@JoinColumn(name = "targets_id")`. No `@Version` — parent's covers the aggregate. |
| `DailyActivityLog` | Last write per `(user_id, on_date)` wins; uniqueness enforced by DB. No `@Version`. |
| `NutritionTargetsAuditLog` | Append-only. No `@Version`, no `@LastModifiedDate`. JSON values via JSONB. |
| `IntakeDay` | Aggregate root. `(userId, onDate)` unique. Owns `IntakeSlot`, `IntakeSnack`, `IntakeAuditLog` (`@OneToMany`, cascade ALL, orphanRemoval). `@Version`. |
| `IntakeSlot`, `IntakeSnack`, `IntakeAuditLog` | Children. `IntakeAuditLog` append-only. |
| `FoodMoodJournalEntry` | One row per `(user_id, on_date, meal_slot)`. `@Version` — same entry can be edited. |
| `IngredientMapping` | Reference data with light updates. `@Version` because the user-correction flow mutates and concurrent corrections must collide cleanly. `nutritionPer100g` mapped to `IngredientNutritionDocument` record via JSONB. |
| `HealthDirective` | Aggregate root. `(sourcePlatform, externalDirectiveId)` unique for idempotent inbound. `@Version`. JSONB for instruction payload, modifications, findings. |

Enums local to the module: `MealSlot` (`BREAKFAST`, `LUNCH`, `DINNER`, `SNACKS`); `Enforcement` (`DAILY_FLOOR`, `WEEKLY_AVERAGE`, `DAILY_TARGET`); `ActivityLevel` (`REST_DAY`, `LIGHT_ACTIVITY`, `TRAINING_DAY`, `HEAVY_TRAINING`); `IntakeSlotStatus` (`PENDING`, `CONFIRMED`, `OVERRIDDEN`, `EDITED`, `SKIPPED`); `IntakeSource` (`USDA`, `OPEN_FOOD_FACTS`, `MANUAL`, `PREFERENCE_ACCOMPANIMENT`); `IngredientMappingSource` (`USDA`, `OPEN_FOOD_FACTS`, `MANUAL`); `DirectiveType` (six values per the HLD: `INGREDIENT_RESTRICTION`, `TARGET_ADJUSTMENT`, `MACRO_REBALANCE`, `ELIMINATION_TRIAL`, `REINTRODUCTION_PROTOCOL`, `SENSITIVITY_DOWNGRADE`); `DirectiveStatus` (`PENDING_REVIEW`, `ACCEPTED`, `REJECTED`, `SUPERSEDED`, `EXPIRED`); `DirectiveConfidence` (`LOW`, `MODERATE`, `HIGH`); `SafetyGateVerdict` (`PASSED`, `BLOCKED`, `PASSED_WITH_WARNINGS`); `IntakeAuditAction` (`PREFILL`, `CONFIRM`, `OVERRIDE`, `EDIT`, `SKIP`, `SNACK_ADD`, `SNACK_REMOVE`).

---

## DTOs

All DTOs are Java records per the style guide. Request shapes carry `expectedVersion` for optimistic-locked updates.

### Targets

```java
public record TargetsDto(
    UUID id, UUID userId,
    Goal goal,                                                  // LOSE_WEIGHT | MAINTAIN | GAIN_WEIGHT
    CalorieTargetDto calories,
    MacroTargetDto protein, MacroTargetDto carbs, MacroTargetDto fat, MacroTargetDto fibre,
    MacroTargetDto saturatedFat,                                // optional — null until user opts in
    List<PerMealDistributionDto> perMealDistribution,
    List<MicroTargetDto> microTargets,
    EatingWindowDto eatingWindow,
    List<ActivityAdjustmentDto> activityAdjustments,
    Set<String> userOverriddenDirections,                       // names of macros where user set direction manually
    String notes, long optimisticVersion
) {}

public enum Goal { LOSE_WEIGHT, MAINTAIN, GAIN_WEIGHT }
public enum EnforcementDirection { UPPER_LIMIT, LOWER_FLOOR, BOTH_BOUNDED }

public record CalorieTargetDto(int dailyTarget, int toleranceUnder, int toleranceOver,
                               Enforcement enforcement, EnforcementDirection direction) {}
public record MacroTargetDto(BigDecimal targetG, BigDecimal floorG, Enforcement enforcement,
                             EnforcementDirection direction, boolean isHardFloor, String notes) {}
public record PerMealDistributionDto(MealSlot mealSlot, int calorieTarget, BigDecimal proteinTargetG) {}
public record MicroTargetDto(String nutrientKey, BigDecimal targetValue, BigDecimal upperLimit,
                             String sourcePreference, String notes) {}
public record EatingWindowDto(boolean enabled, LocalTime windowStart, LocalTime windowEnd, String notes) {}
public record ActivityAdjustmentDto(ActivityLevel activityLevel, int calorieModifier, int carbModifierG) {}

public record UpdateTargetsRequest(
    @NotNull @Valid CalorieTargetDto calories,
    @NotNull @Valid MacroTargetDto protein, @NotNull @Valid MacroTargetDto carbs,
    @NotNull @Valid MacroTargetDto fat, @NotNull @Valid MacroTargetDto fibre,
    @NotNull @Size(max = 4) @Valid List<PerMealDistributionDto> perMealDistribution,
    @NotNull @Size(max = 30) @Valid List<MicroTargetDto> microTargets,
    @Valid @ValidEatingWindow EatingWindowDto eatingWindow,
    @NotNull @Valid List<ActivityAdjustmentDto> activityAdjustments,
    @Size(max = 512) String notes, long expectedVersion
) {}
```

`MacroTargetDto.floorG` is nullable; non-null only when `enforcement = DAILY_FLOOR`. `MacroTargetDto.direction` governs the **soft sub-score's** asymmetric penalty in the planner; `isHardFloor` separately controls the **multiplicative gate** (binary kill if not met). They are complementary — protein is typically `LOWER_FLOOR` direction *and* `isHardFloor = true`; calories on a cut is `UPPER_LIMIT` direction with `isHardFloor = false`.

### Goal-driven defaults

`PUT /api/v1/nutrition/targets/goal` changes `goal` and re-applies direction defaults to any macro NOT in `user_overridden_directions`. The default table (locked 2026-05-07):

| Macro | LOSE_WEIGHT | MAINTAIN | GAIN_WEIGHT |
|---|---|---|---|
| Calories | UPPER_LIMIT | BOTH_BOUNDED | LOWER_FLOOR |
| Protein | LOWER_FLOOR | LOWER_FLOOR | LOWER_FLOOR |
| Fat | BOTH_BOUNDED | BOTH_BOUNDED | BOTH_BOUNDED |
| Carbs | BOTH_BOUNDED | BOTH_BOUNDED | BOTH_BOUNDED |
| Fibre | LOWER_FLOOR | LOWER_FLOOR | LOWER_FLOOR |
| Saturated fat | UPPER_LIMIT | UPPER_LIMIT | UPPER_LIMIT |

Sat fat is goal-independent — it's a health-driven cap. The `GoalDefaultsResolver` (in `domain/service/internal/`) holds this table; goal change calls `resolveDefaults(goal, userOverridden)` to reset only the non-overridden directions.

### Intake

```java
public record IntakeDayDto(UUID id, UUID userId, LocalDate onDate, UUID planId,
                           List<IntakeSlotDto> slots, List<IntakeSnackDto> snacks,
                           DailyAggregateDto totals, long optimisticVersion) {}

public record IntakeSlotDto(UUID id, MealSlot mealSlot, PlannedIntakeDto planned, ActualIntakeDto actual) {}
public record PlannedIntakeDto(UUID recipeId, Integer calories, BigDecimal proteinG, BigDecimal carbsG,
                               BigDecimal fatG, BigDecimal fibreG, Map<String, BigDecimal> micros) {}
public record ActualIntakeDto(IntakeSlotStatus status, Integer calories, BigDecimal proteinG, BigDecimal carbsG,
                              BigDecimal fatG, BigDecimal fibreG, Map<String, BigDecimal> micros,
                              String overrideFreeText, Instant overriddenAt) {}
public record IntakeSnackDto(UUID id, String ingredientMappingKey, String freeText, BigDecimal quantityG,
                             int calories, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG,
                             BigDecimal fibreG, Map<String, BigDecimal> micros,
                             IntakeSource source, Instant loggedAt) {}

// Per macro: planned, actualSoFar, remaining triple. Plus micros actual-so-far map.
public record DailyAggregateDto(
    int caloriesPlanned, int caloriesActualSoFar, int caloriesRemaining,
    MacroAggregateDto protein, MacroAggregateDto carbs, MacroAggregateDto fat, MacroAggregateDto fibre,
    Map<String, BigDecimal> microsActualSoFar
) {}
public record MacroAggregateDto(BigDecimal plannedG, BigDecimal actualSoFarG, BigDecimal remainingG) {}

public record WeeklyAggregateDto(LocalDate weekStart, LocalDate weekEnd, DailyAggregateDto[] perDay,
                                 DailyAggregateDto weeklyTotal, List<String> floorViolations) {}

public record IntakeEntryDto(MealSlot mealSlot, IntakeSlotStatus targetStatus,
                             String overrideFreeText,
                             Integer calories, BigDecimal proteinG, BigDecimal carbsG,
                             BigDecimal fatG, BigDecimal fibreG, Map<String, BigDecimal> micros) {}

public record LogSnackRequest(
    @NotBlank @Size(max = 255) String freeText, String ingredientMappingKey,
    @NotNull @DecimalMin("0.0") BigDecimal quantityG,
    @NotNull IntakeSource source, boolean deductFromPantry
) {}
```

`IntakeEntryDto` is a shared shape for confirm/override/edit/skip; `targetStatus` selects the action.

### Ingredient nutrition lookup and recipe calculation

```java
public record IngredientNutritionDto(String searchTerm, IngredientMappingSource source, String externalId,
                                     IngredientNutritionDocument nutritionPer100g, Integer defaultPieceGrams,
                                     BigDecimal confidence, boolean needsReview, Instant lastVerifiedAt) {}

public record IngredientNutritionDocument(
    Integer calories, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG, BigDecimal fibreG,
    BigDecimal saturatedFatG, BigDecimal sugarG,
    Map<String, BigDecimal> micros, Map<String, BigDecimal> vitamins
) {}

public record IngredientLookupRequest(@NotBlank @Size(max = 255) String query, @Size(max = 20) Integer maxResults) {}
public record IngredientLookupResultDto(List<IngredientNutritionDto> hits, boolean cacheOnly) {}

public record CalculateRecipeNutritionRequest(
    @NotNull UUID recipeId,
    @NotNull @Size(min = 1) @Valid List<RecipeIngredientLineDto> ingredients,
    @NotNull @Min(1) Integer servings
) {}

public record RecipeIngredientLineDto(
    @NotBlank String name, String ingredientMappingKey,
    BigDecimal quantity, String unit, BigDecimal gramsEstimate, Boolean isCooked
) {}

public record RecipeNutritionResultDto(UUID recipeId, int caloriesPerServing,
    BigDecimal proteinPerServingG, BigDecimal carbsPerServingG, BigDecimal fatPerServingG, BigDecimal fibrePerServingG,
    Map<String, BigDecimal> microsPerServing,
    String nutritionStatus, List<UnmappedIngredientDto> unmapped) {}

public record UnmappedIngredientDto(String name, String reason, BigDecimal confidence) {}
```

`nutritionStatus` ∈ {`calculated`, `partial`, `pending`} — matches the [recipe-system.md](../design/recipe-system.md) vocabulary so the recipe module can persist it verbatim on the recipe row.

### Food / mood journal

```java
public record FoodMoodEntryDto(UUID id, UUID userId, LocalDate onDate, MealSlot mealSlot,
                               String journalEntry, Instant loggedAt, long optimisticVersion) {}

public record UpsertFoodMoodEntryRequest(
    @NotNull LocalDate onDate, MealSlot mealSlot,
    @NotBlank @Size(max = 4000) String journalEntry,
    @NotNull Instant loggedAt, long expectedVersion
) {}
```

### Health directives

```java
public record HealthDirectiveDto(
    UUID id, UUID userId, String externalDirectiveId, String sourcePlatform, Instant receivedAt,
    DirectiveStatus status, DirectiveType directiveType,
    String evidenceSummary, DirectiveConfidence evidenceConfidence,
    DirectiveInstructionDocument instruction,
    String mapsToModel, String mapsToTier, boolean temporary, Instant autoExpiresAt,
    Instant decidedAt, UUID decidedByUserId,
    DirectiveInstructionDocument userModification, String rejectionReason,
    SafetyGateVerdict safetyGateVerdict, List<SafetyFindingDto> safetyGateFindings,
    long optimisticVersion
) {}

public record DirectiveInstructionDocument(String action, String target, String scope,
                                           DirectiveDurationDto duration, Map<String, JsonNode> extras) {}
public record DirectiveDurationDto(String type, List<DirectivePhaseDto> phases, Integer durationWeeks) {}
public record DirectivePhaseDto(String phase, Integer durationWeeks, String rule) {}
public record SafetyFindingDto(String code, String message, String severity) {}

public record InboundHealthDirectiveRequest(
    @NotBlank String externalDirectiveId, @NotBlank String sourcePlatform,
    @NotNull DirectiveType directiveType,
    @Size(max = 4000) String evidenceSummary, DirectiveConfidence evidenceConfidence,
    @NotNull @Valid @ValidDirectiveInstruction DirectiveInstructionDocument instruction,
    @NotBlank String mapsToModel, String mapsToTier,
    boolean temporary, Instant autoExpiresAt
) {}

public record AcceptDirectiveRequest(DirectiveInstructionDocument userModification, long expectedVersion) {}
public record RejectDirectiveRequest(@Size(max = 255) String rejectionReason, long expectedVersion) {}
```

---

## Mappers

MapStruct interfaces, `@Mapper(componentModel = "spring")`, one per entity-DTO pair. Defaults match by name; custom `@Mapping` declared only where they diverge.

```java
@Mapper(componentModel = "spring")
public interface TargetsMapper {
    TargetsDto toDto(NutritionTargets entity);
    List<TargetsDto> toDtos(List<NutritionTargets> entities);
    NutritionTargetsAuditEntryDto toAuditDto(NutritionTargetsAuditLog entity);
}

@Mapper(componentModel = "spring")
public interface IntakeMapper {
    IntakeDayDto toDto(IntakeDay entity);
    List<IntakeDayDto> toDtos(List<IntakeDay> entities);
    IntakeSlotDto toSlotDto(IntakeSlot entity);
    IntakeSnackDto toSnackDto(IntakeSnack entity);
}

@Mapper(componentModel = "spring") public interface JournalMapper {
    FoodMoodEntryDto toDto(FoodMoodJournalEntry entity);
    List<FoodMoodEntryDto> toDtos(List<FoodMoodJournalEntry> entities);
}
@Mapper(componentModel = "spring") public interface IngredientMappingMapper {
    IngredientNutritionDto toDto(IngredientMapping entity);
    List<IngredientNutritionDto> toDtos(List<IngredientMapping> entities);
}
@Mapper(componentModel = "spring") public interface HealthDirectiveMapper {
    HealthDirectiveDto toDto(HealthDirective entity);
    List<HealthDirectiveDto> toDtos(List<HealthDirective> entities);
}
```

---

## Repositories

Package-private interfaces (no `public`); cross-module access goes through service interfaces only. `@EntityGraph` on targets and intake-day hot-read queries collapses each to one join — no N+1.

```java
interface NutritionTargetsRepository extends JpaRepository<NutritionTargets, UUID> {
    @EntityGraph(attributePaths = {"perMealDistribution", "microTargets", "eatingWindow", "activityAdjustments"})
    Optional<NutritionTargets> findWithChildrenByUserId(UUID userId);
    @EntityGraph(attributePaths = {"perMealDistribution", "microTargets", "eatingWindow", "activityAdjustments"})
    List<NutritionTargets> findWithChildrenByUserIdIn(List<UUID> userIds);
    Optional<NutritionTargets> findByUserId(UUID userId);
}

interface NutritionTargetsAuditRepository extends JpaRepository<NutritionTargetsAuditLog, UUID> {
    Page<NutritionTargetsAuditLog> findByTargetsIdOrderByOccurredAtDesc(UUID id, Pageable p);
}

interface DailyActivityLogRepository extends JpaRepository<DailyActivityLog, UUID> {
    Optional<DailyActivityLog> findByUserIdAndOnDate(UUID userId, LocalDate onDate);
    List<DailyActivityLog> findByUserIdAndOnDateBetween(UUID userId, LocalDate from, LocalDate to);
}

interface IntakeDayRepository extends JpaRepository<IntakeDay, UUID> {
    @EntityGraph(attributePaths = {"slots", "snacks"})
    Optional<IntakeDay> findWithDetailsByUserIdAndOnDate(UUID userId, LocalDate onDate);
    @EntityGraph(attributePaths = {"slots", "snacks"})
    List<IntakeDay> findWithDetailsByUserIdAndOnDateBetween(UUID userId, LocalDate from, LocalDate to);
    Optional<IntakeDay> findByUserIdAndOnDate(UUID userId, LocalDate onDate);
}

interface IntakeAuditRepository extends JpaRepository<IntakeAuditLog, UUID> {
    Page<IntakeAuditLog> findByIntakeDayIdOrderByOccurredAtDesc(UUID id, Pageable p);
}

interface FoodMoodJournalRepository extends JpaRepository<FoodMoodJournalEntry, UUID> {
    List<FoodMoodJournalEntry> findByUserIdAndOnDateOrderByLoggedAtAsc(UUID userId, LocalDate onDate);
    Page<FoodMoodJournalEntry> findByUserIdOrderByLoggedAtDesc(UUID userId, Pageable p);
    List<FoodMoodJournalEntry> findTop20ByUserIdOrderByLoggedAtDesc(UUID userId);    // Feedback context
}

interface IngredientMappingRepository extends JpaRepository<IngredientMapping, UUID> {
    Optional<IngredientMapping> findBySearchTerm(String searchTerm);
    List<IngredientMapping> findBySearchTermIn(Collection<String> searchTerms);
    Page<IngredientMapping> findByNeedsReviewTrueOrderByUpdatedAtDesc(Pageable p);
    @Query("select im from IngredientMapping im where lower(im.searchTerm) like concat('%', lower(:q), '%')")
    Page<IngredientMapping> searchByTerm(@Param("q") String query, Pageable p);
}

interface HealthDirectiveRepository extends JpaRepository<HealthDirective, UUID> {
    Optional<HealthDirective> findBySourcePlatformAndExternalDirectiveId(String platform, String externalId);
    Page<HealthDirective> findByUserIdAndStatusOrderByReceivedAtDesc(UUID u, DirectiveStatus s, Pageable p);
    Page<HealthDirective> findByUserIdOrderByReceivedAtDesc(UUID userId, Pageable p);
    List<HealthDirective> findByStatusAndAutoExpiresAtBefore(DirectiveStatus status, Instant cutoff);
}
```

---

## Service Interfaces

Per the style guide, all four module interfaces are implemented by a single `NutritionServiceImpl`. The four interfaces exist so cross-module callers inject only what they need.

### `NutritionQueryService`

```java
public interface NutritionQueryService {
    // Targets
    Optional<TargetsDto> getTargets(UUID userId);
    List<TargetsDto> getTargetsByUserIds(List<UUID> userIds);                  // planner batch
    Page<NutritionTargetsAuditEntryDto> getTargetsAuditLog(UUID userId, Pageable pageable);

    // Activity
    Optional<DailyActivityDto> getDailyActivity(UUID userId, LocalDate onDate);
    List<DailyActivityDto> getDailyActivityRange(UUID userId, LocalDate from, LocalDate to);

    // Intake
    Optional<IntakeDayDto> getIntakeForDay(UUID userId, LocalDate onDate);
    List<IntakeDayDto> getIntakeRange(UUID userId, LocalDate from, LocalDate to);
    DailyAggregateDto getDailyAggregate(UUID userId, LocalDate onDate);
    WeeklyAggregateDto getWeeklyAggregate(UUID userId, LocalDate weekStart);
    Page<IntakeAuditEntryDto> getIntakeAuditLog(UUID userId, LocalDate onDate, Pageable p);
    List<DailyAggregateDto> getRecentIntakeTotals(UUID userId, LocalDate from, LocalDate to);

    // Journal
    List<FoodMoodEntryDto> getJournalEntriesForDay(UUID userId, LocalDate onDate);
    Page<FoodMoodEntryDto> getRecentJournalEntries(UUID userId, Pageable pageable);
    List<FoodMoodEntryDto> getJournalEntriesForFeedbackContext(UUID userId);    // top-20

    // Ingredient lookup
    Optional<IngredientNutritionDto> lookupIngredient(String searchTerm);
    List<IngredientNutritionDto> lookupIngredients(Collection<String> searchTerms);
    IngredientLookupResultDto searchIngredientsForUi(IngredientLookupRequest request);
    Page<IngredientNutritionDto> getMappingsNeedingReview(Pageable pageable);

    // Health directives
    Page<HealthDirectiveDto> getDirectives(UUID userId, DirectiveStatus filter, Pageable p);
    Optional<HealthDirectiveDto> getDirective(UUID directiveId);
}
```

`getRecentIntakeTotals` is exposed for the planner's mid-week re-opt path; returns actual-so-far and remaining figures keyed by date for the requested window. `getJournalEntriesForFeedbackContext` is invoked by the Feedback System's classifier-context assembly.

### `NutritionUpdateService`

```java
public interface NutritionUpdateService {
    // Targets — user-only; audited.
    TargetsDto initialiseTargets(UUID userId, UpdateTargetsRequest request);
    TargetsDto updateTargets(UUID userId, UpdateTargetsRequest request, UUID actorUserId);

    // Daily activity
    DailyActivityDto upsertDailyActivity(UUID userId, LocalDate onDate, ActivityLevel level, String notes);

    // Intake — pre-fill is system-driven; the rest are user-driven.
    IntakeDayDto prefillFromPlan(UUID userId, LocalDate onDate, UUID planId, List<PlannedSlotInputDto> slots);
    IntakeDayDto confirmFromPlan(UUID userId, LocalDate onDate, MealSlot mealSlot);
    IntakeDayDto overrideIntakeFromFreeText(UUID userId, LocalDate onDate, MealSlot mealSlot, String freeText);
    IntakeDayDto editIntakeManually(UUID userId, LocalDate onDate, IntakeEntryDto entry);
    IntakeDayDto skipMeal(UUID userId, LocalDate onDate, MealSlot mealSlot);
    IntakeDayDto logSnack(UUID userId, LocalDate onDate, LogSnackRequest request);
    IntakeDayDto removeSnack(UUID userId, LocalDate onDate, UUID snackId);

    // Feedback System entry point — interprets nutrition-classified feedback into a target proposal
    // or journal append. See Flow 10. Returns the DTO that was updated (or proposed).
    NutritionFeedbackOutcomeDto applyFeedback(UUID userId, NutritionFeedbackRequest request);

    // Food/mood journal
    FoodMoodEntryDto upsertJournalEntry(UUID userId, UpsertFoodMoodEntryRequest request);
    void deleteJournalEntry(UUID userId, UUID entryId);

    // Ingredient mapping corrections (user)
    IngredientNutritionDto correctIngredientMapping(String searchTerm, IngredientNutritionDocument override,
                                                    UUID actorUserId);

    // Health directives
    HealthDirectiveDto receiveInboundDirective(UUID userId, InboundHealthDirectiveRequest request);
    HealthDirectiveDto acceptHealthDirective(UUID userId, UUID directiveId, AcceptDirectiveRequest request);
    HealthDirectiveDto rejectHealthDirective(UUID userId, UUID directiveId, RejectDirectiveRequest request);
}
```

`actorUserId` threads who-performed-the-change for the targets audit. Directive-driven writes record the directive's id (Flow 8); self-edits record the user.

### `NutritionCalculationService`

Called only by the recipe module. No user-state dependency; takes the recipe's ingredient list verbatim.

```java
public interface NutritionCalculationService {
    RecipeNutritionResultDto calculateRecipeNutrition(CalculateRecipeNutritionRequest request);

    // Triggered by RecipeEvolvedEvent listener (in this module). Same shape as save-time calculation;
    // separate method for log/observability clarity.
    RecipeNutritionResultDto recalculateForEvolvedRecipe(CalculateRecipeNutritionRequest request);
}
```

### `NutritionFloorGateService`

The deterministic gate consumed by the planner's scoring multiplicative gate per [meal-planner.md §scoring](../design/meal-planner.md#scoring-function). Pure function over a candidate plan — no I/O beyond loading targets.

```java
public interface NutritionFloorGateService {
    FloorGateResultDto evaluate(UUID userId, CandidatePlanRollupDto rollup);
    Map<UUID, FloorGateResultDto> evaluateForHousehold(List<UUID> userIds, CandidatePlanRollupDto rollup);
}

public record CandidatePlanRollupDto(LocalDate startDate, LocalDate endDate,
                                     List<CandidateDailyRollupDto> perDay) {}
public record CandidateDailyRollupDto(LocalDate date, ActivityLevel activityLevel, int calories,
                                      BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG, BigDecimal fibreG,
                                      Map<String, BigDecimal> micros) {}
public record FloorGateResultDto(boolean passed, List<FloorViolationDto> violations, String summary) {}
public record FloorViolationDto(LocalDate date, String macroOrMicro, BigDecimal floor, BigDecimal actual) {}
```

`evaluate` returning `passed = false` collapses the candidate plan's score to zero in the planner's multiplicative scoring gate. **Each target (macro AND micro) carries an `is_hard_floor: boolean` flag.** When `true`, that target participates in the multiplicative gate; when `false`, it surfaces as a warning only. Defaults: macros default to `true` (hard floor enforcement); micros default to `false` (warning only). The user can toggle any specific micro to `true` (e.g. iron during pregnancy, B12 for vegans, vitamin D in winter) — that micro then participates in the gate.

The `nutrition_targets` schema carries an `is_hard_floor` column alongside each macro target and within the JSONB micro-targets shape. UI and API expose this per-target so users can scale the gate's strictness without it being all-or-nothing. `FloorGateResultDto.violations` lists every breached hard floor regardless of macro/micro distinction.

---

## REST Controllers

All endpoints under `/api/v1/nutrition/...`. `userId` resolved server-side from auth context per [technical-architecture.md §Frontend-Backend Contract](../design/technical-architecture.md#frontend-backend-contract). Following the pilot's precedent for multi-controller split when sub-resources are clear, the endpoints split across five controllers.

### `TargetsController` — `/api/v1/nutrition/targets`

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/` | — | `TargetsDto` | 200 / 404 |
| PUT    | `/` | `UpdateTargetsRequest` | `TargetsDto` | 200 / 400 / 404 / 409 |
| GET    | `/audit-log?page=&size=` | — | `Page<NutritionTargetsAuditEntryDto>` | 200 |
| GET    | `/activity?from=&to=` | — | `List<DailyActivityDto>` | 200 |
| PUT    | `/activity/{date}` | `{ activityLevel, notes }` | `DailyActivityDto` | 200 / 400 |

### `IntakeController` — `/api/v1/nutrition/intake`

The HLD defines four intake actions (confirm, override, edit, skip). Each gets a verb endpoint per the style guide ("verbs for non-CRUD actions"). All paths under `/api/v1/nutrition/intake`:

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/{date}` | — | `IntakeDayDto` | 200 / 404 |
| GET    | `?from=&to=` | — | `List<IntakeDayDto>` | 200 |
| GET    | `/{date}/aggregate` | — | `DailyAggregateDto` | 200 |
| GET    | `/week/{weekStart}/aggregate` | — | `WeeklyAggregateDto` | 200 |
| POST   | `/{date}/slots/{mealSlot}/confirm` | — | `IntakeDayDto` | 200 / 404 |
| POST   | `/{date}/slots/{mealSlot}/override` | `{ freeText }` | `IntakeDayDto` | 200 / 400 / 422 |
| POST   | `/{date}/slots/{mealSlot}/edit` | `IntakeEntryDto` | `IntakeDayDto` | 200 / 400 / 409 |
| POST   | `/{date}/slots/{mealSlot}/skip` | — | `IntakeDayDto` | 200 |
| POST   | `/{date}/snacks` | `LogSnackRequest` | `IntakeDayDto` | 201 / 400 / 422 |
| DELETE | `/{date}/snacks/{snackId}` | — | — | 204 / 404 |
| GET    | `/{date}/audit-log?page=&size=` | — | `Page<IntakeAuditEntryDto>` | 200 |

### `JournalController` — `/api/v1/nutrition/journal`

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/{date}` | — | `List<FoodMoodEntryDto>` | 200 |
| POST   | `/{date}` | `UpsertFoodMoodEntryRequest` | `FoodMoodEntryDto` | 201 / 400 |
| PUT    | `/{date}/entries/{entryId}` | `UpsertFoodMoodEntryRequest` | `FoodMoodEntryDto` | 200 / 400 / 404 / 409 |
| DELETE | `/{date}/entries/{entryId}` | — | — | 204 / 404 |
| GET    | `?page=&size=` | — | `Page<FoodMoodEntryDto>` | 200 |

### `IngredientLookupController` — `/api/v1/nutrition/ingredients`

For the standalone food-search UX. `NutritionCalculationService` is in-process only — no REST — because the recipe module injects it directly per [technical-architecture.md §Module Communication](../design/technical-architecture.md#module-communication).

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/lookup?term=` | — | `IngredientNutritionDto` | 200 / 404 |
| POST   | `/search` | `IngredientLookupRequest` | `IngredientLookupResultDto` | 200 |
| PUT    | `/{searchTerm}/correction` | `IngredientNutritionDocument` | `IngredientNutritionDto` | 200 / 400 / 404 / 409 |
| GET    | `/needs-review?page=&size=` | — | `Page<IngredientNutritionDto>` | 200 |

### `HealthDirectivesController` — `/api/v1/nutrition/health-directives`

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `?status=&page=&size=` | — | `Page<HealthDirectiveDto>` | 200 |
| GET    | `/{directiveId}` | — | `HealthDirectiveDto` | 200 / 404 |
| POST   | `/inbound` | `InboundHealthDirectiveRequest` | `HealthDirectiveDto` | 201 / 400 / 409 |
| POST   | `/{directiveId}/accept` | `AcceptDirectiveRequest` | `HealthDirectiveDto` | 200 / 400 / 409 / 422 |
| POST   | `/{directiveId}/reject` | `RejectDirectiveRequest` | `HealthDirectiveDto` | 200 / 400 / 409 |

The HLD does not specify how directives arrive (push from health platform vs polled). **Choosing push.** The `inbound` POST is the entry point; the health platform calls it (auth out of scope for this LLD). Polling could be added later without changing the queue model. **Worth user review.**

`applyFeedback` (called by the Feedback System) is **not** exposed via REST — it is invoked in-process by the feedback module via direct service injection. There is no client of this method outside the JVM.

### Error responses

All error responses use RFC 9457 `ProblemDetail`. Module-specific exceptions, mapped in the project-wide `GlobalExceptionHandler`. Module root: `NutritionException extends MealPrepException`. Type URIs follow the pattern `https://mealprep.example.com/problems/<slug>`.

| Exception | Status |
|---|---|
| `NutritionTargetsNotFoundException`, `IntakeDayNotFoundException`, `IntakeSlotNotFoundException`, `JournalEntryNotFoundException`, `IngredientMappingNotFoundException`, `HealthDirectiveNotFoundException` | 404 |
| `OptimisticLockException` (JPA), `HealthDirectiveAlreadyDecidedException` | 409 |
| `HealthDirectiveSafetyGateBlockedException`, `IntakeOverrideParseException`, `IngredientMappingPipelineException` | 422 |
| `MethodArgumentNotValidException` | 400 (`errors[]` extension) |

---

## Validation

Jakarta annotations at request-record level: `@NotNull`, `@NotBlank`, `@Size`, `@Min`/`@Max`, `@DecimalMin`/`@DecimalMax`, `@Valid` for nested records.

Custom validators in `validation/`:

- **`@ValidEatingWindow`** (class-level on `EatingWindowDto`): `enabled = false` ⇒ start/end may be null; `enabled = true` ⇒ both non-null and `windowStart < windowEnd`. Same-day windows only in v1.
- **`@ValidPerMealDistribution`**: no duplicate meal slots; per-meal calorie sum within ±100 of daily target (planner can redistribute; 50%+ mismatch is almost certainly a UI bug — warn-log; reject only when sum exceeds 2× the daily target).
- **`@ValidActivityProfile`**: no duplicate activity levels.
- **`@ValidDirectiveInstruction`**: `action` in known set; `target` non-blank for `restrict_ingredient` / `adjust_target`; `staged_protocol` phases are ordered, non-overlapping, weeks sum > 0.

Validation failures bubble up as `MethodArgumentNotValidException` → 400 ProblemDetail.

**Service-layer checks (not Jakarta):**
- Free-text override parsing → `IntakeOverrideParseException` (422). The AI parses and returns structured intake; semantic failures don't fit annotation-style validation.
- `DirectiveSafetyGate` runs before any directive apply → `HealthDirectiveSafetyGateBlockedException` (422) with `SafetyFindingDto[]`. The gate is the immutable safety surface — it runs even on user-modified retries.

---

## Events

### Published

```java
public record NutritionTargetsChangedEvent(UUID userId, UUID targetsId,
    Set<String> changedFields,                       // "calorie_target", "protein_floor_g", "micro.iron_mg.target", ...
    UUID actorReferenceId, NutritionActorKind actorKind,
    UUID traceId, Instant occurredAt) {}

public record IntakeLoggedEvent(UUID userId, UUID intakeDayId, LocalDate onDate,
    IntakeAuditAction action, MealSlot mealSlot, UUID snackId,
    UUID traceId, Instant occurredAt) {}

public record NutritionIntakeDivergedEvent(UUID userId, LocalDate onDate,
    Set<String> divergedMacros, DivergenceSummaryDto summary,
    UUID traceId, Instant occurredAt) {}

public record DivergenceSummaryDto(Map<String, BigDecimal> plannedSoFar,
                                    Map<String, BigDecimal> actualSoFar,
                                    Map<String, BigDecimal> percentVariance) {}

public record HealthDirectiveReceivedEvent(UUID userId, UUID directiveId, DirectiveType directiveType,
    String sourcePlatform, Instant receivedAt, UUID traceId, Instant occurredAt) {}

public record HealthDirectiveAcceptedEvent(UUID userId, UUID directiveId, DirectiveType directiveType,
    String mapsToModel, String mapsToTier, boolean userModified,
    UUID traceId, Instant occurredAt) {}

public enum NutritionActorKind { USER, HEALTH_DIRECTIVE, FEEDBACK }
```

Published via `ApplicationEventPublisher` after the write transaction. Listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)` per the style guide.

### Divergence threshold and detection

The HLD says "divergence beyond a configurable threshold (default: ≥15% variance on any macro for the day)" — see [meal-planner.md §triggers](../design/meal-planner.md#triggers). Encoded as:

After every intake-actual update (CONFIRM, OVERRIDE, EDIT, SKIP — not pure snack ops; snacks have no planned counterpart), `DivergenceDetector` loads the day and targets, computes `plannedSoFar` and `actualSoFar` per macro across all decided slots (snacks count toward actuals), and computes `variance = (actualSoFar - plannedSoFar) / plannedSoFar`. If `|variance| ≥ threshold` for any macro **and** at least one slot is still `PENDING` (i.e. there are remaining slots to re-optimise), publish `NutritionIntakeDivergedEvent` listing the diverged macros. Duplicate suppression: publish only if the set of diverged macros differs from the previous publication for the same day, or a previously-diverged macro newly resolves (the planner needs the resolution signal to drop a queued re-opt offer).

Threshold in `application.yml`:

```yaml
mealprep.nutrition.divergence.macro-variance-threshold: 0.15
mealprep.nutrition.divergence.minimum-planned-floor-kcal: 200    # avoid noise on tiny plans
```

### Consumed

Two `@TransactionalEventListener(phase = AFTER_COMMIT)` listeners:

- **`MealCookedEvent`** → auto-confirms the matching planned slot per [technical-architecture.md §Flow 4](../design/technical-architecture.md#flow-4-cook-event). The listener calls `confirmFromPlan(userId, onDate, mealSlot)` resolved from the payload. Duplicate events (same `(userId, planId, mealSlotId)`) no-op idempotently because the slot is already `CONFIRMED`.
- **`RecipeEvolvedEvent`** → triggers `NutritionCalculationService.recalculateForEvolvedRecipe`. The recipe module passes the updated ingredient list via `CalculateRecipeNutritionRequest`; the result is returned to the recipe module, which owns recipe storage and decides whether to overwrite or version the nutrition record.

The HLD does not specify whether `MealCookedEvent` listening lives in the planner or the nutrition module. Per [technical-architecture.md §Flow 4](../design/technical-architecture.md#flow-4-cook-event) ("Nutrition Logger listener auto-confirms planned nutrition for that meal slot"), it lives here. **Worth user review.**

---

## Business Logic Flows

### Flow 1: Targets update (user-driven)

`PUT /api/v1/nutrition/targets` → `updateTargets(userId, request, actorUserId)`. Service impl method is `@Transactional`. Loads the existing aggregate with `findWithChildrenByUserId`. `expectedVersion` mismatch → `OptimisticLockException` → 409. Diffs each scalar field and each child collection (per-meal distribution, micro targets, eating window, activity adjustments); for collections, replaces by key (cascade orphanRemoval handles child churn). Writes one `NutritionTargetsAuditLog` row per changed field with `actorKind = USER`. Publishes `NutritionTargetsChangedEvent` after commit. Returns the updated DTO.

### Flow 2: Targets update (health-directive driven)

The propose/accept flow ensures a directive never auto-applies. Once accepted (Flow 8), the `DirectiveApplier` calls the same service-impl method that backs `updateTargets`, but with `actorKind = HEALTH_DIRECTIVE` and `sourceDirectiveId` populated on the audit row. The audit thread proves the chain user → accept → write.

### Flow 3: Planned-meal confirmation

`POST /api/v1/nutrition/intake/{date}/slots/{mealSlot}/confirm` → `confirmFromPlan(userId, onDate, mealSlot)`. `@Transactional`. Idempotent — re-confirming an already-confirmed slot is a no-op. Loads the day with `findWithDetailsByUserIdAndOnDate`. If the slot is `PENDING`, copies the planned columns into the actual columns and sets `actualStatus = CONFIRMED`. Writes an `IntakeAuditLog(action = CONFIRM)` row. Calls `DivergenceDetector` (see threshold above). Publishes `IntakeLoggedEvent(action=CONFIRM)` after commit.

### Flow 4: Free-text intake override (AI-parsing path)

`POST /api/v1/nutrition/intake/{date}/slots/{mealSlot}/override` with `{ freeText }` → `overrideIntakeFromFreeText`. `@Transactional`.

1. Load the day's `IntakeDay` (404 if missing — pre-fill should have created it) and slot.
2. Store the verbatim `freeText` into `IntakeSlot.overrideFreeText` immediately — preserved even if AI parsing fails.
3. Call `IntakeOverrideParserTask` via `AiService`. Tool-use response is either `parsed` (`{ ingredient_lines: [...] }`, each with mapping key, quantity, unit, grams) or `unparseable` (slot marked `OVERRIDDEN` with zero-nutrition actuals; verbatim preserved; UI banner invites manual edit). Prompt text is **out of scope** (see Out of Scope).
4. For each ingredient line, call `IngredientMappingPipeline.resolve` (Flow 6) and sum into the slot's actual columns. Set `actualStatus = OVERRIDDEN`, `overriddenAt = now`.
5. Audit (`IntakeAuditLog(action = OVERRIDE)`) with the parsed structure as `new_value_json` — the AI's interpretation is auditable.
6. Run `DivergenceDetector`.
7. Publish `IntakeLoggedEvent(action=OVERRIDE)` after commit.

The override path **does not** trigger pantry deduction — the user already ate the food; pantry consumption is captured by `MealCookedEvent` separately.

### Flow 5: Manual intake edit and skip

- `POST .../edit` with `IntakeEntryDto` → `editIntakeManually`. The user has already chosen the numeric values; service writes them directly to the slot's actual columns, sets `actualStatus = EDITED`, audits, runs divergence detection, publishes `IntakeLoggedEvent(action=EDIT)`. No AI involved.
- `POST .../skip` → sets `actualStatus = SKIPPED`, zeroes the actual columns, audits, runs divergence detection (skip is a strong divergence signal — almost always trips the threshold for that macro), publishes `IntakeLoggedEvent(action=SKIP)`.

### Flow 6: USDA ingredient mapping cache hit/miss

Used by every recipe save (via `NutritionCalculationService`), every free-text override, and every snack log. `IngredientMappingPipeline.resolve(line)`:

1. **Normalise** via `IntakeKeyNormaliser` (lowercase, trim, collapse whitespace) per [technical-architecture.md §Cross-module references](../design/technical-architecture.md#cross-module-references). Same normalisation as Provisions.
2. **Cache check** — `findBySearchTerm(normalised)`. **Hit:** multiply `nutritionPer100g × (gramsEstimate / 100)` and return. **Miss:** continue.
3. **AI parse step** — if the line lacks structured form (free text, no mapping key), call `IngredientParseTask` to produce `{ingredient, quantity, unit, gramsEstimate, usdaSearchTerm, isCooked}`. Recipe-side calls already carry structured form; skip.
4. **USDA search** — `UsdaApiClient.search(usdaSearchTerm)` returns top 5-10 matches. Resilience4j `@Retry` (2 attempts on 5xx) + `@RateLimiter` (1000 req/h). Empty → fall back to `OpenFoodFactsClient.search`.
5. **AI match selection** — `IngredientMatchTask` picks the best entry, returns `{externalId, confidence}`. Both clients empty → return `UnmappedIngredientDto(name, "no source matches", 0)`; recipe `nutritionStatus` becomes `partial`.
6. **Persist** one `IngredientMapping` row with `needsReview = (confidence < 0.7)` per the recipe HLD threshold. Concurrent inserts collide on the unique constraint; the loser re-reads and uses the winning row — no retry storm.
7. **Compute and return** as in cache-hit.

The pipeline runs inside the caller's transaction by default. The HLD does not specify whether AI calls happen in-transaction or out. **Choosing in-transaction.** When `calculateRecipeNutrition` returns, the cache reflects every mapping decided during that call. The recipe LLD's `nutrition_status = pending` covers the only failure shape — both clients unreachable — without us needing a separate async state machine. **Worth user review** — long latency could degrade recipe-save UX; novel ingredients could later move to a background queue.

### Flow 7: Snack logging (cross-module write with pantry deduction)

`POST /api/v1/nutrition/intake/{date}/snacks` → `logSnack`. The canonical cross-model write path per [technical-architecture.md §Who injects what](../design/technical-architecture.md#who-injects-what). `@Transactional` (default REQUIRED).

1. Load or create the day's `IntakeDay`.
2. Resolve nutrition: if `ingredientMappingKey` is present, look it up directly; otherwise `IngredientMappingPipeline.resolve` on the free text.
3. Write a new `IntakeSnack` row.
4. Audit (`SNACK_ADD`).
5. **If `deductFromPantry == true`**: call `provisionUpdateService.deductFromInventoryByMappingKey(userId, mappingKey, quantityG)` — same-JVM DI call, joins this transaction. Optimistic-lock failure on the provisions side rolls back the whole flow (409). User retries without `deductFromPantry` if needed.
6. Run `DivergenceDetector`.
7. Publish `IntakeLoggedEvent(SNACK_ADD)` after commit. Provisions publishes its own `ProvisionChangedEvent`; the planner sees both.

The HLD does not specify the transaction shape. **Choosing transaction joining (REQUIRED)** — matches the pilot's pattern for cross-module write flows (Flow 3 of the preference LLD). REQUIRES_NEW would let a snack log succeed while pantry deduction silently fails, surfacing only at the next plan run; joining is correct for this safety-shaped invariant. The `deductFromPantry` flag is request-time: snacks eaten "from the wild" (friend's banana, coffee-shop pastry) should not deduct. UI defaults to `true` when the item matches a pantry inventory key.

### Flow 8: Health-directive propose / accept

The second safety surface. **Directives are NEVER auto-applied** per [nutrition-model.md §Propose, not apply](../design/nutrition-model.md#propose-not-apply).

**Inbound (propose).** `POST .../inbound` → `receiveInboundDirective`. `@Transactional`. Idempotent on `(sourcePlatform, externalDirectiveId)` — re-delivery returns 409 with existing row's status. Validate against `@ValidDirectiveInstruction`. Persist `status = PENDING_REVIEW`. Publish `HealthDirectiveReceivedEvent` after commit; notification module surfaces the in-app review prompt.

**Accept.** `POST .../{directiveId}/accept` with optional `userModification` → `acceptHealthDirective`. `@Transactional`.

1. Status must be `PENDING_REVIEW` else `HealthDirectiveAlreadyDecidedException` (409).
2. If `userModification` non-null, it overrides the original. Re-validate the effective instruction — modification cannot bypass the schema gate.
3. **Run `DirectiveSafetyGate.evaluate`** — deterministic checks; pure code; no AI. The v1 ruleset:
   - `INGREDIENT_RESTRICTION` / `ELIMINATION_TRIAL` colliding with a favourite in the preference taste profile → warn, don't block.
   - `TARGET_ADJUSTMENT` raising a daily floor > 20% above the current daily target, or lowering it below 50% of the current floor → **block**.
   - `MACRO_REBALANCE` whose post-apply per-meal sums diverge from the daily target by > 100 kcal → **block**.
   - `INGREDIENT_RESTRICTION` of staples (water, salt, "all protein") without alternative → **block**.
4. Persist verdict and findings (`safety_gate_verdict`, `safety_gate_findings`). `BLOCKED` → throw `HealthDirectiveSafetyGateBlockedException` (422); status stays `PENDING_REVIEW` so the user can modify and retry. `PASSED` / `PASSED_WITH_WARNINGS` → continue.
5. **Apply via `DirectiveApplier`** — routes by `mapsToModel`:
   - `nutrition_model` → internal target-update path (same as Flow 1) with `actorKind = HEALTH_DIRECTIVE` and `sourceDirectiveId` populated; publishes `NutritionTargetsChangedEvent`.
   - `preference_model` → `PreferenceUpdateService.updateHardConstraints` joining this transaction. `temporary` and `autoExpiresAt` persisted with the constraint so preference can expire it.
6. Mark `status = ACCEPTED`, set `decidedAt`, `decidedByUserId`.
7. Publish `HealthDirectiveAcceptedEvent` after commit.

**Reject.** `POST .../{directiveId}/reject` → `REJECTED`, `decidedAt`, `rejectionReason`. No downstream writes.

**Auto-expiry.** `@Scheduled(cron = "0 0 4 * * *")` sweeps `findByStatusAndAutoExpiresAtBefore(ACCEPTED, now)`. For time-bounded effects (e.g. 6-week egg elimination), instructs the source module to revert (`PreferenceUpdateService.removeTemporaryConstraint(...)`). Marks directive `EXPIRED`.

### Flow 9: Daily and weekly aggregation

`getDailyAggregate(userId, onDate)` is a pure-read flow. `@Transactional(readOnly = true)`. Loads the day with details and the user's targets. Sums actuals across all `IntakeSlot`s and `IntakeSnack`s, ignoring `PENDING` slots (their actual columns are null). Computes `remaining = target - actualSoFar` per macro, floored at zero. Micros aggregated similarly from the JSONB columns; `actualMicros` is a `Map<String, BigDecimal>` keyed by `nutrient_key`.

`getWeeklyAggregate(userId, weekStart)` calls `getDailyAggregate` for each of the seven days, sums weekly totals, and walks each day's macros against `MacroTargetDto.floorG` for the floors-list. Because each daily fetch is one query (`@EntityGraph`), the weekly aggregate is exactly seven queries plus one for targets — fine for the dashboard endpoint, no caching needed in v1.

### Flow 10: Feedback System inbound

`applyFeedback(userId, NutritionFeedbackRequest)` is the entry point invoked by the Feedback System per [feedback-system.md](../design/feedback-system.md). `@Transactional`. The Feedback System has already classified the feedback as nutrition-targeted; this method routes by feedback sub-type:

- **Portion complaint** ("too small") → no automatic write. Returns a `proposalId` for a target adjustment that the user can accept (mirrors the directive flow but human-driven). The HLD says "Action: portion complaints may adjust per-meal calorie distribution" — this is a proposal, not an automatic application, on the same propose/accept principle as directives.
- **Energy/mood observation** ("crashed at 3pm") → writes a `FoodMoodJournalEntry` for the appropriate slot with actor metadata showing the feedback origin. Returns the journal entry DTO.
- **Explicit target change** ("I want more protein") → returns a proposal DTO; the user must confirm via the targets PUT. No silent target writes ever.

`NutritionFeedbackOutcomeDto` carries the route taken so the Feedback System can confirm to the user which sub-flow ran.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | Service-impl methods. Repositories never. |
| Read methods | `@Transactional(readOnly = true)`. |
| Write methods | Default REQUIRED. |
| Optimistic locking | `@Version` on `NutritionTargets`, `IntakeDay`, `FoodMoodJournalEntry`, `IngredientMapping`, `HealthDirective`. Children inherit via parent. Append-only logs and reference-seed data carry no `@Version`. |
| Pessimistic locking | None. |
| Cross-module write (snack-deduct) | `logSnack` joins the caller's transaction; the inner `provisionUpdateService.deductFromInventoryByMappingKey` call also REQUIRED — both writes commit or roll back together. Matches the pilot's `applyTasteProfileDeltas` pattern. |
| Concurrent ingredient-mapping inserts | Unique constraint on `search_term` resolves the race: loser catches `DataIntegrityViolationException`, re-reads the cached row, proceeds. No app-level locking. |
| Idempotent directive inbound | `(sourcePlatform, externalDirectiveId)` unique. Re-delivery returns 409 with existing row id. |
| AI calls outside transactions | `IngredientMappingPipeline` does **not** hold a DB transaction across the AI call. On cache miss: persist a stub `IngredientMapping` row with `mapping_status = 'pending'` and commit, then dispatch the AI work via `@Async`. When the async resolution returns, a new transaction updates the row to `mapping_status = 'mapped'` (or `failed` after retries) and writes the nutrition payload. Recipe save returns immediately with `nutrition_status = pending` for any unmapped ingredients; the planner and dashboard tolerate `pending` status by excluding those rows from aggregates with a flag in the response. The same pattern is used for free-text intake parsing. Matches the project-wide AI graceful-degrade model — AI unavailability never blocks DB writes. |
| Async resolution retries | Failed AI mapping retries via Resilience4j with exponential backoff. After 5 terminal failures the row is marked `mapping_status = 'failed'` and surfaced to the user as "needs manual mapping" — they can correct it via `correctIngredientMapping`. |

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres + WireMock for USDA / OFF. Naming `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `NutritionServiceImplTest` | Query and update happy paths and error mappings with mocked repositories, `IngredientMappingPipeline`, `DivergenceDetector`, `DirectiveSafetyGate`, `ProvisionUpdateService`. |
| `NutritionFloorGateServiceTest` | Macro daily floors trigger `passed = false`. Floors not configured → no violation (HLD: floors are optional). Household-batch path returns one result per user. |
| `NutritionCalculationServiceTest` | Per-recipe arithmetic over a fixture. Cache-hit, cache-miss, and partial-mapping paths produce the expected `nutritionStatus`. |
| `IngredientMappingPipelineTest` | Cache hit short-circuits. Cache miss with USDA hit caches the result. USDA empty + OFF hit falls through. Both empty → `UnmappedIngredientDto`. Concurrent insert race resolves via re-read. Confidence < 0.7 sets `needsReview`. |
| `DivergenceDetectorTest` | Threshold 15% triggers per macro independently. Skip-meal triggers (caloric divergence). Pure snack ops do not trigger. Resolution emits the resolution event. |
| `DirectiveSafetyGateTest` | Each rule in the v1 ruleset rejects matching shapes, accepts safe shapes. User-modification path gated identically. |
| `IntakeAggregatorTest` | Daily and weekly aggregation arithmetic. PENDING slots ignored, skipped contribute zero, snacks add cumulatively, micros aggregate across JSONB keys. |
| `IngredientLookupNormalisationTest` | Lookup normalised before query; round-trip with the Provisions normaliser is stable. |
| Validator tests | `EatingWindow`, `PerMealDistribution`, `ActivityProfile`, `DirectiveInstruction` validators accept canonical valid shapes; reject documented illegal shapes. |
| Mapper tests | MapStruct round-trips for all five mappers preserve every field including child collections and JSONB documents. |

### Integration

| Class | Verifies |
|---|---|
| `TargetsControllerIT` | Full HTTP cycle: GET (200/404), PUT (200/400/409), audit pagination, ProblemDetail shape, `NutritionTargetsChangedEvent` published once after commit with `actorKind = USER`. |
| `IntakeControllerIT` | Confirm/override/edit/skip happy paths and per-flow errors. Free-text override with mocked `AiService` returns expected state. Snack add/delete updates aggregate. Audit captures every action. |
| `JournalControllerIT` | CRUD round-trip. 409 on stale version. Multiple entries per day with different meal_slots and null-slot entries. |
| `IngredientLookupControllerIT` | Cache hit returns cached; miss triggers WireMock-backed USDA flow. User correction bumps `optimistic_version`. Needs-review listing returns `confidence < 0.7`. |
| `HealthDirectivesControllerIT` | Inbound 201; idempotent re-delivery returns 409. Accept happy path applies to targets and publishes the accepted event. Safety-gate block returns 422 leaving status `PENDING_REVIEW`. Reject and auto-expiry sweep transitions. |
| `NutritionServiceIT` | Service-level end-to-end against real DB. Confirm increments aggregates. Override persists verbatim free text plus structured actuals. `@EntityGraph` keeps hot reads to a single statement (Hibernate stats). |
| `IngredientMappingPipelineIT` | Real DB + WireMock USDA/OFF. Cache miss creates row. Concurrent inserts of same `search_term` collide and resolve to one row. Resilience4j retry on simulated 5xx; rate limiter caps. |
| `MealCookedEventListenerIT` | Publishing `MealCookedEvent` auto-confirms the matching slot. Idempotent on duplicates. Confirm runs after publisher's commit. |
| `SnackLogCrossModuleIT` | `logSnack` with `deductFromPantry = true` commits both writes. Provisions failure (fault injection) rolls back the whole flow. |
| `DivergenceDetectorIT` | Exactly one `NutritionIntakeDivergedEvent` per crossed-threshold change; resolution emits an updated event; below-threshold deltas emit nothing. |
| `FlywayMigrationIT` | All nutrition migrations validate against JPA mapping (`ddl-auto=validate`). DRI and quantity-conversion seeds idempotent across reruns. |
| `EventPublicationIT` | All four published events fire only after commit. Failing test-scoped listener does not roll back underlying state. |

---

## Out of Scope

Deferred deliberately — these belong elsewhere or to a later phase:

- **AI prompt for free-text intake parsing** ("actually I had X instead"). The prompt text and tool-use schema for `IntakeOverrideParserTask` belong to the prompt-engineering work. This LLD specifies how the parser is called, what its result shape is, and how the slot is updated — not the prompt itself.
- **AI prompt for ingredient → USDA entry mapping.** Same — `IngredientParseTask` / `IngredientMatchTask` are invoked by the pipeline; prompt text is owned by the AI Service LLD.
- **Food/mood journal NLP analysis.** Correlating mood with food, weekly review summaries, AI-driven insights — owned by the Feedback System and the separate health platform per the HLD's [What this is NOT](../design/nutrition-model.md#what-this-is-not). The Nutrition module exposes the journal as data; analysis is separate.
- **Wearable / fitness tracker integrations** beyond manual activity input. Per the HLD: manual is v1, wearable later. The wearable path will route through the health-platform directive queue (`MACRO_REBALANCE`).
- **Specific dietary identity validation.** Owned by the Preference Model's hard constraints tier. The Nutrition Model's eating window is the only Nutrition-side dietary pattern.
- **Frontend / UI / API consumer concerns.** Figma phase, then frontend LLD.
- **Full scoring formulas** for how the planner uses targets. Owned by the planner LLD per [meal-planner.md §scoring](../design/meal-planner.md#scoring-function). This LLD specifies the inputs and the floor gate API.
- **Cross-module orchestration of re-optimisation.** Reaction to `NutritionIntakeDivergedEvent` is the planner's concern; reaction to target changes is the optimiser's. This LLD specifies what we publish.
- **Authentication.** Owned by the auth module.
- **Health-platform inbound authentication and rate limiting.** The `inbound` POST endpoint specifies the contract; auth (mutual TLS, signed payloads, replay protection) is auth/infra and deferred.
- **Sensible defaults for unconfigured target fields.** DRI seeds give micro defaults; macro defaults from Mifflin-St Jeor and 30/40/30 split are computed by the onboarding wizard. If the user skips nutrition setup, the module returns `Optional.empty` and the planner adapts per the HLD.
- **Bioavailability modelling, alcohol tracking, supplement integration, plausibility checking.** Open questions in the HLD; revisit when relevant adjacent designs land.
