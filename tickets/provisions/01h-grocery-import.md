# Ticket: provisions — 01h `GroceryImportProcessor` + `applyGroceryOrder` + Dormant `GroceryOrderConfirmedEvent` Listener

## Summary

Layer the **inbound grocery-order ingestion flow** on top of 01a..01g. Per [LLD §Flow 2 lines 627-641](../../lld/provisions.md), [LLD §`GroceryImportProcessor` line 50](../../lld/provisions.md), [LLD §`applyGroceryOrder` / `GroceryOrderImportCommand` lines 428-465](../../lld/provisions.md), [LLD §Events §Consumed §`GroceryOrderConfirmedEvent` line 595](../../lld/provisions.md), [LLD §`ItemAddedFromGroceryEvent` line 561](../../lld/provisions.md), [LLD §inventory idempotency index line 123](../../lld/provisions.md), [LLD §Out of Scope §grocery module line 735](../../lld/provisions.md). Ships:

- **`GroceryImportProcessor`** — package-private `@Component` in `provisions/domain/service/internal/`. Orchestrates the per-line import: idempotency check, supplier-product upsert, expiry inference, expiry-aware merge-or-create on `InventoryItem`, substitution-history append.
- **`applyGroceryOrder(UUID userId, GroceryOrderImportCommand command)`** — public method on `ProvisionUpdateService` (already declared from 01a per LLD line 428). Top-level `@Transactional`. Per LLD line 631-640: idempotency on `(userId, source, sourceRef)`, per-line upsert with expiry-aware merge, substitution-history append, audit `actor = GROCERY_IMPORT`, **exactly one `ItemAddedFromGroceryEvent`** coalesced via `ProvisionEventBatcher` (already shipped in 01g).
- **Idempotency log table** `provision_grocery_import_log` with PK on `(user_id, source, source_ref)`. **LLD divergence below** — the LLD specifies idempotency via the existing index on `provision_inventory(user_id, source, source_ref)` (line 123) but 01h adds a dedicated log table for two reasons: (a) re-running an order with zero new inventory rows (everything substituted away) wouldn't leave any inventory rows, so the inventory-side index would NOT block a replay; (b) the log table carries `processed_at` for observability and a future `@Scheduled` retention sweep.
- **Dormant `GroceryOrderConfirmedEvent` listener** — same pattern as the round-7 `CookEventListener`. `@ConditionalOnClass(name = "com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent")` keeps the bean dormant until the grocery module ships (no grocery module exists today; LLD line 735 acknowledges the gap). The listener compiles even with the grocery module absent. **The `GroceryOrderConfirmedEvent` record itself is NOT shipped in 01h** — it's the grocery module's record (publisher owns the record).
- **REST endpoint `POST /api/v1/provisions/grocery-import`** — manual operator / integration-test side-door, same justification as round-7's REST surface for cook-event/consumption flows. Accepts a `GroceryOrderImportCommand` JSON body; the caller's `userId` is resolved server-side. **Worth user review** — alternative is in-process only; chosen for operator parity with 01g's three new endpoints and to give integration tests a uniform entry point.

## LLD divergence notes

### Dedicated idempotency log table

LLD line 123 declares the index `idx_prov_inventory_source_ref ON provision_inventory (user_id, source, source_ref) WHERE source_ref IS NOT NULL` and LLD line 632 says the import "Replays must be no-ops" via `existsByUserIdAndSourceAndSourceRef`. **01h ships a dedicated log table** `provision_grocery_import_log` because:

1. **Substitution-only or all-zero orders** — if all 5 ordered items are substituted away and result in zero inventory writes (edge case but possible), the inventory-side index has no rows for the order. A second confirmation of the same `(source, sourceRef)` would NOT be blocked.
2. **Observability** — the log table is the audit row for "we saw this order"; the inventory table is the audit row for "we wrote this stock".
3. **Retention** — a future `@Scheduled` sweep can prune log rows >12 months without touching inventory.

The `existsByUserIdAndSourceAndSourceRef` check from LLD line 632 now runs against the **log table** (clearer semantics). The inventory-side index from 01a stays — it serves the planner's "where did this row come from" lookup.

**Worth user review** — alternative is to extend the inventory-side index check. Rejected for the three reasons above. Net cost is one new table with 3 columns + 1 INSERT per order.

### `GroceryOrderImportCommand` — fields from LLD line 458-465 verbatim

LLD line 458-465 declares the command exactly:
```java
public record GroceryOrderImportCommand(
    String supplier, String orderRef, LocalDate deliveredOn,
    List<GroceryOrderLine> lines, List<GroceryOrderSubstitution> substitutions, UUID traceId
) {}
public record GroceryOrderLine(String productId, String name, String ingredientMappingKey,
    BigDecimal quantity, String unit, BigDecimal pricePaid, String category, Integer packSizeG) {}
public record GroceryOrderSubstitution(String orderedProductId, String substitutedProductId, String reason) {}
```

01h ships these verbatim. Source value for the inventory row is derived from `supplier`: when `supplier == "tesco"` (case-insensitive) → `ItemSource.TESCO_ORDER`; else → `ItemSource.OTHER_SHOP`. The `source_ref` on the inventory row + the log row is `command.orderRef()`.

### Expiry-aware merge vs new row

LLD line 635-638 specifies the **expiry-aware merge rule** (locked 2026-05-07):
- Merge into an existing row only when `(userId, ingredientMappingKey, storageLocation, expiryDate)` all match (or both `expiryDate` are null).
- Otherwise create a new row.

**01h ships verbatim**. Implementation: `inventoryItemRepository.findActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(userId, key, loc, expiry)` returns `Optional<InventoryItem>` — if present, `existing.quantity += scaledLineQuantity`; if absent, insert a new row. **`ingredientMappingKey == null` line** → ALWAYS new row (cannot merge by a null key).

### `ExpiryInferenceService` v1 data is deferred

LLD line 737 ("Out of Scope") confirms v1 rule data is deferred. **01h ships the `ExpiryRule` interface + `ExpiryInferenceService` registry**, but with **zero registered rules**. `inferExpiry(ingredientMappingKey, category, deliveredOn)` returns `Optional.empty()` for every input, which causes the inventory row to be persisted with `expiry_date = null`. **Worth user review** — alternative is to inline a small heuristic table (e.g. fresh chicken +3 days, dairy +7 days). Rejected: the LLD explicitly defers; a separate ticket can wire the rule data. The empty registry still costs nothing to ship and unblocks the rest of the flow.

### Pantry-tracking-disabled gate

LLD line 138-143 specifies behaviour when `pantry_tracking_enabled == false`: "Auto-add on `GroceryOrderConfirmedEvent` → No-op (the order itself still happens; provisions just doesn't track stock)". **01h ships the gate** — the listener body's first step is `if (!preferenceQueryService.isPantryTrackingEnabled(userId)) return;` — but only on the listener path. The REST `/grocery-import` endpoint is operator-initiated and bypasses the gate (operator wants the import to happen regardless of user preference). **Worth user review** — alternative is to apply the gate uniformly. Rejected because the REST surface is the operator-override path. The listener-only gate matches the LLD's "no-op the event" wording precisely.

LLD line 142 also flags: "The flag is read once per service-method invocation via `PreferenceQueryService` and held for the duration of the call." 01h follows this. **If `PreferenceQueryService.isPantryTrackingEnabled` does not exist** (verify 01a's preference surface), the listener body skips the gate with a TODO comment and 01h ships without the gate; **worth user review** — alternative is to add the preference method here, but that crosses module boundary and is out of provisions-01h scope.

### Partial-failure semantics

LLD line 640 says: "Partial failure. The grocery module owns 'of 5 ordered, 3 arrived'; by the time the command reaches us, those 3 are what the user has. The import is atomic on this side." **01h ships verbatim** — the `@Transactional` wraps the whole command; any per-line exception rolls back the entire import. No "best-effort per-line" mode. The agent should verify no per-line `try/catch` swallows errors.

### Strict ownership on the REST endpoint

The REST endpoint resolves `userId` from auth; the request body carries no `userId`. **An anonymous caller → 401**. There's NO mechanism to import an order for another user (operator-tooling would invoke the service directly via in-process DI, not via REST).

## Defers (still out of scope after 01h)

- `StapleStateTransitioner` (stocked → low → out full state machine) → **provisions-01i** (01g shipped only the minimal STOCKED→OUT transition needed by the deduction engine; 01h's grocery import that REPLENISHES a staple should transition OUT → STOCKED but defers that to 01i for code-clarity reasons — a TODO comment in the import processor flags the future addition).
- `BatchCookSplitter` (fridge/freezer split on cook-event with `isBatchCook=true`) → **provisions-01j**
- `ExpiryRule` registry v1 data + `ItemNearingExpiryEvent` daily sweep → **provisions-01k**
- `applyFeedback(ProvisionsFeedbackCommand)` → **provisions-01l**
- `recordSubstitution` (separate flow — LLD line 436) → **provisions-01m**; 01h embeds the substitution-history append inline within the import processor for the import-time path only
- The grocery module itself (`GroceryProvider`, Tesco automation, partial-failure handling, the `GroceryOrderConfirmedEvent` record) — separate module, no LLD yet (LLD line 735 acknowledges the gap)
- The grocery module's `getOrder(orderRef)` query that the dormant listener would call — not exercisable today; the listener body is a stub that calls a future grocery-side `GroceryOrderQueryService` interface. **01h does NOT ship the interface** (grocery owns it). The listener body throws `UnsupportedOperationException` when invoked today — but `@ConditionalOnClass` ensures it's never invoked.
- Resilience4j `@Retry` on `applyGroceryOrder` for concurrent same-row contention — same posture as 01g's cook-event flow; agent reports if `OptimisticLockException` flows through cleanly to 409. The expiry-aware merge path has the only `@Version` contention surface; the merge falls back to `findActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate` on retry. **Worth user review.**

## Behavioural spec

### `ExpiryInferenceService` (v1 — empty registry)

1. New package-private `@Component` `com.example.mealprep.provisions.domain.service.internal.ExpiryInferenceService`.
2. New public-package interface `ExpiryRule` in the same package:
   ```java
   interface ExpiryRule {
     Optional<LocalDate> infer(String ingredientMappingKey, String category, LocalDate deliveredOn);
   }
   ```
3. **Constructor**: `List<ExpiryRule>` — Spring injects zero rules at v1 (no `@Component`-annotated rule beans).
4. **`inferExpiry(ingredientMappingKey, category, deliveredOn)`** — iterates the rule list, returns the first non-empty `Optional<LocalDate>`. Empty rule list → always `Optional.empty()`.
5. **Worth user review**: alternative is `Optional<ExpiryRule>` first-match-wins. Identical at v1; the iterator pattern keeps future rule-order extensibility open.

### `GroceryImportProcessor`

6. New package-private `@Component` `com.example.mealprep.provisions.domain.service.internal.GroceryImportProcessor` (LLD line 50).
7. **Constructor**: `InventoryItemRepository`, `SupplierProductRepository`, `ProvisionGroceryImportLogRepository` (NEW), `ExpiryInferenceService`, `ProvisionEventBatcher` (from 01g), `InventoryAuditLogRepository`, `InventoryItemMapper`, `ObjectMapper`.
8. **API**:
   ```java
   GroceryImportResultDto process(UUID userId, GroceryOrderImportCommand command, AuditActor actor);
   ```
9. **Step 1 — idempotency check** (LLD line 632):
   - Compute `source = command.supplier().toLowerCase().equals("tesco") ? ItemSource.TESCO_ORDER : ItemSource.OTHER_SHOP`.
   - `groceryImportLogRepository.existsByUserIdAndSourceAndSourceRef(userId, source, command.orderRef())` → true → throw `DuplicateGroceryImportException` (NEW; 409). The exception's ProblemDetail surfaces `userId`, `source`, `sourceRef`.
   - Insert a new `ProvisionGroceryImportLog` row `(userId, source, command.orderRef(), command.traceId(), Instant.now())`.
10. **Step 2 — per-line processing** (LLD line 633-638):
    - For each `GroceryOrderLine line`:
      - **Upsert supplier product**: `supplierProductRepository.findBySupplierAndProductId(command.supplier(), line.productId())` → if present, refresh `price`, `pricePerUnit`, `unit`, `packSizeG`, `category`, `clubcardPrice` (null in v1 — LLD doesn't carry it in `GroceryOrderLine`; v1 leaves the existing value intact), `lastChecked = command.deliveredOn()`, `name`, `ingredientMappingKey`. **`ingredientMappingKey` from the line**: if non-null, set on the supplier-product row. If absent, leave existing or null. If missing entirely (new + null), the supplier product is still cached but won't appear in mapping-key queries — that's OK; the inventory write is what matters for the planner.
      - **Infer storage location** from `line.category()`: `"frozen" / "freezer"` → `FREEZER`; `"fridge" / "dairy" / "fresh"` → `FRIDGE`; else `CUPBOARD`. Default `CUPBOARD`. **Worth user review** — heuristic only; the LLD line 633 says "infer storage location from `category`" without specifying the table. Default to `CUPBOARD` is the safest miss.
      - **Infer expiry**: `expiryInferenceService.inferExpiry(line.ingredientMappingKey(), line.category(), command.deliveredOn())` → `Optional<LocalDate>`. Empty at v1 → `expiryDate = null`.
      - **Expiry-aware merge** (LLD line 635-638):
        - If `line.ingredientMappingKey() == null` → always create a new row.
        - Else `inventoryItemRepository.findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(userId, key, location, expiryDateOpt)` — NEW repo method. Returns `Optional<InventoryItem>` matching `(userId, ingredientMappingKey, storageLocation, expiryDate)`-tuple where both `expiryDate` are null OR both equal. `item_status = 'ACTIVE'`.
        - **Match**: `existing.quantity = existing.quantity.add(line.quantity())` (BigDecimal; scale 3, `RoundingMode.HALF_UP`). `existing.unit` stays. `existing.cost_paid = (existing.cost_paid ?? 0) + (line.pricePaid ?? 0)`. Bump `@Version`.
        - **No match**: insert a new `InventoryItem`. `tracking_mode = QUANTITY` (groceries are quantity-tracked); `storage_location = inferredLocation`; `quantity = line.quantity()`; `unit = line.unit()`; `cost_paid = line.pricePaid()`; `name = line.name()`; `category = line.category()`; `ingredient_mapping_key = line.ingredientMappingKey()`; `expiry_date = expiryDateOpt.orElse(null)`; `source = inferredItemSource`; `source_ref = command.orderRef()`; `item_status = ACTIVE`; `is_staple = false` (groceries don't auto-mark staple); `created_at = updated_at = now`.
      - **Audit**: append one `InventoryAuditLog` row with `actor = GROCERY_IMPORT`, `field_changed = "quantity"`, `previous_value_json = {"quantity": <prev>}` (or `{}` for create), `new_value_json = {"quantity": <new>}`.
      - **Event batch**: `eventBatcher.recordItemAddedFromGrocery(userId, itemId, command.supplier(), command.orderRef(), command.traceId())` — NEW method on the batcher; coalesces all affected item IDs into a single `ItemAddedFromGroceryEvent` at AFTER_COMMIT (single-event-per-operation contract from 01g line 113).
11. **Step 3 — substitutions** (LLD line 638-639):
    - For each `GroceryOrderSubstitution sub in command.substitutions()`:
      - Find the supplier product by `(supplier, sub.orderedProductId())` (the product the user ordered; may not be cached if it's a one-off).
      - **Found**: append a new `SubstitutionRecord(date=command.deliveredOn(), substitutedWithProductId=sub.substitutedProductId(), accepted=true, notes=sub.reason())` to the `substitution_history` JSONB (append-only).
      - **Not found**: log INFO `"supplier product not cached for orderedProductId={}; substitution history skipped"` and continue. **No exception** — substitutions are advisory.
      - The inventory row is created against the **substituted** product (already handled in Step 2 — the line's `productId` is the delivered product, not the ordered one).
12. **Step 4 — return** `GroceryImportResultDto(addedItems, mergedItems, updatedSupplierProducts, warnings)`:
    - `addedItems` — `List<InventoryItemDto>` of newly-created inventory rows.
    - `mergedItems` — `List<InventoryItemDto>` of rows whose quantity was incremented.
    - `updatedSupplierProducts` — `List<SupplierProductDto>` of supplier products refreshed.
    - `warnings` — `List<String>` of advisory messages (e.g. "supplier product orderedProductId=XXX not cached; substitution history skipped").

### `applyGroceryOrder` on the service

13. **Service signature** verbatim from LLD line 428: `GroceryImportResultDto applyGroceryOrder(UUID userId, GroceryOrderImportCommand command)`. The signature already lives on `ProvisionUpdateService` from 01a; 01h ships the impl.
14. **Implementation** on `ProvisionServiceImpl`:
    ```java
    @Override
    @Transactional
    public GroceryImportResultDto applyGroceryOrder(UUID userId, GroceryOrderImportCommand command) {
      return groceryImportProcessor.process(userId, command, AuditActor.GROCERY_IMPORT);
    }
    ```
15. **`@Transactional`** (REQUIRED — top-level per LLD line 684). The whole import is atomic.

### Dormant `GroceryOrderConfirmedEvent` listener

16. New `@Component` `GroceryOrderConfirmedListener` in `provisions/domain/service/internal/`:
    ```java
    @Component
    @ConditionalOnClass(name = "com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent")
    class GroceryOrderConfirmedListener {
      private final ProvisionUpdateService provisions;
      // private final GroceryOrderQueryService groceryQuery;   // also @ConditionalOnClass — grocery owns the interface

      @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
      @Transactional(propagation = Propagation.REQUIRES_NEW)
      public void onGroceryOrderConfirmed(GroceryOrderConfirmedEvent event) {
        // 1. fetch the order detail via grocery-side query service (today: throws UnsupportedOperationException)
        // 2. build GroceryOrderImportCommand from the payload
        // 3. call provisions.applyGroceryOrder(event.userId(), command)
      }
    }
    ```
17. **`@ConditionalOnClass(name = "...")` string-form** — NOT class-literal — so the class never tries to resolve the missing class at class-load time. Same parallel-safety mechanism from rounds 5-7.
18. **`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`** — the round-7 propagation rule applies because the listener body does JPA work (the `applyGroceryOrder` call opens its own tx, but ANY JPA touched before that — e.g. the gate check via `PreferenceQueryService` if wired — runs outside a tx without `REQUIRES_NEW`). Round-7 lesson encoded.
19. **Pantry-tracking gate** (LLD line 138):
    ```java
    // First step inside onGroceryOrderConfirmed (after entering REQUIRES_NEW tx):
    if (preferenceQueryService.isPantryTrackingEnabled(event.userId()) == false) {
      log.info("pantry tracking disabled for userId={}; no-op on grocery confirmation", event.userId());
      return;
    }
    ```
    **Verify** the method exists on `PreferenceQueryService`; if absent, the listener skips the gate with a TODO comment. **Worth user review.**
20. **The `GroceryOrderConfirmedEvent` record is NOT defined in provisions-01h** — it's the grocery module's record (publisher owns). `@ConditionalOnClass` keeps the listener dormant today.
21. **No test coverage for the listener body** — the grocery module doesn't exist. Coverage is on `applyGroceryOrder` directly + a context-load IT verifying the bean is NOT registered today.

### `ProvisionEventBatcher` extension

22. **Append a method** to the existing `ProvisionEventBatcher` from 01g:
    ```java
    void recordItemAddedFromGrocery(UUID userId, UUID itemId, String supplier, String orderRef, UUID traceId);
    ```
23. Internal state extension: a new `Map<AddedFromGroceryKey, AddedFromGroceryState>` keyed `(userId, supplier, orderRef)`. On `afterCommit`: one `ItemAddedFromGroceryEvent` per distinct key with all `affectedItemIds` collected. Same `TransactionSynchronizationManager` pattern from 01g.
24. **Single-event-per-operation contract** preserved (LLD line 572): one import touching 15 items → exactly one `ItemAddedFromGroceryEvent` carrying all 15 IDs.

### REST endpoint

25. **`POST /api/v1/provisions/grocery-import`** — new endpoint on a new controller `GroceryImportController` under `provisions/api/controller/`. Authenticated. Caller's `userId` resolved server-side.
26. **Request body**: `GroceryOrderImportCommand` (NEW DTO ship via `provisions/api/dto/`; the LLD command record verbatim becomes the request body too — no separate REST request record). Validation: `@NotBlank` supplier and orderRef; `@NotNull @PastOrPresent` deliveredOn; `@NotEmpty @Valid` lines; `@Valid` substitutions; nullable traceId.
27. **Response**: 200 + `GroceryImportResultDto`. 400 on validation. 409 on duplicate `(source, orderRef)`. 401 anonymous.
28. **Worth user review** — alternative is 201 (resource created). Chosen 200 because the request semantics is "apply an import command", not "create a Grocery Import resource"; the inventory rows it creates are downstream side effects.

### Errors

29. New exception `DuplicateGroceryImportException extends ProvisionsException` (409 — `type = .../duplicate-grocery-import`).
30. **Append one new `@ExceptionHandler` method** to existing `ProvisionsExceptionHandler` (already `@Order(Ordered.HIGHEST_PRECEDENCE)` from 01a..01g). Do NOT modify `config/GlobalExceptionHandler.java`. Do NOT create a second handler class.

### Cross-module facade

31. `ProvisionsModule` already re-exports `ProvisionUpdateService` (per 01a). `applyGroceryOrder` joins the same interface — no facade changes.

## Database

```
src/main/resources/db/migration/V20260601700700__provision_create_grocery_import_log.sql   new
```

Schema:

```sql
-- Provisions module — 01h grocery-import idempotency log.
-- See lld/provisions.md §Flow 2 / Idempotency line 632.
-- One row per accepted grocery order import. The PK enforces "no replays".
-- Retention sweep deferred to a follow-up (rows older than 12 months can be hard-deleted).

CREATE TABLE provision_grocery_import_log (
    user_id       uuid          NOT NULL,
    source        varchar(16)   NOT NULL,      -- tesco_order | other_shop (matches ItemSource enum)
    source_ref    varchar(128)  NOT NULL,      -- the orderRef
    trace_id      uuid,
    processed_at  timestamptz   NOT NULL,
    PRIMARY KEY (user_id, source, source_ref)
);

-- Retention sweep target (a follow-up @Scheduled deletes rows > 12 months).
CREATE INDEX idx_provision_grocery_import_log_processed_at
    ON provision_grocery_import_log (processed_at);
```

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/provisions.yaml`

(File extended by 01a..01g — append below 01g's most recent block. Do NOT touch existing path-items.)

```yaml
provisionsGroceryImport:
  post:
    tags: [Provisions]
    operationId: applyGroceryOrder
    summary: 'Apply a grocery-order import; upserts supplier products and creates/merges inventory rows by expiry.'
    description: 'Idempotent on (userId, source, orderRef); re-submission yields 409 duplicate-grocery-import.'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/GroceryOrderImportCommand' }
    responses:
      '200':
        description: 'Import result; addedItems, mergedItems, updatedSupplierProducts, and advisory warnings.'
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/GroceryImportResultDto' }
      '400':
        description: 'Validation error'
        content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } }
      '401':
        description: 'Unauthenticated'
        content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } }
      '409':
        description: 'Duplicate grocery import; the same (source, orderRef) was already applied for this user.'
        content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } }
```

### Append to `src/main/resources/openapi/schemas/provisions.yaml`

```yaml
GroceryOrderImportCommand:
  type: object
  required: [supplier, orderRef, deliveredOn, lines]
  properties:
    supplier:    { type: string, maxLength: 32 }
    orderRef:    { type: string, maxLength: 128 }
    deliveredOn: { type: string, format: date }
    lines:
      type: array
      minItems: 1
      items: { $ref: '#/GroceryOrderLine' }
    substitutions:
      type: array
      nullable: true
      items: { $ref: '#/GroceryOrderSubstitution' }
    traceId:
      type: string
      format: uuid
      nullable: true
GroceryOrderLine:
  type: object
  required: [productId, name, quantity, unit]
  properties:
    productId:            { type: string, maxLength: 128 }
    name:                 { type: string, maxLength: 255 }
    ingredientMappingKey:
      type: string
      maxLength: 128
      nullable: true
    quantity:             { type: number, format: double, minimum: 0 }
    unit:                 { type: string, maxLength: 16 }
    pricePaid:
      type: number
      format: double
      minimum: 0
      nullable: true
    category:
      type: string
      maxLength: 64
      nullable: true
    packSizeG:
      type: integer
      minimum: 1
      nullable: true
GroceryOrderSubstitution:
  type: object
  required: [orderedProductId, substitutedProductId]
  properties:
    orderedProductId:     { type: string, maxLength: 128 }
    substitutedProductId: { type: string, maxLength: 128 }
    reason:
      type: string
      maxLength: 255
      nullable: true
GroceryImportResultDto:
  type: object
  required: [addedItems, mergedItems, updatedSupplierProducts, warnings]
  properties:
    addedItems:
      type: array
      items: { $ref: '#/InventoryItemDto' }
    mergedItems:
      type: array
      items: { $ref: '#/InventoryItemDto' }
    updatedSupplierProducts:
      type: array
      items: { $ref: '#/SupplierProductDto' }
    warnings:
      type: array
      items: { type: string, maxLength: 255 }
```

**Gotcha applied — round-1/4/6 sticky trap**: every nullable property (`ingredientMappingKey`, `pricePaid`, `category`, `packSizeG`, `reason`, `substitutions`, `traceId`) uses INLINE `nullable: true` directly on the property — NOT `$ref + nullable: true`.

**Gotcha applied** — every description string containing `,` `:` `'` is single-quoted (`'Idempotent on (userId, source, orderRef); re-submission yields 409 duplicate-grocery-import.'` has comma + semicolon).

**Verify**: `InventoryItemDto` and `SupplierProductDto` are already registered from 01a and 01d. Their `$ref`s here are non-nullable so the sticky-trap doesn't apply.

### Append to entry `src/main/resources/openapi/openapi.yaml`

Under `paths:` in the `# provisions` block:

```yaml
  /api/v1/provisions/grocery-import: { $ref: 'paths/provisions.yaml#/provisionsGroceryImport' }
```

Under `components.schemas:` in the `# provisions` block (alphabetical):

```yaml
    GroceryImportResultDto:     { $ref: 'schemas/provisions.yaml#/GroceryImportResultDto' }
    GroceryOrderImportCommand:  { $ref: 'schemas/provisions.yaml#/GroceryOrderImportCommand' }
    GroceryOrderLine:           { $ref: 'schemas/provisions.yaml#/GroceryOrderLine' }
    GroceryOrderSubstitution:   { $ref: 'schemas/provisions.yaml#/GroceryOrderSubstitution' }
```

## Verbatim shape snippets

### Command records (LLD line 458-465 verbatim)

```java
package com.example.mealprep.provisions.api.dto;

public record GroceryOrderImportCommand(
    @NotBlank @Size(max = 32) String supplier,
    @NotBlank @Size(max = 128) String orderRef,
    @NotNull @PastOrPresent LocalDate deliveredOn,
    @NotEmpty @Valid List<GroceryOrderLine> lines,
    @Valid List<GroceryOrderSubstitution> substitutions,
    UUID traceId) {}

public record GroceryOrderLine(
    @NotBlank @Size(max = 128) String productId,
    @NotBlank @Size(max = 255) String name,
    @Size(max = 128) String ingredientMappingKey,
    @NotNull @PositiveOrZero BigDecimal quantity,
    @NotBlank @Size(max = 16) String unit,
    @PositiveOrZero BigDecimal pricePaid,
    @Size(max = 64) String category,
    @Positive Integer packSizeG) {}

public record GroceryOrderSubstitution(
    @NotBlank @Size(max = 128) String orderedProductId,
    @NotBlank @Size(max = 128) String substitutedProductId,
    @Size(max = 255) String reason) {}
```

### `GroceryImportProcessor` skeleton

```java
@Component
class GroceryImportProcessor {
  private static final Logger log = LoggerFactory.getLogger(GroceryImportProcessor.class);
  private final InventoryItemRepository inventoryItemRepository;
  private final SupplierProductRepository supplierProductRepository;
  private final ProvisionGroceryImportLogRepository importLogRepository;
  private final ExpiryInferenceService expiryInference;
  private final ProvisionEventBatcher eventBatcher;
  private final InventoryAuditLogRepository auditRepository;
  private final InventoryItemMapper inventoryMapper;
  private final SupplierProductMapper supplierMapper;
  private final ObjectMapper objectMapper;

  GroceryImportResultDto process(UUID userId, GroceryOrderImportCommand command, AuditActor actor) {
    ItemSource source = "tesco".equalsIgnoreCase(command.supplier())
        ? ItemSource.TESCO_ORDER : ItemSource.OTHER_SHOP;

    if (importLogRepository.existsByUserIdAndSourceAndSourceRef(userId, source, command.orderRef())) {
      throw new DuplicateGroceryImportException(userId, source, command.orderRef());
    }
    importLogRepository.save(new ProvisionGroceryImportLog(
        userId, source, command.orderRef(),
        command.traceId(), Instant.now()));

    UUID traceId = command.traceId() != null ? command.traceId() : UUID.randomUUID();
    List<InventoryItemDto> added = new ArrayList<>();
    List<InventoryItemDto> merged = new ArrayList<>();
    List<SupplierProductDto> supplierUpdates = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    for (GroceryOrderLine line : command.lines()) {
      supplierUpdates.add(upsertSupplierProduct(command.supplier(), command.deliveredOn(), line));
      StorageLocation location = inferLocation(line.category());
      LocalDate expiry = expiryInference.inferExpiry(line.ingredientMappingKey(), line.category(),
          command.deliveredOn()).orElse(null);
      Optional<InventoryItem> existing = line.ingredientMappingKey() == null
          ? Optional.empty()
          : inventoryItemRepository.findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(
              userId, line.ingredientMappingKey(), location, expiry);

      if (existing.isPresent()) {
        InventoryItem item = existing.get();
        BigDecimal prevQty = item.getQuantity();
        item.setQuantity(prevQty.add(line.quantity()));
        if (line.pricePaid() != null) {
          item.setCostPaid((item.getCostPaid() != null ? item.getCostPaid() : BigDecimal.ZERO).add(line.pricePaid()));
        }
        recordAudit(item.getId(), userId, actor, prevQty, item.getQuantity());
        eventBatcher.recordItemAddedFromGrocery(userId, item.getId(),
            command.supplier(), command.orderRef(), traceId);
        merged.add(inventoryMapper.toDto(item));
      } else {
        InventoryItem item = createNewInventoryRow(userId, line, location, expiry, source, command.orderRef());
        recordAudit(item.getId(), userId, actor, BigDecimal.ZERO, item.getQuantity());
        eventBatcher.recordItemAddedFromGrocery(userId, item.getId(),
            command.supplier(), command.orderRef(), traceId);
        added.add(inventoryMapper.toDto(item));
      }
    }

    if (command.substitutions() != null) {
      for (GroceryOrderSubstitution sub : command.substitutions()) {
        Optional<SupplierProduct> orderedOpt =
            supplierProductRepository.findBySupplierAndProductId(command.supplier(), sub.orderedProductId());
        if (orderedOpt.isPresent()) {
          appendSubstitutionRecord(orderedOpt.get(), sub, command.deliveredOn());
        } else {
          warnings.add("supplier product orderedProductId=" + sub.orderedProductId()
              + " not cached; substitution history skipped");
        }
      }
    }
    return new GroceryImportResultDto(added, merged, supplierUpdates, warnings);
  }
}
```

### Dormant listener skeleton

```java
@Component
@ConditionalOnClass(name = "com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent")
class GroceryOrderConfirmedListener {
  private static final Logger log = LoggerFactory.getLogger(GroceryOrderConfirmedListener.class);
  private final ProvisionUpdateService provisions;
  // private final PreferenceQueryService preferenceQuery;  // optional — see step 19

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onGroceryOrderConfirmed(GroceryOrderConfirmedEvent event) {
    // 1. (optional) pantry-tracking gate per LLD line 138-143
    // 2. fetch the order detail from the grocery module via its query service
    // 3. build GroceryOrderImportCommand from the payload
    // 4. provisions.applyGroceryOrder(event.userId(), command)
    throw new UnsupportedOperationException(
        "grocery module not yet on classpath; listener dormant via @ConditionalOnClass");
  }
}
```

## Edge-case checklist

### `GroceryImportProcessor`

- [ ] Single-line import with no existing inventory → adds 1 row; `addedItems.size == 1`, `mergedItems.size == 0`
- [ ] Single-line import with existing row matching `(userId, mappingKey, storageLocation, expiryDate)` → merges; `addedItems.size == 0`, `mergedItems.size == 1`, `quantity = prev + new`
- [ ] Single-line import with `ingredientMappingKey = null` → always creates a new row (never merges by null key)
- [ ] Two lines for the same mapping key in one import — same expiry → merges into ONE row (the second line merges with the first line's just-created row, OR both into a pre-existing row)
- [ ] Two lines for the same mapping key with DIFFERENT expiries → TWO rows
- [ ] Two lines for the same mapping key with same expiry but DIFFERENT storage locations → TWO rows
- [ ] Line with `null` `expiryDate` + existing row with `null` `expiryDate` (same key + location) → merges (both null match per LLD line 636)
- [ ] Line with `null` `expiryDate` + existing row with non-null expiry → NEW row (asymmetric null handling)
- [ ] Supplier product upserted: existing row gets price + lastChecked refreshed; new row gets full payload
- [ ] Supplier `"tesco"` (any case) → `source = TESCO_ORDER`; anything else → `source = OTHER_SHOP`
- [ ] Storage-location inference: category=`"frozen"` → FREEZER; `"fridge"`/`"dairy"`/`"fresh"` → FRIDGE; else CUPBOARD
- [ ] Expiry inference v1 (empty registry) → every row has `expiryDate = null`
- [ ] Audit row per inventory write: actor=GROCERY_IMPORT, field_changed="quantity", JSON before/after
- [ ] Single `ItemAddedFromGroceryEvent` per import, carrying all affected item IDs, published AFTER_COMMIT
- [ ] Substitution targeting cached supplier product → SubstitutionRecord appended to its JSONB history
- [ ] Substitution targeting un-cached supplier product → warning in `result.warnings`; no exception; import succeeds
- [ ] Pricing: `pricePaid` adds to `cost_paid` on merge; replaces on create
- [ ] Idempotency: re-submitting same `(userId, source, orderRef)` → 409 `duplicate-grocery-import`
- [ ] Idempotency log row inserted BEFORE inventory writes; rolls back on tx-level failure
- [ ] Empty `lines` list → 400 (`@NotEmpty`)
- [ ] Future `deliveredOn` → 400 (`@PastOrPresent`)

### REST endpoint

- [ ] `POST /grocery-import` happy path → 200 + GroceryImportResultDto
- [ ] `POST /grocery-import` anonymous → 401
- [ ] `POST /grocery-import` with duplicate `(source, orderRef)` → 409
- [ ] `POST /grocery-import` with empty lines → 400
- [ ] `POST /grocery-import` with empty supplier → 400
- [ ] OpenAPI request/response shapes match (swagger-request-validator in IT)

### Dormant listener

- [ ] `@ConditionalOnClass(name = "com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent")` keeps the bean dormant today
- [ ] String-form `name = "..."` (NOT class-literal) — round-5 bug-1 avoidance
- [ ] Listener method has `@Transactional(propagation = REQUIRES_NEW)` — round-7 lesson encoded
- [ ] Context-load IT: `applicationContext.getBeansOfType(GroceryOrderConfirmedListener.class).isEmpty()` — no bean registered today
- [ ] Listener compiles with the grocery module absent (string-form conditional)

### Cross-cutting

- [ ] OpenAPI inlines `nullable: true` (verified via grep — zero `$ref` + `nullable` sibling pairs)
- [ ] YAML description strings with `,` `:` `'` are single-quoted
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 1 handler method
- [ ] **No @MockBean of `ProvisionServiceImpl`** in any new IT without `grep "implements"` first — multi-interface gotcha; the impl provides `ProvisionQueryService`, `ProvisionUpdateService`, `ProvisionForPlannerService`
- [ ] `applyGroceryOrder` is `@Transactional` (REQUIRED, top-level) — atomic per LLD line 640
- [ ] `ProvisionEventBatcher` extension preserves single-event-per-operation contract
- [ ] Migration applies cleanly; `FlywayMigrationIT` passes (boots Postgres + validates schema)
- [ ] `ProvisionsBoundaryTest` (from 01a) still passes — no new sub-packages added (processor in existing `domain/service/internal/`, controller in `api/controller/`, dto in `api/dto/`, entity + repo in `domain/entity/` and `domain/repository/`, exception in `exception/`)
- [ ] No regression on 01a..01g tests
- [ ] No `pom.xml` dependency adds
- [ ] No nutrition / recipe / household / auth / preference / ai module file touched (the dormant listener may import a future grocery interface but doesn't activate today)

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601700700__provision_create_grocery_import_log.sql

NEW   src/main/java/com/example/mealprep/provisions/api/controller/GroceryImportController.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/GroceryOrderImportCommand.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/GroceryOrderLine.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/GroceryOrderSubstitution.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/GroceryImportResultDto.java

NEW   src/main/java/com/example/mealprep/provisions/domain/entity/ProvisionGroceryImportLog.java
NEW   src/main/java/com/example/mealprep/provisions/domain/repository/ProvisionGroceryImportLogRepository.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/internal/GroceryImportProcessor.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/internal/ExpiryInferenceService.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/internal/ExpiryRule.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/internal/GroceryOrderConfirmedListener.java

NEW   src/main/java/com/example/mealprep/provisions/exception/DuplicateGroceryImportException.java

MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java  (implement applyGroceryOrder via GroceryImportProcessor; constructor adds GroceryImportProcessor)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionEventBatcher.java (append recordItemAddedFromGrocery method + per-key batching state; preserve single-event-per-operation contract)
MOD   src/main/java/com/example/mealprep/provisions/domain/repository/InventoryItemRepository.java     (add findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate — verify; may need @Query because of null-vs-equals expiry handling)
MOD   src/main/java/com/example/mealprep/provisions/api/ProvisionsExceptionHandler.java                (append 1 @ExceptionHandler for DuplicateGroceryImportException; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/provisions/ProvisionsModule.java                              (optional — no facade re-export changes)

MOD   src/main/resources/openapi/paths/provisions.yaml      (append 1 new path-item; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/provisions.yaml    (append 4 new schemas)
MOD   src/main/resources/openapi/openapi.yaml               (1 path entry + 4 schema refs under `# provisions` block)

NEW   src/test/java/com/example/mealprep/provisions/GroceryImportProcessorTest.java                    (single-line add; single-line merge; null-mapping-key always-add; mixed lines; substitution append; substitution miss → warning; expiry-aware merge edge cases)
NEW   src/test/java/com/example/mealprep/provisions/GroceryImportFlowIT.java                           (HTTP: 200 happy; 409 duplicate; 400 validation; 401 anonymous; event publication via @RecordApplicationEvents; expiry-aware merge across two lines; substitution history JSONB grows)
NEW   src/test/java/com/example/mealprep/provisions/GroceryOrderConfirmedListenerConditionalTest.java  (context-load: @ConditionalOnClass keeps the listener dormant today)
NEW   src/test/java/com/example/mealprep/provisions/ExpiryInferenceServiceTest.java                    (empty registry → empty Optional; rule-list iteration first-non-empty-wins shape verified via stub rules)
MOD   src/test/java/com/example/mealprep/provisions/testdata/ProvisionsTestData.java                   (append builders for all 4 new DTOs + ProvisionGroceryImportLog)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-8 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exception in `ProvisionsExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module rule lives in `ProvisionsBoundaryTest` (unchanged).
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, migrations, entities — none touched.
- The recipe / nutrition / household / auth / preference / ai modules — **explicitly not modified**. The dormant `GroceryOrderConfirmedListener` may reference a future `com.example.mealprep.grocery.*` class via string-name `@ConditionalOnClass`; that's not a current module.
- 01a..01g existing tests — none modified; only `ProvisionsTestData.java` gets appends.

## Dependencies

- **Hard dependency**: `provisions-01a` (merged) — `InventoryItem`, `InventoryItemRepository`, `InventoryAuditLog`, `AuditActor` (with `GROCERY_IMPORT`), `ItemSource` (with `TESCO_ORDER`, `OTHER_SHOP`), `ItemLifecycleStatus`, `StorageLocation`, `TrackingMode`, `ProvisionsException`, `ProvisionsExceptionHandler`, `ProvisionsBoundaryTest`, `ProvisionsModule`, `InventoryItemMapper`.
- **Hard dependency**: `provisions-01d` (merged) — `SupplierProduct`, `SupplierProductRepository`, `SupplierProductMapper`, `SubstitutionRecord` JSONB shape.
- **Hard dependency**: `provisions-01g` (merged) — `ProvisionEventBatcher` (extended here), `ItemAddedFromGroceryEvent` record (shipped as part of the sealed hierarchy in 01g but not yet published), sealed `ProvisionChangedEvent`, `ApplicationEventPublisher` integration.
- **Hard dependency**: `provisions-01b`/`01c`/`01e`/`01f` (merged) — pattern reuse only.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Soft dependency on a future grocery module** — provides `GroceryOrderConfirmedEvent` and a query service for full-order fetching. 01h's listener is `@ConditionalOnClass(name = "com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent")` so it stays dormant until then.
- **Sibling tickets running in parallel** (Wave 2 round 8): `nutrition-01h`, `recipe-01h`. None should touch any provisions file or the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# provisions` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean (mandatory; not optional)
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 1 handler method
- [ ] OpenAPI nullable fields use **INLINE** `nullable: true` — verified by grep on `paths/provisions.yaml` and `schemas/provisions.yaml` for `$ref` and `nullable` on the same property block (zero hits expected)
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] `GroceryOrderConfirmedListener` uses **string-form** `@ConditionalOnClass(name = "...")` — round-5 bug-1 avoidance
- [ ] `GroceryOrderConfirmedListener` uses **`@Transactional(propagation = Propagation.REQUIRES_NEW)`** alongside `@TransactionalEventListener(AFTER_COMMIT)` — round-7 propagation rule encoded; verified by grep
- [ ] Migration applies cleanly; `FlywayMigrationIT` passes
- [ ] Single-event-per-operation contract preserved on `ItemAddedFromGroceryEvent` (one event per import even when 15 items added)
- [ ] No regression on 01a..01g tests
- [ ] No `pom.xml` dependency adds
- [ ] No nutrition / recipe / household / auth / preference / ai module file touched

## What's NOT in scope

- `StapleStateTransitioner` full state machine — staple `OUT → STOCKED` transition on replenishment is acknowledged in a TODO inside the processor but deferred to **provisions-01i**.
- `BatchCookSplitter` → **provisions-01j**
- `ExpiryRule` registry v1 data — empty registry shipped; rule data → **provisions-01k**
- `applyFeedback(ProvisionsFeedbackCommand)` → **provisions-01l**
- Pantry-tracking gate full wire-up (the listener checks but the REST endpoint doesn't) — **provisions-01m**
- `recordSubstitution` separate flow → **provisions-01m** (this ticket embeds the substitution-history append in the import processor for the import-time path only)
- Resilience4j `@Retry` on `applyGroceryOrder` for concurrent merge contention — follow-up if needed
- The grocery module itself + its `GroceryOrderConfirmedEvent` record + its `getOrder(orderRef)` query service — separate module
- Cross-user authorisation on the REST endpoint — caller's `userId` resolved server-side; no `userId` in path/body
- `@Scheduled` retention sweep on `provision_grocery_import_log` (rows >12 months) — follow-up
- A dedicated `recordSubstitutionFlow` REST endpoint — not in this ticket
- Bulk supplier-product price refresh via separate endpoint (`upsertSupplierProduct`) — already shipped per LLD line 435; not touched here

Squash-merge with: `feat(provisions): 01h — GroceryImportProcessor + applyGroceryOrder + idempotent grocery-import + dormant GroceryOrderConfirmedEvent listener`
