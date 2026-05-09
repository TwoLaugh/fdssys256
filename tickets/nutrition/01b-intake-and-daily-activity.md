# Ticket: nutrition — 01b Intake Tracking + Daily Activity

## Summary

Layer the **intake tracking** concern on the 01a targets aggregate: `IntakeDay` (root, one per `(userId, onDate)`) + child collections `IntakeSlot`, `IntakeSnack`, `IntakeAuditLog`; the **`DailyActivityLog`** standalone aggregate; the eight intake-write endpoints + two activity endpoints + the day/range/audit reads — all under `/api/v1/nutrition/intake/...` and `/api/v1/nutrition/targets/activity/...`. Per [`lld/nutrition.md`](../../lld/nutrition.md) §V20260502120300 (`nutrition_daily_activity_log`), §V20260502120400 (intake tables), §`IntakeController`, §`TargetsController.activity`, §`NutritionUpdateService` (lines 712-718), §Flows 3, 4, 5, 9.

**Defers** (still out of scope after 01b):
- Food/mood journal (entry CRUD, `getJournalEntriesForFeedbackContext`) → **nutrition-01c**
- Ingredient mapping cache + USDA/OFF clients + `IngredientMappingPipeline` lookup endpoints → **nutrition-01d**
- Health directives queue → **nutrition-01e**
- `NutritionCalculationService` (recipe save-time + `RecipeEvolvedEvent` listener) → **nutrition-01f** (depends on 01d)
- `NutritionFloorGateService` for the planner → **nutrition-01g**
- `IntakeAggregator`, `WeeklyAggregateDto`, weekly-rollup endpoints, `DivergenceDetector` + `NutritionIntakeDivergedEvent` → **nutrition-01h**
- DRI seed (`R__nutrition_seed_dri_defaults.sql`) → still **nutrition-01c**
- `MealCookedEvent` auto-confirm listener → **nutrition-01i** (depends on planner module)
- `applyFeedback` from feedback module → **nutrition-01j**
- AI-driven free-text override parsing (`IntakeOverrideParserTask`) → **nutrition-01k** (depends on AI module)
- Cross-module pantry-deduct on snack log (`provisionUpdateService.deductFromInventoryByMappingKey`) → **nutrition-01l** (depends on provisions-01b's mark-exhausted + a follow-up that adds `deductFromInventoryByMappingKey`)
- The four custom validators (`@ValidEatingWindow` lands in 01a; `@ValidPerMealDistribution`, `@ValidActivityProfile`, `@ValidDirectiveInstruction` are deferred to their respective concerns)

01b unblocks the dashboard's "today's intake so far" read + the manual-edit/skip/snack write paths. Without 01b the 01a targets aggregate is read-only — there's nothing for users to log against it.

**LLD divergence notes**:
1. **Free-text override (Flow 4)** — `overrideIntakeFromFreeText` requires the `AiService` (not yet implemented) and `IngredientMappingPipeline` (deferred to 01d). 01b ships the endpoint as an **AI-stub**: it stores the verbatim free text + sets `actualStatus = OVERRIDDEN` + writes zero-nutrition actuals + adds a `needs_ai_parse` flag on the row. A follow-up listener (01k) picks up `IntakeLoggedEvent(action=OVERRIDE)` rows where `needsAiParse = true`, runs the parse, and updates the row's `actual_*` columns. **Justification**: the LLD says "preserved even if AI parsing fails" — 01b's stub IS the "AI parsing failed" path. Endpoint contract unchanged when 01k lands.
2. **Snack pantry-deduct** — the LLD specifies `deductFromPantry: true` flag on `LogSnackRequest` triggers a cross-module `provisionUpdateService.deductFromInventoryByMappingKey`. **01b ships the flag in the request DTO but TREATS IT AS A NO-OP** (logs INFO if true; persists the snack without touching pantry). The deduct call is added in **nutrition-01l** when the provisions write-side method is available. Flag stays in the DTO so the contract doesn't change.
3. **Divergence detection (Flow 3 last paragraph + Flow 4 step 6 + Flow 5)** — `DivergenceDetector` + `NutritionIntakeDivergedEvent` are deferred to **nutrition-01h** alongside aggregation. 01b skips the DivergenceDetector call from the confirm/override/edit/skip paths. Flag for 01h: re-add the call in those flows.
4. **`MealCookedEvent` listener (Flow 3 description, Flow 4 of tech-arch)** — deferred to 01i. The `confirmFromPlan` endpoint exists in 01b for explicit user confirms; auto-confirm-on-cook lands later.
5. **`IngredientMappingPipeline.resolve` for snacks (Flow 7 step 2)** — pipeline doesn't exist yet (01d). 01b's `logSnack` accepts pre-resolved nutrition fields directly in the request DTO (`LogSnackRequest` carries `calories, proteinG, carbsG, fatG, fibreG, micros`) — frontend computes them from a separate ingredient lookup OR the user fills them in manually. Once 01d lands, `LogSnackRequest` gains a `resolveFromKey` flag.
6. **Aggregation (Flow 9)** — `getDailyAggregate` and `getWeeklyAggregate` are part of the aggregator-deferral. 01b ships `getIntakeForDay` (raw read of the day's slots + snacks) but not the aggregated/macro-summed view. **Defer to nutrition-01h**.

**Scope-flag for the parent**: this ticket is at the upper end of the 30-45 min target. By scope: ~22 new files, ~13 invariants, 8 endpoints, 1 stub-flow. **At the threshold but does NOT need pre-splitting** because all the heavy lift (intake aggregation, AI parsing, divergence detection, pantry deduct, cook-event listener, journal, ingredient pipeline, directives) was already deferred to 01c through 01l. What's left in 01b is the "store + read raw intake" backbone — straightforward CRUD with one stub flow. If the implementation agent reports >45 min, the natural pre-split is: (a) `nutrition-01b-day-and-slots` = `IntakeDay`, `IntakeSlot`, `IntakeAuditLog` + GET day + confirm/edit/skip endpoints; (b) `nutrition-01b-snacks-and-activity` = `IntakeSnack` + log/remove + `DailyActivityLog` + activity endpoints + free-text override stub. Both halves are independent.

## Behavioural spec

### Aggregate shape — `IntakeDay`

1. `IntakeDay` is the aggregate root, one row per `(userId, onDate)`. Fields per [LLD V20260502120400 lines 188-195](../../lld/nutrition.md): `id (UUID, application-set), userId (UUID NOT NULL), onDate (LocalDate NOT NULL), planId (UUID nullable — snapshot of plan when first pre-filled), version (@Version Long), createdAt (@CreatedDate), updatedAt (@LastModifiedDate)`. UNIQUE `(user_id, on_date)`. Owns `@OneToMany(cascade=ALL, orphanRemoval=true, fetch=LAZY)` to `IntakeSlot`, `IntakeSnack`, `IntakeAuditLog`.
2. `IntakeSlot` child per [LLD lines 199-216](../../lld/nutrition.md). Fields: `id, intakeDayId (via @ManyToOne(fetch=LAZY) IntakeDay + @JoinColumn(name="intake_day_id")), mealSlot (MealSlot enum: BREAKFAST|LUNCH|DINNER|SNACKS — note SNACKS is here for parity with 01a, though canonically the slot is BREAKFAST/LUNCH/DINNER and snacks live in IntakeSnack), plannedRecipeId (UUID nullable — soft FK), plannedCalories (Integer nullable), plannedProteinG (BigDecimal 6,1 nullable), plannedCarbsG, plannedFatG, plannedFibreG (all BigDecimal 6,1 nullable), plannedMicros (JsonNode JSONB nullable), actualStatus (IntakeSlotStatus enum: PENDING|CONFIRMED|OVERRIDDEN|EDITED|SKIPPED — default PENDING), actualCalories (Integer nullable), actualProteinG, actualCarbsG, actualFatG, actualFibreG (BigDecimal 6,1 nullable), actualMicros (JsonNode JSONB nullable), overrideFreeText (varchar 512 nullable), overriddenAt (Instant nullable), needsAiParse (boolean default false — 01b extension; flips true when overrideFreeText set, flips false when 01k parses)`. UNIQUE `(intake_day_id, meal_slot)`. No `@Version` — parent's covers.
3. `IntakeSnack` child per [LLD lines 219-232](../../lld/nutrition.md). Fields: `id, intakeDayId, ingredientMappingKey (varchar 255 nullable), freeText (varchar 255 NOT NULL), quantityG (BigDecimal 8,1 NOT NULL), calories (int NOT NULL), proteinG (BigDecimal 6,1 NOT NULL), carbsG (BigDecimal 6,1 NOT NULL), fatG (BigDecimal 6,1 NOT NULL), fibreG (BigDecimal 6,1 nullable), micros (JsonNode JSONB nullable), source (IntakeSource enum: USDA|OPEN_FOOD_FACTS|MANUAL|PREFERENCE_ACCOMPANIMENT), loggedAt (Instant NOT NULL)`. No `@Version`.
4. `IntakeAuditLog` child per [LLD lines 234-243](../../lld/nutrition.md). Append-only. Fields: `id, intakeDayId, actorUserId (UUID NOT NULL), action (IntakeAuditAction enum: PREFILL|CONFIRM|OVERRIDE|EDIT|SKIP|SNACK_ADD|SNACK_REMOVE), mealSlot (MealSlot nullable — null for SNACK_*), snackId (UUID nullable — populated for SNACK_*), previousValueJson (JsonNode JSONB), newValueJson (JsonNode JSONB), occurredAt (Instant NOT NULL)`. No `@Version`, no `@LastModifiedDate`.

### `DailyActivityLog`

5. Standalone aggregate root per [LLD V20260502120300 lines 172-180](../../lld/nutrition.md). NOT a child of `IntakeDay` — semantically separate (activity is a per-day fact, intake is a per-day collection). Fields: `id, userId (UUID NOT NULL), onDate (LocalDate NOT NULL), activityLevel (ActivityLevel enum from 01a — REST_DAY|LIGHT_ACTIVITY|TRAINING_DAY|HEAVY_TRAINING), notes (varchar 255 nullable), createdAt (@CreatedDate)`. UNIQUE `(user_id, on_date)`. **No `@Version`** per LLD ("Last write per `(user_id, on_date)` wins"). Spec: upserts use `INSERT ... ON CONFLICT (user_id, on_date) DO UPDATE` semantics via JPA `find + save`.

### Endpoints — `IntakeController` at `/api/v1/nutrition/intake`

Cookie-auth required on all. Server resolves `userId` via `CurrentUserResolver`. `userId` never accepted from body / query.

6. `GET /{date}` — `IntakeDayDto` (200) or 404 `IntakeDayNotFoundException` if no day row (i.e. user hasn't had a plan pre-filled or done any logging for that date). The day must auto-create on the first write but DOES NOT auto-create on read.
7. `GET ?from=&to=` — `List<IntakeDayDto>` for `[from, to]` inclusive (max 35 days; reject 400 if longer). Returns days that exist; missing dates not represented in the result.
8. `POST /{date}/slots/{mealSlot}/confirm` — sets `actualStatus = CONFIRMED`, copies `planned_*` columns into `actual_*` columns. **Idempotent**: re-confirming an already-confirmed slot → 200, no audit row, no event. If the day row doesn't exist → 404 `IntakeDayNotFoundException` ("Pre-fill the day first" — but pre-fill in 01b is a stub; see below). If the slot row doesn't exist within the day → 404 `IntakeSlotNotFoundException`. Returns the updated `IntakeDayDto`. Audit `(action=CONFIRM)`.
9. `POST /{date}/slots/{mealSlot}/override` — body `{ freeText: String }`. Stores verbatim `freeText` to `overrideFreeText`, sets `overriddenAt = now`, `actualStatus = OVERRIDDEN`, `needsAiParse = true`, **zeroes** `actual_*` (with `0` not null — the slot has been "logged" semantically; zero values keep aggregations sane). 200. Audit `(action=OVERRIDE)`. **Stub**: AI parse deferred to 01k.
10. `POST /{date}/slots/{mealSlot}/edit` — body `IntakeEntryDto { mealSlot, targetStatus = EDITED, calories, proteinG, carbsG, fatG, fibreG, micros }`. Writes the values to `actual_*`, sets `actualStatus = EDITED`. 200. Audit `(action=EDIT)`. Validation: numeric values `>= 0`.
11. `POST /{date}/slots/{mealSlot}/skip` — sets `actualStatus = SKIPPED`, zeroes `actual_*`. 200. Audit `(action=SKIP)`.
12. `POST /{date}/snacks` — body `LogSnackRequest`. Creates `IntakeSnack` row. **Snack pantry-deduct flag accepted but no-op'd** (LLD divergence #2). **Auto-creates the day row** if missing (snacks pre-empt slot-pre-fill — user might not have a plan for today). 201 with the updated `IntakeDayDto`. Audit `(action=SNACK_ADD)`.
13. `DELETE /{date}/snacks/{snackId}` — removes the snack. 204. 404 if not owned by caller / wrong date / non-existent. Audit `(action=SNACK_REMOVE)`.
14. `GET /{date}/audit-log?page=&size=` — paginated `Page<IntakeAuditEntryDto>` newest-first, default size 20, max 100.

### Endpoints — `TargetsController.activity` at `/api/v1/nutrition/targets/activity`

15. `GET /api/v1/nutrition/targets/activity?from=&to=` — `List<DailyActivityDto>` for `[from, to]` (max 35 days).
16. `PUT /api/v1/nutrition/targets/activity/{date}` — body `{ activityLevel, notes }`. Upserts the row. 200. Validation: `notes <= 255`. **No audit log** for activity (the LLD doesn't define one).

### Pre-fill — STUB

17. The LLD defines `prefillFromPlan(UUID userId, LocalDate, UUID planId, List<PlannedSlotInputDto>)` as a system-driven write triggered by plan creation. The planner module doesn't exist yet. **01b ships the service method as in-process-only** — exposed on `NutritionUpdateService` for future planner injection. **No HTTP endpoint** for prefill in 01b. Tested via service-impl unit tests + an IT that calls it directly.

### Service interfaces

18. Append to existing `NutritionQueryService`:
    ```java
    Optional<IntakeDayDto> getIntakeForDay(UUID userId, LocalDate onDate);
    List<IntakeDayDto> getIntakeRange(UUID userId, LocalDate from, LocalDate to);
    Page<IntakeAuditEntryDto> getIntakeAuditLog(UUID userId, LocalDate onDate, Pageable p);
    Optional<DailyActivityDto> getDailyActivity(UUID userId, LocalDate onDate);
    List<DailyActivityDto> getDailyActivityRange(UUID userId, LocalDate from, LocalDate to);
    ```
19. Append to existing `NutritionUpdateService`:
    ```java
    IntakeDayDto prefillFromPlan(UUID userId, LocalDate onDate, UUID planId, List<PlannedSlotInputDto> slots);  // in-process only
    IntakeDayDto confirmFromPlan(UUID userId, LocalDate onDate, MealSlot mealSlot);
    IntakeDayDto overrideIntakeFromFreeText(UUID userId, LocalDate onDate, MealSlot mealSlot, String freeText);
    IntakeDayDto editIntakeManually(UUID userId, LocalDate onDate, IntakeEntryDto entry);
    IntakeDayDto skipMeal(UUID userId, LocalDate onDate, MealSlot mealSlot);
    IntakeDayDto logSnack(UUID userId, LocalDate onDate, LogSnackRequest request);
    IntakeDayDto removeSnack(UUID userId, LocalDate onDate, UUID snackId);
    DailyActivityDto upsertDailyActivity(UUID userId, LocalDate onDate, ActivityLevel level, String notes);
    ```

### Repositories — package-private

20. `IntakeDayRepository`:
    ```java
    interface IntakeDayRepository extends JpaRepository<IntakeDay, UUID> {
      // Multi-bag fetch trap: slots, snacks, audits are all @OneToMany List<>.
      // ONE @EntityGraph for slots+snacks (the hot read); audits lazy-load on the audit endpoint.
      // Wait — that's still TWO bags. Per the agent-prompt-template gotcha:
      // @EntityGraph over multiple @OneToMany List<> triggers MultipleBagFetchException.
      // Use NO @EntityGraph; service touches getters inside @Transactional to lazy-load.
      Optional<IntakeDay> findByUserIdAndOnDate(UUID userId, LocalDate onDate);
      List<IntakeDay> findByUserIdAndOnDateBetween(UUID userId, LocalDate from, LocalDate to);
    }
    ```
21. `IntakeAuditRepository`:
    ```java
    interface IntakeAuditRepository extends JpaRepository<IntakeAuditLog, UUID> {
      Page<IntakeAuditLog> findByIntakeDayIdOrderByOccurredAtDesc(UUID id, Pageable p);
    }
    ```
22. `DailyActivityLogRepository`:
    ```java
    interface DailyActivityLogRepository extends JpaRepository<DailyActivityLog, UUID> {
      Optional<DailyActivityLog> findByUserIdAndOnDate(UUID userId, LocalDate onDate);
      List<DailyActivityLog> findByUserIdAndOnDateBetween(UUID userId, LocalDate from, LocalDate to);
    }
    ```
23. **Boundary**: existing `NutritionBoundaryTest` from 01a covers all new repos in `domain/repository/`. No changes.

### Events

24. `IntakeLoggedEvent(UUID userId, UUID intakeDayId, LocalDate onDate, IntakeAuditAction action, MealSlot mealSlot, UUID snackId, UUID traceId, Instant occurredAt)` published `AFTER_COMMIT` after each of confirm/override/edit/skip/log-snack/remove-snack. **Skipped** for confirm-already-confirmed (idempotent). No listeners in 01b — emitted for downstream consumers (future divergence detector + future planner mid-week re-opt offer).
25. **Not emitted in 01b**: `NutritionIntakeDivergedEvent` (defer to 01h with the divergence detector).

### Errors

26. New module exception subclasses extending `NutritionException` from 01a:
    - `IntakeDayNotFoundException` (404, `type = .../intake-day-not-found`)
    - `IntakeSlotNotFoundException` (404, `type = .../intake-slot-not-found`)
    - `IntakeSnackNotFoundException` (404, `type = .../intake-snack-not-found`)
    - `InvalidIntakeRangeException` (400, `type = .../invalid-intake-range`) — when `from > to` or range > 35 days
27. **Append** four new `@ExceptionHandler` methods to the existing `NutritionExceptionHandler` `@RestControllerAdvice` from 01a (which is already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler. Do **NOT** modify `config/GlobalExceptionHandler.java`. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler`.

## Database

```
src/main/resources/db/migration/V20260601600500__nutrition_create_daily_activity_log.sql        new
src/main/resources/db/migration/V20260601600600__nutrition_create_intake_day.sql                new
src/main/resources/db/migration/V20260601600700__nutrition_create_intake_slot.sql               new
src/main/resources/db/migration/V20260601600800__nutrition_create_intake_snack.sql              new
src/main/resources/db/migration/V20260601600900__nutrition_create_intake_audit.sql              new
```

(01a migrations occupy V…600000 through V…600400. 01b lands at V…600500+.)

```sql
-- V20260601600500
CREATE TABLE nutrition_daily_activity_log (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL,
    on_date         date NOT NULL,
    activity_level  varchar(24) NOT NULL,        -- rest_day | light_activity | training_day | heavy_training
    notes           varchar(255),
    created_at      timestamptz NOT NULL,
    UNIQUE (user_id, on_date)
);
CREATE INDEX idx_nutr_daily_activity_user_date ON nutrition_daily_activity_log (user_id, on_date DESC);

-- V20260601600600
CREATE TABLE nutrition_intake_day (
    id                  uuid PRIMARY KEY,
    user_id             uuid NOT NULL,
    on_date             date NOT NULL,
    plan_id             uuid,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    UNIQUE (user_id, on_date)
);
CREATE INDEX idx_nutr_intake_day_user_date ON nutrition_intake_day (user_id, on_date DESC);

-- V20260601600700
CREATE TABLE nutrition_intake_slot (
    id                  uuid PRIMARY KEY,
    intake_day_id       uuid NOT NULL REFERENCES nutrition_intake_day(id) ON DELETE CASCADE,
    meal_slot           varchar(24) NOT NULL,                      -- breakfast | lunch | dinner | snacks
    planned_recipe_id   uuid,
    planned_calories    integer,
    planned_protein_g   numeric(6,1), planned_carbs_g numeric(6,1),
    planned_fat_g       numeric(6,1), planned_fibre_g numeric(6,1),
    planned_micros      jsonb,
    actual_status       varchar(24) NOT NULL DEFAULT 'pending',    -- pending | confirmed | overridden | edited | skipped
    actual_calories     integer,
    actual_protein_g    numeric(6,1), actual_carbs_g numeric(6,1),
    actual_fat_g        numeric(6,1), actual_fibre_g numeric(6,1),
    actual_micros       jsonb,
    override_free_text  varchar(512),
    overridden_at       timestamptz,
    needs_ai_parse      boolean NOT NULL DEFAULT false,
    UNIQUE (intake_day_id, meal_slot)
);
CREATE INDEX idx_nutr_intake_slot_day ON nutrition_intake_slot (intake_day_id);

-- V20260601600800
CREATE TABLE nutrition_intake_snack (
    id                      uuid PRIMARY KEY,
    intake_day_id           uuid NOT NULL REFERENCES nutrition_intake_day(id) ON DELETE CASCADE,
    ingredient_mapping_key  varchar(255),
    free_text               varchar(255) NOT NULL,
    quantity_g              numeric(8,1) NOT NULL,
    calories                integer NOT NULL,
    protein_g               numeric(6,1) NOT NULL,
    carbs_g                 numeric(6,1) NOT NULL,
    fat_g                   numeric(6,1) NOT NULL,
    fibre_g                 numeric(6,1),
    micros                  jsonb,
    source                  varchar(24) NOT NULL,
    logged_at               timestamptz NOT NULL
);
CREATE INDEX idx_nutr_intake_snack_day ON nutrition_intake_snack (intake_day_id);

-- V20260601600900
CREATE TABLE nutrition_intake_audit (
    id                      uuid PRIMARY KEY,
    intake_day_id           uuid NOT NULL REFERENCES nutrition_intake_day(id) ON DELETE CASCADE,
    actor_user_id           uuid NOT NULL,
    action                  varchar(32) NOT NULL,                  -- prefill | confirm | override | edit | skip | snack_add | snack_remove
    meal_slot               varchar(24),
    snack_id                uuid,
    previous_value_json     jsonb,
    new_value_json          jsonb,
    occurred_at             timestamptz NOT NULL
);
CREATE INDEX idx_nutr_intake_audit_day_time ON nutrition_intake_audit (intake_day_id, occurred_at DESC);
```

`action` width 32: longest value `snack_remove` (12 chars) — comfortable. **Computed**.

`needs_ai_parse` is the 01b-specific extension flagging the AI parse deferral. Defaults to `false`; flipped to `true` only on `OVERRIDE` writes.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/nutrition.yaml`

(File created by 01a — append new path-items. Do NOT touch 01a's `targets`, `targetsAuditLog`.)

```yaml
intakeForDay:
  get:
    tags: [Nutrition]
    operationId: getIntakeForDay
    summary: Return the calling user's intake for one day.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: date
        required: true
        schema: { type: string, format: date }
    responses:
      '200': { description: Intake day, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/IntakeDayDto' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: No day row, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
intakeRange:
  get:
    tags: [Nutrition]
    operationId: getIntakeRange
    summary: List intake days in [from, to] (max 35 days).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: from
        required: true
        schema: { type: string, format: date }
      - in: query
        name: to
        required: true
        schema: { type: string, format: date }
    responses:
      '200': { description: Intake days, content: { application/json: { schema: { type: array, items: { $ref: '../schemas/nutrition.yaml#/IntakeDayDto' } } } } }
      '400': { description: Invalid range, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
intakeSlotConfirm:
  post:
    tags: [Nutrition]
    operationId: confirmIntakeSlot
    summary: Confirm a planned slot was eaten as planned.
    security: [{ cookieAuth: [] }]
    parameters:
      - { in: path, name: date, required: true, schema: { type: string, format: date } }
      - { in: path, name: mealSlot, required: true, schema: { $ref: '../schemas/nutrition.yaml#/MealSlot' } }
    responses:
      '200': { description: Updated, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/IntakeDayDto' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Day or slot not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
intakeSlotOverride:
  post:
    tags: [Nutrition]
    operationId: overrideIntakeSlot
    summary: Override a slot with verbatim free text (AI parse deferred).
    security: [{ cookieAuth: [] }]
    parameters:
      - { in: path, name: date, required: true, schema: { type: string, format: date } }
      - { in: path, name: mealSlot, required: true, schema: { $ref: '../schemas/nutrition.yaml#/MealSlot' } }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/OverrideIntakeRequest' }
    responses:
      '200': { description: Overridden, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/IntakeDayDto' } } } }
      '400': { description: Validation, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Day or slot not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
intakeSlotEdit:
  post:
    tags: [Nutrition]
    operationId: editIntakeSlot
    summary: Manually set a slot's actual values.
    security: [{ cookieAuth: [] }]
    parameters:
      - { in: path, name: date, required: true, schema: { type: string, format: date } }
      - { in: path, name: mealSlot, required: true, schema: { $ref: '../schemas/nutrition.yaml#/MealSlot' } }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/IntakeEntryDto' }
    responses:
      '200': { description: Edited, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/IntakeDayDto' } } } }
      '400': { description: Validation, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Day or slot not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
intakeSlotSkip:
  post:
    tags: [Nutrition]
    operationId: skipIntakeSlot
    summary: Mark a slot as skipped.
    security: [{ cookieAuth: [] }]
    parameters:
      - { in: path, name: date, required: true, schema: { type: string, format: date } }
      - { in: path, name: mealSlot, required: true, schema: { $ref: '../schemas/nutrition.yaml#/MealSlot' } }
    responses:
      '200': { description: Skipped, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/IntakeDayDto' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Day or slot not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
intakeSnacks:
  post:
    tags: [Nutrition]
    operationId: logIntakeSnack
    summary: Log a snack on a date (auto-creates the day row).
    security: [{ cookieAuth: [] }]
    parameters:
      - { in: path, name: date, required: true, schema: { type: string, format: date } }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/LogSnackRequest' }
    responses:
      '201': { description: Logged, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/IntakeDayDto' } } } }
      '400': { description: Validation, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
intakeSnackById:
  delete:
    tags: [Nutrition]
    operationId: removeIntakeSnack
    summary: Delete a snack.
    security: [{ cookieAuth: [] }]
    parameters:
      - { in: path, name: date, required: true, schema: { type: string, format: date } }
      - { in: path, name: snackId, required: true, schema: { type: string, format: uuid } }
    responses:
      '204': { description: Deleted }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Snack not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
intakeAuditLog:
  get:
    tags: [Nutrition]
    operationId: getIntakeAuditLog
    summary: Paginated audit log for a day.
    security: [{ cookieAuth: [] }]
    parameters:
      - { in: path, name: date, required: true, schema: { type: string, format: date } }
      - { in: query, name: page, schema: { type: integer, minimum: 0, default: 0 } }
      - { in: query, name: size, schema: { type: integer, minimum: 1, maximum: 100, default: 20 } }
    responses:
      '200': { description: Audit page, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/IntakeAuditEntryDtoPage' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Day not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
targetsActivityRange:
  get:
    tags: [Nutrition]
    operationId: getDailyActivityRange
    summary: List daily activity log entries in [from, to].
    security: [{ cookieAuth: [] }]
    parameters:
      - { in: query, name: from, required: true, schema: { type: string, format: date } }
      - { in: query, name: to,   required: true, schema: { type: string, format: date } }
    responses:
      '200': { description: Activity entries, content: { application/json: { schema: { type: array, items: { $ref: '../schemas/nutrition.yaml#/DailyActivityDto' } } } } }
      '400': { description: Invalid range, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
targetsActivityByDate:
  put:
    tags: [Nutrition]
    operationId: upsertDailyActivity
    summary: Upsert the activity entry for a date.
    security: [{ cookieAuth: [] }]
    parameters:
      - { in: path, name: date, required: true, schema: { type: string, format: date } }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/UpsertDailyActivityRequest' }
    responses:
      '200': { description: Upserted, content: { application/json: { schema: { $ref: '../schemas/nutrition.yaml#/DailyActivityDto' } } } }
      '400': { description: Validation, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/nutrition.yaml`

```yaml
IntakeSlotStatus:
  type: string
  enum: [pending, confirmed, overridden, edited, skipped]
IntakeAuditAction:
  type: string
  enum: [prefill, confirm, override, edit, skip, snack_add, snack_remove]
IntakeSource:
  type: string
  enum: [usda, open_food_facts, manual, preference_accompaniment]
PlannedIntakeDto:
  type: object
  properties:
    recipeId: { type: string, format: uuid, nullable: true }
    calories: { type: integer, minimum: 0, nullable: true }
    proteinG: { type: number, format: double, minimum: 0, nullable: true }
    carbsG: { type: number, format: double, minimum: 0, nullable: true }
    fatG: { type: number, format: double, minimum: 0, nullable: true }
    fibreG: { type: number, format: double, minimum: 0, nullable: true }
    micros:
      type: object
      additionalProperties: { type: number, format: double }
      nullable: true
ActualIntakeDto:
  type: object
  required: [status]
  properties:
    status: { $ref: '#/IntakeSlotStatus' }
    calories: { type: integer, minimum: 0, nullable: true }
    proteinG: { type: number, format: double, minimum: 0, nullable: true }
    carbsG: { type: number, format: double, minimum: 0, nullable: true }
    fatG: { type: number, format: double, minimum: 0, nullable: true }
    fibreG: { type: number, format: double, minimum: 0, nullable: true }
    micros:
      type: object
      additionalProperties: { type: number, format: double }
      nullable: true
    overrideFreeText: { type: string, maxLength: 512, nullable: true }
    overriddenAt: { type: string, format: date-time, nullable: true }
    needsAiParse: { type: boolean }
IntakeSlotDto:
  type: object
  required: [id, mealSlot, planned, actual]
  properties:
    id: { type: string, format: uuid }
    mealSlot: { $ref: '#/MealSlot' }
    planned: { $ref: '#/PlannedIntakeDto' }
    actual: { $ref: '#/ActualIntakeDto' }
IntakeSnackDto:
  type: object
  required: [id, freeText, quantityG, calories, proteinG, carbsG, fatG, source, loggedAt]
  properties:
    id: { type: string, format: uuid }
    ingredientMappingKey: { type: string, maxLength: 255, nullable: true }
    freeText: { type: string, minLength: 1, maxLength: 255 }
    quantityG: { type: number, format: double, minimum: 0 }
    calories: { type: integer, minimum: 0 }
    proteinG: { type: number, format: double, minimum: 0 }
    carbsG: { type: number, format: double, minimum: 0 }
    fatG: { type: number, format: double, minimum: 0 }
    fibreG: { type: number, format: double, minimum: 0, nullable: true }
    micros:
      type: object
      additionalProperties: { type: number, format: double }
      nullable: true
    source: { $ref: '#/IntakeSource' }
    loggedAt: { type: string, format: date-time }
IntakeDayDto:
  type: object
  required: [id, userId, onDate, slots, snacks, version]
  properties:
    id: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    onDate: { type: string, format: date }
    planId: { type: string, format: uuid, nullable: true }
    slots:
      type: array
      items: { $ref: '#/IntakeSlotDto' }
    snacks:
      type: array
      items: { $ref: '#/IntakeSnackDto' }
    version: { type: integer, format: int64 }
IntakeEntryDto:
  type: object
  required: [calories, proteinG, carbsG, fatG]
  properties:
    calories: { type: integer, minimum: 0 }
    proteinG: { type: number, format: double, minimum: 0 }
    carbsG: { type: number, format: double, minimum: 0 }
    fatG: { type: number, format: double, minimum: 0 }
    fibreG: { type: number, format: double, minimum: 0, nullable: true }
    micros:
      type: object
      additionalProperties: { type: number, format: double }
      nullable: true
OverrideIntakeRequest:
  type: object
  required: [freeText]
  properties:
    freeText: { type: string, minLength: 1, maxLength: 512 }
LogSnackRequest:
  type: object
  required: [freeText, quantityG, calories, proteinG, carbsG, fatG, source]
  properties:
    freeText: { type: string, minLength: 1, maxLength: 255 }
    ingredientMappingKey: { type: string, maxLength: 255, nullable: true }
    quantityG: { type: number, format: double, minimum: 0 }
    calories: { type: integer, minimum: 0 }
    proteinG: { type: number, format: double, minimum: 0 }
    carbsG: { type: number, format: double, minimum: 0 }
    fatG: { type: number, format: double, minimum: 0 }
    fibreG: { type: number, format: double, minimum: 0, nullable: true }
    micros:
      type: object
      additionalProperties: { type: number, format: double }
      nullable: true
    source: { $ref: '#/IntakeSource' }
    deductFromPantry:
      type: boolean
      default: false
      description: Reserved for nutrition-01l. Currently a no-op.
IntakeAuditEntryDto:
  type: object
  required: [id, intakeDayId, actorUserId, action, occurredAt]
  properties:
    id: { type: string, format: uuid }
    intakeDayId: { type: string, format: uuid }
    actorUserId: { type: string, format: uuid }
    action: { $ref: '#/IntakeAuditAction' }
    mealSlot: { $ref: '#/MealSlot' }       # nullable inline below — but MealSlot is required-non-null in 01a's schema
    snackId: { type: string, format: uuid, nullable: true }
    previousValue: {}
    newValue: {}
    occurredAt: { type: string, format: date-time }
IntakeAuditEntryDtoPage:
  type: object
  additionalProperties: true
  required: [content, page]
  properties:
    content:
      type: array
      items: { $ref: '#/IntakeAuditEntryDto' }
    page:
      type: object
      additionalProperties: true
      properties:
        number: { type: integer, minimum: 0 }
        size: { type: integer, minimum: 1 }
        totalElements: { type: integer, format: int64 }
        totalPages: { type: integer }
DailyActivityDto:
  type: object
  required: [id, userId, onDate, activityLevel]
  properties:
    id: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    onDate: { type: string, format: date }
    activityLevel: { $ref: '#/ActivityLevel' }
    notes: { type: string, maxLength: 255, nullable: true }
    createdAt: { type: string, format: date-time }
UpsertDailyActivityRequest:
  type: object
  required: [activityLevel]
  properties:
    activityLevel: { $ref: '#/ActivityLevel' }
    notes: { type: string, maxLength: 255, nullable: true }
PlannedSlotInputDto:
  type: object
  required: [mealSlot]
  properties:
    mealSlot: { $ref: '#/MealSlot' }
    plannedRecipeId: { type: string, format: uuid, nullable: true }
    plannedCalories: { type: integer, minimum: 0, nullable: true }
    plannedProteinG: { type: number, format: double, minimum: 0, nullable: true }
    plannedCarbsG: { type: number, format: double, minimum: 0, nullable: true }
    plannedFatG: { type: number, format: double, minimum: 0, nullable: true }
    plannedFibreG: { type: number, format: double, minimum: 0, nullable: true }
    plannedMicros:
      type: object
      additionalProperties: { type: number, format: double }
      nullable: true
```

For `IntakeAuditEntryDto.mealSlot` — that field is nullable for SNACK_* actions. Per the gotcha (`$ref + nullable: true` is silently dropped), inline:

```yaml
    mealSlot:
      type: string
      enum: [breakfast, lunch, dinner, snacks]
      nullable: true
```

(Replace the `{ $ref: ... }` from the snippet above with this inline shape.)

**Gotcha applied**: every page schema (`IntakeAuditEntryDtoPage`) uses `additionalProperties: true`. Nullable enums use inline shapes.

### Append to entry `src/main/resources/openapi/openapi.yaml`

Under `paths:`:

```yaml
  /api/v1/nutrition/intake/{date}:
    $ref: 'paths/nutrition.yaml#/intakeForDay'
  /api/v1/nutrition/intake:
    $ref: 'paths/nutrition.yaml#/intakeRange'
  /api/v1/nutrition/intake/{date}/slots/{mealSlot}/confirm:
    $ref: 'paths/nutrition.yaml#/intakeSlotConfirm'
  /api/v1/nutrition/intake/{date}/slots/{mealSlot}/override:
    $ref: 'paths/nutrition.yaml#/intakeSlotOverride'
  /api/v1/nutrition/intake/{date}/slots/{mealSlot}/edit:
    $ref: 'paths/nutrition.yaml#/intakeSlotEdit'
  /api/v1/nutrition/intake/{date}/slots/{mealSlot}/skip:
    $ref: 'paths/nutrition.yaml#/intakeSlotSkip'
  /api/v1/nutrition/intake/{date}/snacks:
    $ref: 'paths/nutrition.yaml#/intakeSnacks'
  /api/v1/nutrition/intake/{date}/snacks/{snackId}:
    $ref: 'paths/nutrition.yaml#/intakeSnackById'
  /api/v1/nutrition/intake/{date}/audit-log:
    $ref: 'paths/nutrition.yaml#/intakeAuditLog'
  /api/v1/nutrition/targets/activity:
    $ref: 'paths/nutrition.yaml#/targetsActivityRange'
  /api/v1/nutrition/targets/activity/{date}:
    $ref: 'paths/nutrition.yaml#/targetsActivityByDate'
```

Under `components.schemas:` (~16 ref lines):

```yaml
    IntakeSlotStatus: { $ref: 'schemas/nutrition.yaml#/IntakeSlotStatus' }
    IntakeAuditAction: { $ref: 'schemas/nutrition.yaml#/IntakeAuditAction' }
    IntakeSource: { $ref: 'schemas/nutrition.yaml#/IntakeSource' }
    PlannedIntakeDto: { $ref: 'schemas/nutrition.yaml#/PlannedIntakeDto' }
    ActualIntakeDto: { $ref: 'schemas/nutrition.yaml#/ActualIntakeDto' }
    IntakeSlotDto: { $ref: 'schemas/nutrition.yaml#/IntakeSlotDto' }
    IntakeSnackDto: { $ref: 'schemas/nutrition.yaml#/IntakeSnackDto' }
    IntakeDayDto: { $ref: 'schemas/nutrition.yaml#/IntakeDayDto' }
    IntakeEntryDto: { $ref: 'schemas/nutrition.yaml#/IntakeEntryDto' }
    OverrideIntakeRequest: { $ref: 'schemas/nutrition.yaml#/OverrideIntakeRequest' }
    LogSnackRequest: { $ref: 'schemas/nutrition.yaml#/LogSnackRequest' }
    IntakeAuditEntryDto: { $ref: 'schemas/nutrition.yaml#/IntakeAuditEntryDto' }
    DailyActivityDto: { $ref: 'schemas/nutrition.yaml#/DailyActivityDto' }
    UpsertDailyActivityRequest: { $ref: 'schemas/nutrition.yaml#/UpsertDailyActivityRequest' }
    PlannedSlotInputDto: { $ref: 'schemas/nutrition.yaml#/PlannedSlotInputDto' }
```

## Verbatim shape snippets

### IntakeDay entity — multi-bag fetch trap; lazy-load via getters inside @Transactional

The 01a `NutritionTargets` entity hit the same trap with three `@OneToMany List<>` collections; same workaround.

```java
@Entity
@Table(name = "nutrition_intake_day",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "on_date"}))
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IntakeDay {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;
  @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
  @Column(name = "on_date", nullable = false, updatable = false) private LocalDate onDate;
  @Column(name = "plan_id") private UUID planId;

  @OneToMany(mappedBy = "intakeDay", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  @Builder.Default private List<IntakeSlot> slots = new ArrayList<>();

  @OneToMany(mappedBy = "intakeDay", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  @Builder.Default private List<IntakeSnack> snacks = new ArrayList<>();

  @OneToMany(mappedBy = "intakeDay", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  @Builder.Default private List<IntakeAuditLog> auditLog = new ArrayList<>();

  @Version @Column(name = "version", nullable = false) private long version;
  @CreationTimestamp @Column(name = "created_at", updatable = false, nullable = false) private Instant createdAt;
  @UpdateTimestamp  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
```

### Service-impl skeleton — confirmFromPlan (idempotent)

```java
@Transactional
public IntakeDayDto confirmFromPlan(UUID userId, LocalDate onDate, MealSlot mealSlot) {
  IntakeDay day = intakeDayRepository.findByUserIdAndOnDate(userId, onDate)
      .orElseThrow(IntakeDayNotFoundException::new);
  // Force lazy-load of slots inside @Transactional
  IntakeSlot slot = day.getSlots().stream()
      .filter(s -> s.getMealSlot() == mealSlot)
      .findFirst()
      .orElseThrow(IntakeSlotNotFoundException::new);
  if (slot.getActualStatus() == IntakeSlotStatus.CONFIRMED) {
    return mapper.toDto(day);                                  // idempotent
  }
  IntakeSlotStatus prev = slot.getActualStatus();
  slot.setActualStatus(IntakeSlotStatus.CONFIRMED);
  slot.setActualCalories(slot.getPlannedCalories());
  slot.setActualProteinG(slot.getPlannedProteinG());
  slot.setActualCarbsG(slot.getPlannedCarbsG());
  slot.setActualFatG(slot.getPlannedFatG());
  slot.setActualFibreG(slot.getPlannedFibreG());
  slot.setActualMicros(slot.getPlannedMicros());
  intakeDayRepository.saveAndFlush(day);   // gotcha: flush so @Version increments before mapping

  intakeAuditLogRepository.save(buildAudit(
      day.getId(), userId, IntakeAuditAction.CONFIRM, mealSlot, null,
      objectMapper.valueToTree(Map.of("status", prev.name())),
      objectMapper.valueToTree(Map.of("status", "CONFIRMED"))));
  publisher.publishEvent(new IntakeLoggedEvent(
      userId, day.getId(), onDate, IntakeAuditAction.CONFIRM, mealSlot, null,
      traceIdFromMdcOrRandom(), Instant.now()));
  return mapper.toDto(day);
}
```

### Service-impl skeleton — overrideIntakeFromFreeText (AI stub)

```java
@Transactional
public IntakeDayDto overrideIntakeFromFreeText(UUID userId, LocalDate onDate, MealSlot mealSlot, String freeText) {
  IntakeDay day = intakeDayRepository.findByUserIdAndOnDate(userId, onDate)
      .orElseThrow(IntakeDayNotFoundException::new);
  IntakeSlot slot = day.getSlots().stream()
      .filter(s -> s.getMealSlot() == mealSlot)
      .findFirst()
      .orElseThrow(IntakeSlotNotFoundException::new);
  // Verbatim free text always preserved.
  slot.setOverrideFreeText(freeText);
  slot.setOverriddenAt(Instant.now());
  slot.setActualStatus(IntakeSlotStatus.OVERRIDDEN);
  slot.setNeedsAiParse(true);    // 01k listener will pick this up
  // Stub: zero actuals so aggregations stay sane
  slot.setActualCalories(0);
  slot.setActualProteinG(BigDecimal.ZERO);
  slot.setActualCarbsG(BigDecimal.ZERO);
  slot.setActualFatG(BigDecimal.ZERO);
  slot.setActualFibreG(BigDecimal.ZERO);
  slot.setActualMicros(null);
  intakeDayRepository.saveAndFlush(day);

  intakeAuditLogRepository.save(buildAudit(
      day.getId(), userId, IntakeAuditAction.OVERRIDE, mealSlot, null,
      /* prev */ objectMapper.valueToTree(Map.of("status", slot.getActualStatus().name())),
      /* new */  objectMapper.valueToTree(Map.of("status", "OVERRIDDEN", "freeText", freeText))));
  publisher.publishEvent(new IntakeLoggedEvent(
      userId, day.getId(), onDate, IntakeAuditAction.OVERRIDE, mealSlot, null,
      traceIdFromMdcOrRandom(), Instant.now()));
  return mapper.toDto(day);
}
```

### `findOrCreateDay` for snacks

```java
private IntakeDay findOrCreateDay(UUID userId, LocalDate onDate) {
  return intakeDayRepository.findByUserIdAndOnDate(userId, onDate)
      .orElseGet(() -> intakeDayRepository.saveAndFlush(IntakeDay.builder()
          .id(UUID.randomUUID()).userId(userId).onDate(onDate).build()));
}
```

## Edge-case checklist

- [ ] `GET /intake/{date}` for a user with no day row → 404 `intake-day-not-found` ProblemDetail
- [ ] `GET /intake/{date}` for a user with a day row → 200, slots and snacks both present (lazy-loaded inside `@Transactional`)
- [ ] `GET /intake?from=&to=` with `from > to` → 400 `invalid-intake-range`; with range > 35 days → 400; with valid 7-day range → 200 returning only days that exist
- [ ] `POST /confirm` for a `PENDING` slot → 200, slot now `CONFIRMED`, actuals copied from planned, audit row written, event published
- [ ] `POST /confirm` for an already-`CONFIRMED` slot → 200 idempotent, no audit row, no event
- [ ] `POST /confirm` for a non-existent day → 404; non-existent slot in existing day → 404
- [ ] `POST /override` with valid free text → 200, `actualStatus = OVERRIDDEN`, `overrideFreeText` populated verbatim, `needsAiParse = true`, actuals zeroed, audit row written, event published
- [ ] `POST /override` validation: `freeText` empty → 400; > 512 chars → 400
- [ ] `POST /edit` with valid `IntakeEntryDto` → 200, `actualStatus = EDITED`, values written, audit row written
- [ ] `POST /edit` validation: negative `calories` → 400
- [ ] `POST /skip` → 200, `actualStatus = SKIPPED`, actuals zeroed, audit row, event
- [ ] `POST /snacks` for a date with no day row → auto-creates the day, 201, snack row written, audit `(SNACK_ADD)`, event
- [ ] `POST /snacks` validation: `quantityG < 0` → 400; `freeText` empty → 400
- [ ] `POST /snacks` with `deductFromPantry: true` → currently a no-op; INFO-logged; **no** call to ProvisionUpdateService (verified via mock)
- [ ] `DELETE /snacks/{snackId}` for an existing snack → 204, snack row gone, audit `(SNACK_REMOVE)` written, event
- [ ] `DELETE /snacks/{snackId}` for a snack on a different date or different user's day → 404
- [ ] `GET /audit-log` paginated newest-first; default size 20; `size > 100` clamped
- [ ] `PUT /targets/activity/{date}` first call → 200 (NOT 201 per LLD spec — upsert returns 200 either way), row created
- [ ] `PUT /targets/activity/{date}` second call → 200, row updated (no `@Version` so no conflict path); validation: `notes > 255` → 400
- [ ] `GET /targets/activity?from=&to=` analogous to intake range — 400 on bad range, max 35 days
- [ ] No raw `userId` from request body / query — server resolved
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter)
- [ ] `NutritionBoundaryTest` (from 01a) still passes — new repos in `domain/repository`
- [ ] `IntakeLoggedEvent` published exactly once per write (NOT per audit row); skipped on idempotent confirm
- [ ] No N+1 — `getIntakeForDay` does at most 3 SELECTs (root + slots + snacks); audit-log is its own paginated query

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601600500__nutrition_create_daily_activity_log.sql
NEW   src/main/resources/db/migration/V20260601600600__nutrition_create_intake_day.sql
NEW   src/main/resources/db/migration/V20260601600700__nutrition_create_intake_slot.sql
NEW   src/main/resources/db/migration/V20260601600800__nutrition_create_intake_snack.sql
NEW   src/main/resources/db/migration/V20260601600900__nutrition_create_intake_audit.sql

NEW   src/main/java/com/example/mealprep/nutrition/api/controller/IntakeController.java
NEW   src/main/java/com/example/mealprep/nutrition/api/controller/DailyActivityController.java       (mounted under /api/v1/nutrition/targets/activity per LLD's TargetsController split)
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IntakeDayDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IntakeSlotDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IntakeSnackDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/PlannedIntakeDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/ActualIntakeDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IntakeEntryDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IntakeAuditEntryDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/OverrideIntakeRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/LogSnackRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/PlannedSlotInputDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/DailyActivityDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/UpsertDailyActivityRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/mapper/IntakeMapper.java
NEW   src/main/java/com/example/mealprep/nutrition/api/mapper/IntakeSlotMapper.java
NEW   src/main/java/com/example/mealprep/nutrition/api/mapper/IntakeSnackMapper.java
NEW   src/main/java/com/example/mealprep/nutrition/api/mapper/IntakeAuditMapper.java
NEW   src/main/java/com/example/mealprep/nutrition/api/mapper/DailyActivityMapper.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/IntakeDay.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/IntakeSlot.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/IntakeSnack.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/IntakeAuditLog.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/DailyActivityLog.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/IntakeSlotStatus.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/IntakeSource.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/IntakeAuditAction.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/IntakeDayRepository.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/IntakeAuditRepository.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/DailyActivityLogRepository.java
NEW   src/main/java/com/example/mealprep/nutrition/event/IntakeLoggedEvent.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/IntakeDayNotFoundException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/IntakeSlotNotFoundException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/IntakeSnackNotFoundException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/InvalidIntakeRangeException.java

MOD   src/main/java/com/example/mealprep/nutrition/api/NutritionExceptionHandler.java                (append 4 @ExceptionHandler methods; KEEP @Order(HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionQueryService.java         (append 5 methods)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionUpdateService.java        (append 8 methods)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java (implement new methods)
MOD   src/main/java/com/example/mealprep/nutrition/NutritionModule.java                              (no change to re-exports)

MOD   src/main/resources/openapi/paths/nutrition.yaml                                                (append 11 new path-items)
MOD   src/main/resources/openapi/schemas/nutrition.yaml                                              (append 16 new schemas)
MOD   src/main/resources/openapi/openapi.yaml                                                        (11 lines under paths:; 16 lines under components.schemas:)

NEW   src/test/java/com/example/mealprep/nutrition/IntakeFlowIT.java
NEW   src/test/java/com/example/mealprep/nutrition/DailyActivityFlowIT.java
NEW   src/test/java/com/example/mealprep/nutrition/PrefillFromPlanIT.java                           (in-process service-call IT for the deferred planner flow)
MOD   src/test/java/com/example/mealprep/nutrition/NutritionServiceImplTest.java                    (append unit coverage for confirm/override/edit/skip/log-snack/remove-snack/upsertDailyActivity)
MOD   src/test/java/com/example/mealprep/nutrition/testdata/NutritionTestData.java                  (append IntakeDay + slot fixtures)
```

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`
- `NutritionBoundaryTest` is unchanged (new repos in the existing package; rule covers them).

Total: ~30 new files (counting DTOs + mappers + entities + enums). At the higher end of round-2 scope but still single-ticket-sized; if the agent reports overrun see the pre-split note in the Summary.

## Dependencies

- **Hard dependency**: `nutrition-01a` (merged) — `NutritionTargets`, `MealSlot`, `ActivityLevel`, `ActorKind`, `NutritionQueryService`, `NutritionUpdateService`, `NutritionExceptionHandler`, `NutritionBoundaryTest`, `NutritionException`, `NutritionTargetsNotFoundException`.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged).
- **Sibling tickets running serially in this round** (Wave 2 round 2): `household-01b`, `provisions-01b`, `recipe-01b`. None touch nutrition files; this ticket touches no household / provisions / recipe files.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] **`NutritionExceptionHandler` continues to be `@Order(Ordered.HIGHEST_PRECEDENCE)`** — appending handlers must not remove the annotation
- [ ] `saveAndFlush` used in `confirmFromPlan` / `editIntakeManually` / `skipMeal` / `overrideIntakeFromFreeText` so response DTOs reflect the bumped `@Version`
- [ ] No regression on existing tests, including 01a's targets IT suite
- [ ] No N+1 — verified via Hibernate stats on `getIntakeForDay`
- [ ] Lazy-load of children inside `@Transactional` confirmed (no `MultipleBagFetchException` — that's why `IntakeDayRepository` has NO `@EntityGraph`)
- [ ] `IntakeLoggedEvent` published exactly once per write; verified by an `EventPublicationIT`-style test

## What's NOT in scope

- Food/mood journal (`FoodMoodJournalEntry`, journal endpoints, `getJournalEntriesForFeedbackContext`) → **nutrition-01c**
- `IngredientMapping` cache, USDA / OFF clients, `IngredientMappingPipeline`, lookup endpoints → **nutrition-01d**
- `HealthDirective` queue + inbound / accept / reject endpoints + `DirectiveSafetyGate` + `DirectiveApplier` → **nutrition-01e**
- `NutritionCalculationService` (recipe save-time + `RecipeEvolvedEvent` listener) → **nutrition-01f**
- `NutritionFloorGateService` for the planner → **nutrition-01g**
- `IntakeAggregator`, `WeeklyAggregateDto`, weekly-rollup endpoints, `getDailyAggregate` / `getWeeklyAggregate`, `DivergenceDetector`, `NutritionIntakeDivergedEvent` → **nutrition-01h**
- `MealCookedEvent` listener (auto-confirm) → **nutrition-01i** (depends on planner)
- `applyFeedback(NutritionFeedbackRequest)` → **nutrition-01j**
- AI-driven free-text override parsing (`IntakeOverrideParserTask`) — 01b ships the AI-stub override path → **nutrition-01k** (depends on AI module)
- Cross-module pantry-deduct (`provisionUpdateService.deductFromInventoryByMappingKey`) → **nutrition-01l** (depends on a follow-up to provisions-01b)
- DRI seed (`R__nutrition_seed_dri_defaults.sql`) → **nutrition-01c**
- `initialiseTargets` auto-seed on user creation → depends on DRI seed (nutrition-01c)
- The four custom validators (`@ValidPerMealDistribution`, `@ValidActivityProfile`, `@ValidDirectiveInstruction`) → land with their respective concerns
- HTTP endpoint for `prefillFromPlan` (in-process only in 01b)
- `getRecentIntakeTotals(userId, from, to)` planner-helper → **nutrition-01h** (with the aggregator)

Squash-merge with: `feat(nutrition): 01b — intake tracking (day/slot/snack/audit) + daily-activity log + AI-stub override`
