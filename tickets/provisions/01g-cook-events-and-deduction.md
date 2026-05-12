# Ticket: provisions — 01g Cook Events + `InventoryDeductionEngine` + Sealed `ProvisionChangedEvent` + `MealCookedEvent` Listener

## Summary

Layer the **cook-event consumption flow** on top of 01a..01f. Per [LLD §Flow 1 lines 604-625](../../lld/provisions.md), [LLD §Flow 3 lines 642-648](../../lld/provisions.md), [LLD §`InventoryDeductionEngine` line 44](../../lld/provisions.md), [LLD §`ProvisionUpdateService.applyCookEvent` / `applyMealConsumption` / `applyStandaloneConsumption` lines 423-425](../../lld/provisions.md), [LLD §`CookEventCommand` lines 449-457](../../lld/provisions.md), [LLD §Events lines 556-588](../../lld/provisions.md). Ships:

- **`ProvisionChangedEvent` sealed interface** + the 5 concrete record variants per LLD line 560-570 (`ItemAddedFromGroceryEvent`, `ItemSpoiledEvent`, `ItemRanOutEvent`, `SubstitutionAcceptedEvent`, `ItemQuantityAdjustedEvent`, plus the catch-all `GenericProvisionChangedEvent`). This is the **sealed-base refactor** flagged in provisions-01a's LLD-divergence note ("the sealed `ProvisionChangedEvent` base lands with 01g (cook-event flow) — see LLD divergence note in the summary").
- **`InventoryDeductionEngine`** — FIFO-by-expiry deduction helper in `provisions/domain/service/internal/`. Loads active inventory rows for `(userId, ingredientMappingKey)` ordered by `expiry_date ASC NULLS LAST`, walks rows decrementing, floors at zero, emits `UnderflowFlagDto` when requested exceeds available.
- **`applyCookEvent(userId, CookEventCommand)`** — top-level `@Transactional`. Multiplies requested quantities by `proportionOfRecipe`, calls `InventoryDeductionEngine` per ingredient, writes `InventoryAuditLog` rows (`actor = COOK_EVENT`), coalesces a single `ItemQuantityAdjustedEvent` via `ProvisionEventBatcher`, returns `InventoryDeductionResultDto`.
- **`applyMealConsumption(userId, MealConsumptionCommand)`** — simpler variant; decrements one specific `inventoryItemId` by N portions; audit `actor = COOK_EVENT` (per LLD line 568); publishes `ItemQuantityAdjustedEvent(source=MEAL_CONSUMPTION)`.
- **`applyStandaloneConsumption(userId, StandaloneConsumptionCommand)`** — Nutrition Logger path. Finds active rows by `(userId, ingredientMappingKey)`; **no-op if none** (per LLD line 646 — "None → return without error"); when `userConfirmedDeduction == true`, decrements oldest-expiry row by `quantity`, floors at zero, audits `actor = NUTRITION_LOGGER`, publishes `ItemQuantityAdjustedEvent(source=STANDALONE_LOG)`.
- **Cook-event idempotency**: new table `provision_cook_event_dedupe` per LLD line 620, PK `(meal_slot_id, dedupe_key)`, gates the listener; duplicate `MealCookedEvent` for the same `(mealSlotId, dedupeKey)` is a no-op.
- **`MealCookedEvent` listener** — **SPI surface only, no wiring**. The planner module doesn't exist; the cook listener that translates `MealCookedEvent` → `applyCookEvent` would live in a planner→provisions bridge. **01g ships the listener `@Component` behind `@ConditionalOnClass(name = "com.example.mealprep.planner.event.MealCookedEvent")`** so the class is dormant until the planner ships its event record. Until then, `applyCookEvent` is invoked **directly** via cross-module call from the future planner. 01g's listener compiles even with the planner absent.
- **3 new REST endpoints** for manual invocation: `POST /api/v1/provisions/cook-event`, `POST /api/v1/provisions/meal-consumption`, `POST /api/v1/provisions/standalone-consumption`. **Worth user review** — alternative is to keep these in-process only. v1 ships the REST surface so the operator / nutrition-logger UI / future planner have a uniform entry point.

This is the **biggest 01g ticket** — flag as **50-60 minute estimated runtime** rather than the usual 30-45. May tip into split if the agent reports scope strain; pre-split alternative noted at the end.

## LLD divergence notes

### `ProvisionEventBatcher` already exists in 01a/01b?

LLD §package layout line 51 lists `ProvisionEventBatcher` in `domain/service/internal/`. Provisions-01a's `InventoryItemUpsertedEvent` was published directly (per 01a's ticket line 72 LLD divergence) without the batcher. **01g ships the batcher** — implementing the LLD's "Per-tx state via `TransactionSynchronizationManager`; flushes one event per kind at AFTER_COMMIT with collected `affectedItemIds`" (LLD line 692). The 01a `InventoryItemUpsertedEvent` is a separate event type and remains direct-published; the batcher coalesces only the sealed-event variants.

### Sealed-event refactor — backward compatibility with 01a's `InventoryItemUpsertedEvent`

Provisions-01a shipped `InventoryItemUpsertedEvent(UUID itemId, UUID userId, AuditActor actor, UUID traceId, Instant occurredAt)` per 01a's LLD divergence note. This event is **NOT** in the LLD's enumeration (line 560-570) — it was the parent-ticket's stop-gap. **01g's choices**:

- **Option 1 (chosen)**: keep `InventoryItemUpsertedEvent` as a separate non-sealed event published directly from `createInventoryItem`/`updateInventoryItem`. The sealed `ProvisionChangedEvent` hierarchy covers cook-event / consumption / spoil / grocery flows that have **no existing concrete record yet**. Zero refactor to 01a's flow.
- **Option 2 (rejected)**: retrofit `InventoryItemUpsertedEvent` to permit-clause the sealed base. Rejected because it changes 01a's contract for downstream consumers (none exist yet, but contract churn is contract churn).
- **Worth user review** — the LLD's sealed-base design wants ALL provision events under the umbrella; Option 1 leaves `InventoryItemUpsertedEvent` outside. If the future planner needs a single subscription, a wrapper `ItemQuantityAdjustedEvent(source=MANUAL)` is already in the sealed hierarchy and can be republished from `updateInventoryItem`. Punted.

### `MealCookedEvent` listener — SPI shape only

Parent guidance: "ship the SPI surface ... defer the actual listener wiring". `MealCookedEvent` is declared in technical-architecture's cross-module event catalogue and per LLD line 593-595 is **CONSUMED** by provisions. The publisher (planner module) doesn't exist. 01g ships:

- Listener class `CookEventListener` in `provisions/domain/service/internal/`, `@Component @ConditionalOnClass(name = "com.example.mealprep.planner.event.MealCookedEvent")`.
- The listener's `@TransactionalEventListener(phase = AFTER_COMMIT) onMealCooked(MealCookedEvent event)` method invokes `RecipeQueryService.getById(event.recipeId())` to fetch the recipe's ingredient list, then builds a `CookEventCommand` and calls `applyCookEvent`.
- **The `MealCookedEvent` record itself is NOT shipped in 01g** — it's the planner's record (cross-module ID-pattern dictates: publisher owns the record). The `@ConditionalOnClass` keeps the listener dormant until the planner ships its event.
- **Verification today**: the IT loads the full Spring context **without** the planner module → the `@ConditionalOnClass` evaluates false → the `CookEventListener` bean is NOT registered → context loads cleanly. **Worth user review** — alternative is to inline a TEMPORARY `MealCookedEvent` record in provisions-01g and delete it when the planner ships; rejected because cross-module event records belong to the publisher (style guide).

### `applyMealConsumption` — actor enum

LLD line 568 lists `ItemAdjustmentSource.MEAL_CONSUMPTION`. The `AuditActor` enum from 01a is `{USER, COOK_EVENT, GROCERY_IMPORT, NUTRITION_LOGGER, SYSTEM}`. **`applyMealConsumption`** writes audit rows with `actor = COOK_EVENT` (closest match) and publishes `ItemQuantityAdjustedEvent` with `source = ItemAdjustmentSource.MEAL_CONSUMPTION`. **Worth user review** — the two enums diverge intentionally (auditor vs adjustment source). The audit's `actor` is "who-pressed-the-button"; the event's `source` is "what-kind-of-adjustment".

### Strict-mode 422 vs floor-at-zero default

LLD line 535 says: "`InventoryUnderflowException` is a 422 only when the caller declares strict mode on a cook-event command. The default flow floors at zero and returns `UnderflowFlagDto`s." **01g implements both**:

- `CookEventCommand.strict` field — Boolean, **default false**. (LLD §Command records line 449-455 doesn't declare it; **01g extends `CookEventCommand` with `strict`**. LLD divergence.)
- `strict = false` (default) → floor at zero, return underflows in `InventoryDeductionResultDto.underflows`.
- `strict = true` → first underflow throws `InventoryUnderflowException` mapped to 422 (existing from 01a or NEW in 01g).

### Cook-event idempotency table location

LLD line 620: "New table `provision_cook_event_dedupe` with primary key `(meal_slot_id, dedupe_key)` and a `created_at` column." **01g ships the migration**: `V20260601700600__provision_create_cook_event_dedupe.sql` (next slot after 01e's `V20260601700500__provision_create_waste_log.sql`).

### Daily sweep for the dedupe table

LLD line 623: "Daily sweep deletes rows older than 24h — bounded storage". **01g ships the sweep** as a `@Scheduled` method on `CookEventDedupeRepository` (or a small `CookEventDedupeSweeper`). `@Scheduled(cron = "${mealprep.provision.cook-dedupe.cron:0 0 4 * * *}")` — daily at 04:00 UTC.

## Defers (still out of scope after 01g)

- `GroceryImportProcessor` + `applyGroceryOrder` → **provisions-01h**
- `StapleStateTransitioner` (stocked → low → out) → **provisions-01i** (uses `ItemRanOutEvent` already shipped here as a sealed-base variant)
- `BatchCookSplitter` (fridge/freezer split per LLD line 611) → **provisions-01j**
- `ExpiryRule` registry + `ItemNearingExpiryEvent` daily sweep → **provisions-01k**
- `applyFeedback(ProvisionsFeedbackCommand)` → **provisions-01l**
- Pantry-tracking-disabled gate → **provisions-01m**
- The future planner's `MealCookedEvent` record + the listener wire-up — owned by the planner module
- Resilience4j `@Retry` on `applyCookEvent` (LLD line 616 — for concurrent same-row cook events) — **01g ships without retry**; agent reports if `OptimisticLockException` flows through cleanly. Retry can be added in a follow-up; the `@Version` check still serialises correctness. **Worth user review.**
- `UnitConverter` helper (LLD line 609 — for non-canonical unit conversions). 01g assumes the planner sends quantities in canonical units (grams / mL / units); mismatches log WARN and emit underflow flags rather than throw. The full `UnitConverter` ships in a follow-up if real-world data requires it.

## Behavioural spec

### `ProvisionChangedEvent` sealed hierarchy

1. New public sealed interface `com.example.mealprep.provisions.event.ProvisionChangedEvent` in `provisions/event/`:
   ```java
   public sealed interface ProvisionChangedEvent permits
       ItemAddedFromGroceryEvent,
       ItemSpoiledEvent,
       ItemRanOutEvent,
       SubstitutionAcceptedEvent,
       ItemQuantityAdjustedEvent,
       GenericProvisionChangedEvent {
     UUID userId();
     List<UUID> affectedItemIds();
     UUID traceId();
     Instant occurredAt();
   }
   ```
2. Six concrete `record`s — each `implements ProvisionChangedEvent`:
   - `ItemAddedFromGroceryEvent(UUID userId, List<UUID> affectedItemIds, String supplier, String orderRef, UUID traceId, Instant occurredAt)` — **published in 01h**, but the record ships here so the sealed hierarchy is complete.
   - `ItemSpoiledEvent(UUID userId, List<UUID> affectedItemIds, String reason, UUID traceId, Instant occurredAt)` — published when a future `markSpoiled` flow lands.
   - `ItemRanOutEvent(UUID userId, List<UUID> affectedItemIds, String ingredientMappingKey, boolean wasStaple, UUID traceId, Instant occurredAt)` — **published by 01g's deduction engine** when an item's quantity hits zero AND `isStaple == true` per LLD line 613.
   - `SubstitutionAcceptedEvent(UUID userId, List<UUID> affectedItemIds, String orderedProductId, String substitutedProductId, UUID traceId, Instant occurredAt)` — published in 01h.
   - `ItemQuantityAdjustedEvent(UUID userId, List<UUID> affectedItemIds, ItemAdjustmentSource source, UUID traceId, Instant occurredAt)` — **published by 01g's `applyCookEvent`, `applyMealConsumption`, `applyStandaloneConsumption`**. `source` enum values: `COOK_EVENT, MEAL_CONSUMPTION, MANUAL, STANDALONE_LOG, WASTE` per LLD line 568.
   - `GenericProvisionChangedEvent(UUID userId, List<UUID> affectedItemIds, String changeType, UUID traceId, Instant occurredAt)` — catch-all per LLD line 569.
3. New enum `ItemAdjustmentSource` in `provisions/event/` per LLD line 568: `{COOK_EVENT, MEAL_CONSUMPTION, MANUAL, STANDALONE_LOG, WASTE}`.

### `ProvisionEventBatcher`

4. New `@Component` `ProvisionEventBatcher` in `provisions/domain/service/internal/`. **Per-tx state via `TransactionSynchronizationManager`** (LLD line 692).
5. **API**:
   ```java
   void recordAdjustment(UUID userId, UUID itemId, ItemAdjustmentSource source, UUID traceId);
   void recordRanOut(UUID userId, UUID itemId, String ingredientMappingKey, boolean wasStaple, UUID traceId);
   ```
6. **Internal**: per-tx `Map<EventKind, MutableEventState>` registered via `TransactionSynchronizationManager.registerSynchronization`. On `afterCommit`:
   - One `ItemQuantityAdjustedEvent` per distinct `(userId, source)` pair, with all `affectedItemIds` collected.
   - One `ItemRanOutEvent` per distinct `(userId, ingredientMappingKey)` pair.
   - All published via `ApplicationEventPublisher`.
7. **Single-event-per-operation contract** preserved (LLD line 572): even when one cook event touches 15 items, ONE `ItemQuantityAdjustedEvent(source=COOK_EVENT)` carries all 15 IDs.
8. **No tx → direct publish** (degrade gracefully if invoked outside an active tx).

### `InventoryDeductionEngine`

9. New package-private `@Component` `InventoryDeductionEngine` in `provisions/domain/service/internal/`.
10. **API**:
    ```java
    DeductionOutcome deduct(UUID userId, String ingredientMappingKey,
                            BigDecimal requested, String unit, boolean strict);
    ```
11. **Algorithm** per LLD line 609:
    - `List<InventoryItem> rows = inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(userId, ingredientMappingKey)` — NEW repo method (verify naming with 01a; `findAllByUserIdAndIngredientMappingKeyAndItemStatus(userId, key, ACTIVE)` may already exist; add ordering if not). Ordering: `expiry_date ASC NULLS LAST` (oldest-expiring first; rows with no expiry last).
    - Walk rows. Per row:
      - **Unit handling**: if `row.unit != requestedUnit` AND no conversion available → log WARN, add to `underflows` list, do NOT decrement; continue to next row.
      - Else `take = min(row.quantity, remaining)`.
      - `row.quantity -= take`. If `row.quantity == 0` then `row.itemStatus = EXHAUSTED` AND if `row.isStaple == true` AND `row.status` was `STOCKED` then `row.status = OUT` (LLD line 666 staple transition — minimal version; full transitioner is 01i).
      - `remaining -= take`.
      - Audit row appended: `actor = COOK_EVENT`, `fieldChanged = "quantity"`, `previousValueJson = {quantity: <old>}`, `newValueJson = {quantity: <new>}`.
      - Each decremented item ID is recorded on `ProvisionEventBatcher.recordAdjustment(userId, itemId, ItemAdjustmentSource.COOK_EVENT, traceId)`.
      - If row became staple-out → `eventBatcher.recordRanOut(userId, itemId, ingredientMappingKey, true, traceId)`.
      - **Break** when `remaining == 0`.
    - **Floor at zero** (`strict == false`): if `remaining > 0` after walking all rows, append `new UnderflowFlagDto(ingredientMappingKey, requested, requested - remaining)` to the result.
    - **Strict** (`strict == true`): if `remaining > 0` after walking all rows, throw `InventoryUnderflowException` (422).
12. **Return** `DeductionOutcome(deductedItemIds, exhaustedItemIds, underflows)`.

### `applyCookEvent`

13. **Service signature** (LLD line 423): `InventoryDeductionResultDto applyCookEvent(UUID userId, CookEventCommand command)`. Joins `ProvisionServiceImpl`.
14. **Transactional, write**. `@Transactional` (REQUIRED — top-level per LLD line 684).
15. **Step 1 — idempotency check** (LLD line 618-625):
    - Compute `dedupeKey`: if `command.dedupeKey()` non-null, use it. Else hash `(mealSlotId, recipeId, servingsCooked, isBatchCook)` via SHA-256 → base64 (32 chars).
    - `cookEventDedupeRepository.existsByMealSlotIdAndDedupeKey(command.mealSlotId(), dedupeKey)` → true → INFO log `"duplicate cook event mealSlot={} dedupeKey={}; no-op"` + return `InventoryDeductionResultDto(emptyList, emptyList, emptyList)`.
    - Else insert a `ProvisionCookEventDedupe` row with `(mealSlotId, dedupeKey, createdAt = now)`.
16. **Step 2 — proportion-of-recipe scaling** (LLD line 608): per ingredient `usage`, `scaledQuantity = usage.quantity() * command.proportionOfRecipe()` (default 1.0). BigDecimal arithmetic, scale 3.
17. **Step 3 — per-ingredient deduction**: for each `RecipeIngredientUsage` in `command.ingredientsUsed()`:
    - `engine.deduct(userId, usage.ingredientMappingKey(), scaledQuantity, usage.unit(), command.strict())` → `DeductionOutcome`.
    - Accumulate `deductedItemIds`, `exhaustedItemIds`, `underflows` across all ingredients.
18. **Step 4 — batch cook** (LLD line 611): **DEFERRED to 01j** per the "Defers" list above. 01g rejects `command.isBatchCook() == true` with 422 `BatchCookNotSupportedException` (NEW) — the planner doesn't ship batch cooks in v1.
19. **Step 5 — event coalescing**: already happened inside the deduction engine via `ProvisionEventBatcher`. After commit, exactly one `ItemQuantityAdjustedEvent(source=COOK_EVENT)` fires carrying all touched IDs + zero-or-more `ItemRanOutEvent`s for staples that hit OUT.
20. **Return** `InventoryDeductionResultDto(deductedItemIds (mapped to `InventoryItemDto` via mapper + fresh-read), exhaustedItemIds, underflows)`.

### `applyMealConsumption`

21. **Service signature** (LLD line 424): `InventoryDeductionResultDto applyMealConsumption(UUID userId, MealConsumptionCommand command)`.
22. `@Transactional`. Loads `inventoryItem = inventoryItemRepository.findById(command.inventoryItemId())` → 404 `InventoryItemNotFoundException` if missing. **Ownership**: `item.userId == userId` else 404 (don't leak).
23. **Quantity reduction**: `item.quantity -= command.portions()`. Floor at zero. If `item.quantity == 0` then `itemStatus = EXHAUSTED`.
24. **Audit**: one row, `actor = COOK_EVENT`, `fieldChanged = "quantity"`.
25. **Event**: `eventBatcher.recordAdjustment(userId, command.inventoryItemId(), ItemAdjustmentSource.MEAL_CONSUMPTION, command.traceId())`.
26. Returns `InventoryDeductionResultDto(List.of(itemDto), exhaustedItemIds, List.of())`.

### `applyStandaloneConsumption`

27. **Service signature** (LLD line 425): `InventoryItemDto applyStandaloneConsumption(UUID userId, StandaloneConsumptionCommand command)`.
28. `@Transactional`. Per LLD line 646: "Find active inventory rows by `(userId, ingredientMappingKey)`. None → return without error (unrelated logged item)."
29. **None found**: return null-equivalent — record-wise, since the method returns `InventoryItemDto`, **01g changes the signature to `Optional<InventoryItemDto>`**. **LLD divergence**: more honest type. **Worth user review** — alternative is to return a sentinel; rejected because `Optional` is the idiomatic Spring/Java path.
30. **`userConfirmedDeduction == false`** (LLD line 647): return `Optional.of(itemDto)` with NO mutation. The Nutrition Logger UI uses this for "see what's in pantry without changing it" preview.
31. **`userConfirmedDeduction == true`**: pick oldest-expiry row (sort `expiry_date ASC NULLS LAST`); decrement by `command.quantity()`; floor at zero; audit `actor = NUTRITION_LOGGER`; emit `eventBatcher.recordAdjustment(userId, itemId, ItemAdjustmentSource.STANDALONE_LOG, traceId)`.
32. Returns `Optional<InventoryItemDto>` of the mutated row.

### `MealCookedEvent` listener (SPI surface)

33. New `@Component` `CookEventListener` in `provisions/domain/service/internal/`. Class-level annotations:
    ```java
    @Component
    @ConditionalOnClass(name = "com.example.mealprep.planner.event.MealCookedEvent")
    class CookEventListener {
      private final ProvisionUpdateService provisions;
      private final RecipeQueryService recipeQuery;

      @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
      public void onMealCooked(MealCookedEvent event) {
        // 1. fetch the recipe's ingredient list via recipeQuery
        // 2. build CookEventCommand
        // 3. call provisions.applyCookEvent(event.userId(), command)
      }
    }
    ```
34. **The `MealCookedEvent` record is NOT defined in provisions-01g** — it's the planner's record. The `@ConditionalOnClass` keeps the listener dormant.
35. **No test coverage for the listener body** in 01g (the planner doesn't exist). Coverage is on `applyCookEvent` directly + the conditional wiring.

### REST endpoints

36. **`POST /api/v1/provisions/cook-event`** — new endpoint on a new controller `CookEventController` under `provisions/api/controller/`. Authenticated. Caller's `userId` resolved server-side. Request body: `CookEventCommand` (NEW DTO, see below). Response: 200 + `InventoryDeductionResultDto`. **400** on validation. **422** on strict-mode underflow. **422** on batch cook (not supported yet).
37. **`POST /api/v1/provisions/meal-consumption`** — request body `MealConsumptionCommand`. Response: 200 + `InventoryDeductionResultDto`. **404** if item not found / not owned.
38. **`POST /api/v1/provisions/standalone-consumption`** — request body `StandaloneConsumptionCommand`. Response: 200 + `Optional<InventoryItemDto>` — serialised as `null` when empty (use `@JsonInclude(Include.ALWAYS)` semantics so an absent body is still HTTP 200). **Worth user review** — alternative is 204 when absent; rejected because the caller still needs the "we didn't find anything" signal which is cleaner as JSON `null`.

### Errors

39. New exceptions:
    - `InventoryUnderflowException` (422 — `type = .../inventory-underflow`) — strict-mode cook event with insufficient stock.
    - `BatchCookNotSupportedException` (422 — `type = .../batch-cook-not-supported`) — v1 stop-gap until 01j.
40. **Append two `@ExceptionHandler` methods** to existing `ProvisionsExceptionHandler` (already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do NOT modify `config/GlobalExceptionHandler.java`.

### Cross-module facade

41. The existing `ProvisionsModule` already re-exports `ProvisionUpdateService` (per 01a). 01g's `applyCookEvent` / `applyMealConsumption` / `applyStandaloneConsumption` join the same interface — no facade changes.

## Database

```
src/main/resources/db/migration/V20260601700600__provision_create_cook_event_dedupe.sql   new
```

Schema:

```sql
-- Provisions module — 01g cook-event idempotency table.
-- See lld/provisions.md §Flow 1 / Idempotency line 620.
-- Daily sweep deletes rows older than 24h — bounded storage.

CREATE TABLE provision_cook_event_dedupe (
    meal_slot_id  uuid          NOT NULL,
    dedupe_key    varchar(64)   NOT NULL,
    created_at    timestamptz   NOT NULL,
    PRIMARY KEY (meal_slot_id, dedupe_key)
);

-- Sweep query: DELETE WHERE created_at < now() - INTERVAL '24 hours'.
CREATE INDEX idx_provision_cook_event_dedupe_created_at
    ON provision_cook_event_dedupe (created_at);
```

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/provisions.yaml`

(File extended by 01a..01f — append 3 new path-items below 01f's most recent block. Do NOT touch existing path-items.)

```yaml
provisionsCookEvent:
  post:
    tags: [Provisions]
    operationId: applyCookEvent
    summary: 'Apply a cook event for the calling user; deducts ingredients FIFO-by-expiry from the pantry.'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/CookEventCommand' }
    responses:
      '200':
        description: 'Deduction outcome including any underflow flags.'
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/InventoryDeductionResultDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Strict-mode underflow or batch cook (not supported in v1)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

provisionsMealConsumption:
  post:
    tags: [Provisions]
    operationId: applyMealConsumption
    summary: 'Decrement a specific inventory item by a number of portions.'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/MealConsumptionCommand' }
    responses:
      '200':
        description: 'Deduction outcome.'
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/InventoryDeductionResultDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Inventory item not found or not owned by caller', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

provisionsStandaloneConsumption:
  post:
    tags: [Provisions]
    operationId: applyStandaloneConsumption
    summary: 'Standalone food log — finds an active inventory row matching ingredientMappingKey and optionally decrements.'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/StandaloneConsumptionCommand' }
    responses:
      '200':
        description: 'The matched inventory item (nullable; null when no matching active row exists).'
        content:
          application/json:
            schema:
              type: object
              nullable: true
              description: 'InventoryItemDto inlined to honor the round-1/4/6 nullable+$ref sticky-trap avoidance — do NOT use $ref with nullable here.'
              properties:
                id: { type: string, format: uuid }
                userId: { type: string, format: uuid }
                name: { type: string, maxLength: 128 }
                quantity: { type: number, format: double, nullable: true }
                unit: { type: string, maxLength: 16, nullable: true }
                ingredientMappingKey: { type: string, maxLength: 128, nullable: true }
                itemStatus: { type: string, enum: [ACTIVE, EXHAUSTED, SPOILED, WASTED] }
                version: { type: integer, format: int64 }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/provisions.yaml`

```yaml
CookEventCommand:
  type: object
  required: [recipeId, mealSlotId, servingsCooked, ingredientsUsed]
  properties:
    recipeId: { type: string, format: uuid }
    planId: { type: string, format: uuid, nullable: true }
    mealSlotId: { type: string, format: uuid }
    servingsCooked: { type: integer, minimum: 1 }
    isBatchCook: { type: boolean, nullable: true, default: false }
    proportionOfRecipe:
      type: number
      format: double
      minimum: 0
      maximum: 1
      nullable: true
      default: 1.0
    strict:
      type: boolean
      nullable: true
      default: false
      description: 'When true, an underflow throws 422 InventoryUnderflowException; when false (default) underflows are floored at zero and surfaced in result.underflows.'
    dedupeKey:
      type: string
      maxLength: 64
      nullable: true
      description: 'Idempotency token; server defaults to SHA-256 over (mealSlotId, recipeId, servingsCooked, isBatchCook) when null.'
    ingredientsUsed:
      type: array
      minItems: 1
      items: { $ref: '#/RecipeIngredientUsage' }
    traceId: { type: string, format: uuid, nullable: true }
RecipeIngredientUsage:
  type: object
  required: [ingredientMappingKey, quantity, unit]
  properties:
    ingredientMappingKey: { type: string, maxLength: 128 }
    quantity: { type: number, format: double, minimum: 0 }
    unit: { type: string, maxLength: 16 }
MealConsumptionCommand:
  type: object
  required: [inventoryItemId, portions]
  properties:
    inventoryItemId: { type: string, format: uuid }
    portions: { type: number, format: double, minimum: 0 }
    traceId: { type: string, format: uuid, nullable: true }
StandaloneConsumptionCommand:
  type: object
  required: [ingredientMappingKey, quantity, unit, userConfirmedDeduction]
  properties:
    ingredientMappingKey: { type: string, maxLength: 128 }
    quantity: { type: number, format: double, minimum: 0 }
    unit: { type: string, maxLength: 16 }
    userConfirmedDeduction: { type: boolean }
    traceId: { type: string, format: uuid, nullable: true }
InventoryDeductionResultDto:
  type: object
  required: [updatedItems, exhaustedItems, underflows]
  properties:
    updatedItems:
      type: array
      items: { $ref: '#/InventoryItemDto' }
    exhaustedItems:
      type: array
      items: { type: string, format: uuid }
    underflows:
      type: array
      items: { $ref: '#/UnderflowFlagDto' }
UnderflowFlagDto:
  type: object
  required: [ingredientMappingKey, requested, available]
  properties:
    ingredientMappingKey: { type: string, maxLength: 128 }
    requested: { type: number, format: double, minimum: 0 }
    available: { type: number, format: double, minimum: 0 }
```

**Gotcha applied — round-1/4/6 sticky trap**: every nullable field (`planId`, `isBatchCook`, `proportionOfRecipe`, `strict`, `dedupeKey`, `traceId`) uses INLINE `nullable: true` directly on the property — NOT `$ref + nullable: true`. The `StandaloneConsumptionCommand` response schema **inlines** the `InventoryItemDto` shape (with `nullable: true` on the top-level object) rather than `{ $ref: '#/InventoryItemDto', nullable: true }`. Even though `InventoryItemDto` is a named schema from 01a, the round-1/4/6 lesson is: **don't reuse via `$ref` when nullable**.

**Gotcha applied**: every description string containing `,` `:` `'` is single-quoted (`'When true, an underflow throws...'`, `'Idempotency token; server defaults to...'`).

### Append to entry `src/main/resources/openapi/openapi.yaml`

Under `paths:` in the `# provisions` block:

```yaml
  /api/v1/provisions/cook-event:              { $ref: 'paths/provisions.yaml#/provisionsCookEvent' }
  /api/v1/provisions/meal-consumption:        { $ref: 'paths/provisions.yaml#/provisionsMealConsumption' }
  /api/v1/provisions/standalone-consumption:  { $ref: 'paths/provisions.yaml#/provisionsStandaloneConsumption' }
```

Under `components.schemas:` in the `# provisions` block (alphabetical):

```yaml
    CookEventCommand:              { $ref: 'schemas/provisions.yaml#/CookEventCommand' }
    InventoryDeductionResultDto:   { $ref: 'schemas/provisions.yaml#/InventoryDeductionResultDto' }
    MealConsumptionCommand:        { $ref: 'schemas/provisions.yaml#/MealConsumptionCommand' }
    RecipeIngredientUsage:         { $ref: 'schemas/provisions.yaml#/RecipeIngredientUsage' }
    StandaloneConsumptionCommand:  { $ref: 'schemas/provisions.yaml#/StandaloneConsumptionCommand' }
    UnderflowFlagDto:              { $ref: 'schemas/provisions.yaml#/UnderflowFlagDto' }
```

## Verbatim shape snippets

### Sealed event base

```java
package com.example.mealprep.provisions.event;

public sealed interface ProvisionChangedEvent permits
    ItemAddedFromGroceryEvent,
    ItemSpoiledEvent,
    ItemRanOutEvent,
    SubstitutionAcceptedEvent,
    ItemQuantityAdjustedEvent,
    GenericProvisionChangedEvent {
  UUID userId();
  List<UUID> affectedItemIds();
  UUID traceId();
  Instant occurredAt();
}

public record ItemQuantityAdjustedEvent(UUID userId, List<UUID> affectedItemIds,
                                        ItemAdjustmentSource source, UUID traceId, Instant occurredAt)
    implements ProvisionChangedEvent {}

public enum ItemAdjustmentSource { COOK_EVENT, MEAL_CONSUMPTION, MANUAL, STANDALONE_LOG, WASTE }
```

### Cook event command record

```java
package com.example.mealprep.provisions.api.dto;

public record CookEventCommand(
    @NotNull UUID recipeId, @Nullable UUID planId, @NotNull UUID mealSlotId,
    @Min(1) int servingsCooked, @Nullable Boolean isBatchCook,
    @Nullable BigDecimal proportionOfRecipe,
    @Nullable Boolean strict,
    @Nullable @Size(max = 64) String dedupeKey,
    @NotNull @Size(min = 1) List<RecipeIngredientUsage> ingredientsUsed,
    @Nullable UUID traceId
) {}

public record RecipeIngredientUsage(@NotBlank @Size(max = 128) String ingredientMappingKey,
                                    @NotNull @PositiveOrZero BigDecimal quantity,
                                    @NotBlank @Size(max = 16) String unit) {}
```

### `ProvisionEventBatcher` skeleton

```java
@Component
class ProvisionEventBatcher {
  private final ApplicationEventPublisher publisher;
  private static final String SYNC_KEY = "ProvisionEventBatcher.state";

  void recordAdjustment(UUID userId, UUID itemId, ItemAdjustmentSource source, UUID traceId) {
    state().adjust.computeIfAbsent(new Key(userId, source),
        k -> new AdjustState(new ArrayList<>(), traceId)).itemIds.add(itemId);
  }

  void recordRanOut(UUID userId, UUID itemId, String key, boolean wasStaple, UUID traceId) {
    state().ranOut.computeIfAbsent(new RanOutKey(userId, key),
        k -> new RanOutState(new ArrayList<>(), wasStaple, traceId)).itemIds.add(itemId);
  }

  private State state() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return new State();   // degrade: caller publishes directly via flush()
    }
    State s = (State) TransactionSynchronizationManager.getResource(SYNC_KEY);
    if (s == null) {
      s = new State();
      TransactionSynchronizationManager.bindResource(SYNC_KEY, s);
      TransactionSynchronizationManager.registerSynchronization(new Sync(s));
    }
    return s;
  }

  private class Sync implements TransactionSynchronization {
    private final State s;
    Sync(State s) { this.s = s; }
    @Override
    public void afterCommit() {
      s.adjust.forEach((k, v) -> publisher.publishEvent(
          new ItemQuantityAdjustedEvent(k.userId, List.copyOf(v.itemIds), k.source, v.traceId, Instant.now())));
      s.ranOut.forEach((k, v) -> publisher.publishEvent(
          new ItemRanOutEvent(k.userId, List.copyOf(v.itemIds), k.key, v.wasStaple, v.traceId, Instant.now())));
    }
    @Override
    public void afterCompletion(int status) {
      TransactionSynchronizationManager.unbindResourceIfPossible(SYNC_KEY);
    }
  }
}
```

### `applyCookEvent` skeleton

```java
@Override
@Transactional
public InventoryDeductionResultDto applyCookEvent(UUID userId, CookEventCommand command) {
  String dedupeKey = command.dedupeKey() != null ? command.dedupeKey() : computeDedupeKey(command);
  if (cookEventDedupeRepository.existsByMealSlotIdAndDedupeKey(command.mealSlotId(), dedupeKey)) {
    log.info("duplicate cook event mealSlot={} dedupeKey={}; no-op", command.mealSlotId(), dedupeKey);
    return new InventoryDeductionResultDto(List.of(), List.of(), List.of());
  }
  cookEventDedupeRepository.save(new ProvisionCookEventDedupe(command.mealSlotId(), dedupeKey, Instant.now()));

  if (Boolean.TRUE.equals(command.isBatchCook())) {
    throw new BatchCookNotSupportedException();   // v1 — full split lands in 01j
  }
  BigDecimal proportion = command.proportionOfRecipe() != null ? command.proportionOfRecipe() : BigDecimal.ONE;
  boolean strict = Boolean.TRUE.equals(command.strict());
  UUID traceId = command.traceId() != null ? command.traceId() : UUID.randomUUID();

  Set<UUID> deductedIds = new LinkedHashSet<>();
  Set<UUID> exhaustedIds = new LinkedHashSet<>();
  List<UnderflowFlagDto> underflows = new ArrayList<>();
  for (RecipeIngredientUsage usage : command.ingredientsUsed()) {
    BigDecimal scaled = usage.quantity().multiply(proportion);
    DeductionOutcome o = engine.deduct(userId, usage.ingredientMappingKey(), scaled, usage.unit(), strict);
    deductedIds.addAll(o.deductedItemIds());
    exhaustedIds.addAll(o.exhaustedItemIds());
    underflows.addAll(o.underflows());
  }
  return new InventoryDeductionResultDto(
      inventoryItemMapper.toDtos(inventoryItemRepository.findAllById(deductedIds)),
      List.copyOf(exhaustedIds),
      List.copyOf(underflows));
}
```

## Edge-case checklist

### Sealed event hierarchy

- [ ] `ProvisionChangedEvent` is declared `sealed interface` with explicit `permits` clause
- [ ] Each variant `implements ProvisionChangedEvent` (non-sealed because records are implicitly final)
- [ ] `switch (event)` over the sealed base is exhaustive at compile time (assert in a small test)
- [ ] `InventoryItemUpsertedEvent` (01a) remains a separate non-sealed record (LLD-divergence note: chose Option 1)
- [ ] `ItemAdjustmentSource` enum has all 5 values (`COOK_EVENT, MEAL_CONSUMPTION, MANUAL, STANDALONE_LOG, WASTE`) per LLD line 568

### `InventoryDeductionEngine`

- [ ] FIFO-by-expiry: 3 rows for the same key with expiries (3 days, 7 days, no expiry), requested = 1 unit each → all 3 deduct from oldest first
- [ ] When a row hits zero quantity → `itemStatus = EXHAUSTED`
- [ ] Staple with `isStaple=true` hits zero → `status = OUT` + `ItemRanOutEvent` emitted
- [ ] Strict mode + insufficient stock → `InventoryUnderflowException` (422)
- [ ] Floor mode + insufficient stock → `UnderflowFlagDto` in result, no exception, partial deduction applied
- [ ] No matching rows → returns with `underflows` containing one entry (requested, available=0), no exception in non-strict
- [ ] Unit mismatch (no conversion) → WARN log + underflow flag for that row; engine moves to next row
- [ ] Multiple rows for same key, deduction satisfied by row 1 alone → row 2/3 untouched
- [ ] Audit row written for every decremented item (`actor = COOK_EVENT`, JSON before/after)
- [ ] Returns `(deductedItemIds, exhaustedItemIds, underflows)` correctly

### `applyCookEvent`

- [ ] Happy path: 200 + `InventoryDeductionResultDto` with updated items, exhausted IDs, underflows
- [ ] Duplicate `(mealSlotId, dedupeKey)` → no-op + INFO log; returns empty deduction result
- [ ] Server-computed dedupeKey is stable: same `(mealSlotId, recipeId, servingsCooked, isBatchCook)` produces same SHA-256 → same key → idempotency holds
- [ ] `proportionOfRecipe = 0.5` → all ingredient quantities scaled by 0.5
- [ ] `proportionOfRecipe = null` → defaults to 1.0
- [ ] `strict = true` + insufficient stock → 422
- [ ] `strict = false` (default) + insufficient stock → 200 + underflows
- [ ] `isBatchCook = true` → 422 `batch-cook-not-supported` (v1 stop-gap)
- [ ] Exactly ONE `ItemQuantityAdjustedEvent(source=COOK_EVENT)` published `AFTER_COMMIT` per cook event, carrying all touched item IDs
- [ ] Staple item hitting OUT → an additional `ItemRanOutEvent` is published in the same after-commit batch
- [ ] Anonymous request → 401
- [ ] Servings = 0 → 400 (Jakarta `@Min(1)`)
- [ ] Empty ingredients list → 400 (`@Size(min=1)`)

### `applyMealConsumption`

- [ ] Happy path: portions deducted; audit row; `ItemQuantityAdjustedEvent(source=MEAL_CONSUMPTION)`
- [ ] Item not found → 404
- [ ] Item owned by another user → 404 (don't leak)
- [ ] Portions > quantity → floor at zero; `itemStatus = EXHAUSTED`

### `applyStandaloneConsumption`

- [ ] No matching active rows → 200 + JSON `null` body (no exception)
- [ ] `userConfirmedDeduction = false` → 200 + matched row (unchanged) — preview semantics
- [ ] `userConfirmedDeduction = true` → oldest-expiry row decremented; audit `actor = NUTRITION_LOGGER`
- [ ] `ItemQuantityAdjustedEvent(source=STANDALONE_LOG)` published after commit
- [ ] Concurrent standalone + cook → standalone retries up to 3 (LLD line 690) — **DEFERRED** (no retry in 01g); concurrent test asserts `OptimisticLockException` flows through cleanly to 409

### Cook-event dedupe

- [ ] Migration applies cleanly; PK is `(meal_slot_id, dedupe_key)`
- [ ] Daily sweep deletes rows older than 24h (verified by `@TestPropertySource` forcing a near-now cutoff)
- [ ] Dedupe row is inserted **before** the deduction so a thrown exception still records the attempt (DB constraint ensures one row per key)

### `MealCookedEvent` listener

- [ ] `@ConditionalOnClass(name = "com.example.mealprep.planner.event.MealCookedEvent")` keeps the bean dormant when the planner is absent (today's state)
- [ ] Listener compiles even without the planner record on classpath (string-form `name = "..."`)
- [ ] Context-load IT: `applicationContext.getBeansOfType(CookEventListener.class).isEmpty()` — no bean registered today

### Cross-cutting

- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in the IT)
- [ ] **`StandaloneConsumptionCommand`'s response uses INLINE `nullable: true` on the InventoryItemDto** (NOT `$ref + nullable`) — round-1/4/6 sticky-trap avoidance verified by grep on `paths/provisions.yaml`
- [ ] **Every nullable property in `CookEventCommand` / `MealConsumptionCommand` / `StandaloneConsumptionCommand` uses INLINE `nullable: true`** — verified by grep
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 2 handler methods
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] **No @MockBean of `ProvisionServiceImpl`** in any new IT without checking interfaces — if any IT mocks one of provisions' interfaces, it MUST `@MockBean` every interface the impl provides (`ProvisionQueryService`, `ProvisionUpdateService`, `ProvisionForPlannerService`) — round-6 gotcha; quick check: `grep "implements" src/main/java/.../ProvisionServiceImpl.java` before adding `@MockBean`
- [ ] **Service writes audit/state then throws 4xx**: `applyCookEvent` in strict mode writes the dedupe row before throwing — `@Transactional(noRollbackFor = InventoryUnderflowException.class)` on the method so the dedupe row commits even when the exception surfaces. **Test by reading the dedupe table via JdbcTemplate after the 422 response**.
- [ ] `@TransactionalEventListener(phase = AFTER_COMMIT)` on the (dormant) listener method
- [ ] `ProvisionsBoundaryTest` (from 01a) still passes — verify the boundary rule permits `event` sub-package additions (sealed base + 5 records). If the rule whitelists known sub-packages, **append `event` if not already present** — this is the ONE shared file the agent may touch on the provisions side
- [ ] Migration applies cleanly; `FlywayMigrationIT` passes (boots full Postgres + validates schema)
- [ ] No regression on 01a/01b/01c/01d/01e/01f tests
- [ ] No `pom.xml` dependency adds
- [ ] No nutrition / recipe / household / auth / preference module file touched (provisions reads `RecipeQueryService.getById` via the existing public interface — but only inside the dormant `CookEventListener` which doesn't activate today)

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601700600__provision_create_cook_event_dedupe.sql

NEW   src/main/java/com/example/mealprep/provisions/event/ProvisionChangedEvent.java
NEW   src/main/java/com/example/mealprep/provisions/event/ItemAddedFromGroceryEvent.java
NEW   src/main/java/com/example/mealprep/provisions/event/ItemSpoiledEvent.java
NEW   src/main/java/com/example/mealprep/provisions/event/ItemRanOutEvent.java
NEW   src/main/java/com/example/mealprep/provisions/event/SubstitutionAcceptedEvent.java
NEW   src/main/java/com/example/mealprep/provisions/event/ItemQuantityAdjustedEvent.java
NEW   src/main/java/com/example/mealprep/provisions/event/GenericProvisionChangedEvent.java
NEW   src/main/java/com/example/mealprep/provisions/event/ItemAdjustmentSource.java

NEW   src/main/java/com/example/mealprep/provisions/api/controller/CookEventController.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/CookEventCommand.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/RecipeIngredientUsage.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/MealConsumptionCommand.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/StandaloneConsumptionCommand.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/InventoryDeductionResultDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/UnderflowFlagDto.java

NEW   src/main/java/com/example/mealprep/provisions/domain/entity/ProvisionCookEventDedupe.java
NEW   src/main/java/com/example/mealprep/provisions/domain/repository/CookEventDedupeRepository.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/internal/InventoryDeductionEngine.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionEventBatcher.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/internal/CookEventListener.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/internal/CookEventDedupeSweeper.java

NEW   src/main/java/com/example/mealprep/provisions/exception/InventoryUnderflowException.java
NEW   src/main/java/com/example/mealprep/provisions/exception/BatchCookNotSupportedException.java

MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionUpdateService.java       (append signatures applyCookEvent / applyMealConsumption / applyStandaloneConsumption per LLD line 423-425)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java (implement applyCookEvent / applyMealConsumption / applyStandaloneConsumption; constructor adds InventoryDeductionEngine + ProvisionEventBatcher + CookEventDedupeRepository)
MOD   src/main/java/com/example/mealprep/provisions/domain/repository/InventoryItemRepository.java   (add findActiveByMappingKeyOrderByExpiryAsc(UUID userId, String key) if not present; verify 01a's surface)
MOD   src/main/java/com/example/mealprep/provisions/api/ProvisionsExceptionHandler.java               (append 2 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/provisions/ProvisionsModule.java                             (optional — no facade re-export changes needed; ProvisionUpdateService already re-exported)
MOD   src/main/resources/application.yml                                                              (add `mealprep.provision.cook-dedupe.cron: 0 0 4 * * *` default)

MOD   src/main/resources/openapi/paths/provisions.yaml      (append 3 new path-items; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/provisions.yaml    (append 6 new schemas)
MOD   src/main/resources/openapi/openapi.yaml               (3 path entries + 6 schema refs under `# provisions` block)

NEW   src/test/java/com/example/mealprep/provisions/InventoryDeductionEngineTest.java       (FIFO-by-expiry; floor-at-zero; strict mode 422; unit mismatch warn; staple OUT transition + event)
NEW   src/test/java/com/example/mealprep/provisions/CookEventFlowIT.java                    (HTTP: happy path; idempotency; strict 422; batch-cook 422; anonymous 401; event publication via @RecordApplicationEvents; dedupe row committed even when strict throws)
NEW   src/test/java/com/example/mealprep/provisions/MealConsumptionFlowIT.java              (HTTP: happy; 404 missing; 404 not owned; floor-at-zero; ItemQuantityAdjustedEvent published)
NEW   src/test/java/com/example/mealprep/provisions/StandaloneConsumptionFlowIT.java        (HTTP: no match → 200 null; userConfirmedDeduction=false → 200 unchanged; userConfirmedDeduction=true → 200 mutated; STANDALONE_LOG event)
NEW   src/test/java/com/example/mealprep/provisions/ProvisionEventBatcherTest.java          (multiple deductions in one tx → one ItemQuantityAdjustedEvent; mixed kinds → one event per kind; no-tx fallback)
NEW   src/test/java/com/example/mealprep/provisions/CookEventListenerConditionalTest.java   (context-load: @ConditionalOnClass keeps the listener dormant today)
NEW   src/test/java/com/example/mealprep/provisions/CookEventDedupeSweeperIT.java           (sweep deletes rows >24h old)
MOD   src/test/java/com/example/mealprep/provisions/testdata/ProvisionsTestData.java        (append builders for all 7 new DTOs + ProvisionCookEventDedupe + ItemQuantityAdjustedEvent + ItemRanOutEvent)
MOD   src/test/java/com/example/mealprep/provisions/ProvisionsBoundaryTest.java             (verify `event` sub-package permitted; append if not — this is the ONE shared file the agent may touch on the provisions side)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-7 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exceptions in `ProvisionsExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module rule lives in `ProvisionsBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, migrations, entities — none touched.
- The recipe module (any file) — **explicitly not modified**. `CookEventListener` consumes `recipe.api.dto.RecipeQueryService` via the existing public interface ONLY inside the dormant `@ConditionalOnClass` listener.
- The nutrition, household, auth, preference modules — none touched.
- 01a/01b/01c/01d/01e/01f's existing tests — none modified; only `ProvisionsTestData.java` and `ProvisionsBoundaryTest.java` get appends.

## Dependencies

- **Hard dependency**: `provisions-01a` (merged) — `InventoryItem`, `InventoryItemRepository`, `InventoryAuditLog`, `AuditActor` enum, `ItemLifecycleStatus`, `StapleStatus`, `InventoryItemUpsertedEvent` (kept separate; see LLD divergence above), `ProvisionsException`, `ProvisionsExceptionHandler`, `ProvisionsBoundaryTest`, `ProvisionsModule`.
- **Hard dependency**: `provisions-01b`/`01c`/`01d`/`01e`/`01f` (merged) — pattern reuse only; supplier-product and budget aren't touched, but `ProvisionServiceImpl` has the constructor pattern that the 01g methods extend.
- **Hard dependency**: `recipe-01a` (merged) — `RecipeQueryService.getById` (only consumed by the dormant `CookEventListener`; not exercised in the IT today).
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Soft dependency on a future planner module** — provides `MealCookedEvent`. 01g's listener is `@ConditionalOnClass(name = "com.example.mealprep.planner.event.MealCookedEvent")` so it stays dormant until then. No coordination needed for the planner ticket.
- **Sibling tickets running in parallel** (Wave 2 round 7): `nutrition-01g`, `recipe-01g`. None should touch any provisions file or the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# provisions` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean (mandatory; not optional)
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 2 handler methods
- [ ] OpenAPI 3.0 nullable fields use **INLINE** `nullable: true` — verified by grep on `paths/provisions.yaml` for `$ref` and `nullable` on the same property block (zero hits expected)
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] `@Transactional(noRollbackFor = InventoryUnderflowException.class)` on `applyCookEvent` so the dedupe row commits even when strict mode throws — verified by JdbcTemplate read after the 422 response (round-5 audit/state-then-throw gotcha applied)
- [ ] `ProvisionChangedEvent` sealed base + 6 variants compile; exhaustive `switch` works
- [ ] `CookEventListener` is `@ConditionalOnClass(name = "...")` string-form (NOT class-literal) — round-5 bug-1 avoidance
- [ ] Migration applies; `FlywayMigrationIT` passes
- [ ] No regression on 01a-01f tests
- [ ] No `pom.xml` dependency adds
- [ ] No nutrition / recipe / household / auth / preference module file touched (recipe.api.dto import only inside dormant listener)

## What's NOT in scope

- `GroceryImportProcessor` + `applyGroceryOrder` → **provisions-01h**
- `StapleStateTransitioner` full state machine → **provisions-01i** (this ticket implements only the minimal `STOCKED → OUT` transition needed by the deduction engine)
- `BatchCookSplitter` → **provisions-01j** (batch cook stop-gap throws 422 in this ticket)
- `ExpiryRule` registry + `ItemNearingExpiryEvent` daily sweep → **provisions-01k**
- `applyFeedback(ProvisionsFeedbackCommand)` → **provisions-01l**
- Pantry-tracking-disabled gate → **provisions-01m**
- Resilience4j `@Retry` on `applyCookEvent` — agent reports if `OptimisticLockException` flows through cleanly; retry is a follow-up
- `UnitConverter` helper — unit mismatches log WARN + emit underflow flag; full converter in a follow-up
- The planner module's `MealCookedEvent` record + listener wire-up — planner module's concern
- Cross-user authorisation on the REST endpoints — caller's `userId` resolved server-side; no `userId` in path/body

## Sizing note — agent may report scope strain

This ticket is the biggest of round 7. If the implementation agent reports strain after 5 verify-loop iterations, **pre-approved split**:

- **Split A** — `provisions-01g-cook`: sealed event hierarchy + `InventoryDeductionEngine` + `applyCookEvent` + cook-event dedupe + idempotency table + `POST /cook-event` endpoint + listener SPI. ~30 files.
- **Split B** — `provisions-01g-consumption`: `applyMealConsumption` + `applyStandaloneConsumption` + their endpoints + `ProvisionEventBatcher`. ~15 files.

The parent should not pre-split unless the verify loop fails. The sealed-base lands in Split A; Split B re-uses the batcher/events without redefining them.

Squash-merge with: `feat(provisions): 01g — cook events + InventoryDeductionEngine + sealed ProvisionChangedEvent + dormant MealCookedEvent listener + dedupe`
