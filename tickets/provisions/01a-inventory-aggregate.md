# Ticket: provisions — 01a Inventory Aggregate

## Summary

Implement the `provisions` module's foundation aggregate: `InventoryItem` (single-table aggregate root, both `quantity`- and `status`-tracked items discriminated by `tracking_mode`), repository, `ProvisionQueryService` (read-by-others contract: `getActiveInventory(userId)`, `getInventoryItem(itemId)`), `ProvisionUpdateService.createInventoryItem` and `.updateInventoryItem`, the V…700000 migration plus the V…700100 audit migration, and the endpoint pair: `GET /api/v1/provisions/inventory` (paginated list of active items) + `GET /api/v1/provisions/inventory/{itemId}` + `POST /api/v1/provisions/inventory` + `PUT /api/v1/provisions/inventory/{itemId}`. Per [`lld/provisions.md`](../../lld/provisions.md) §V20260502120000, §V20260502120500, §Service Interfaces, §REST Controllers.

**Defers** (this LLD has 4 concerns × CRUD; only inventory's READ-BY-OTHERS path lands here):
- `Equipment` table + endpoints + `R__provision_seed_equipment_catalogue.sql` → **provisions-01b**
- `Budget` table + endpoints + spend-tracking derivation → **provisions-01c**
- `SupplierProduct` table + endpoints + substitution-history JSONB → **provisions-01d**
- `WasteEntry` (`provision_waste_log`) + endpoints + `WasteValidator` → **provisions-01e**
- `ProvisionForPlannerBundleDto` + `ProvisionForPlannerService.getBundle` → **provisions-01f** (depends on 01b/01c/01d)
- `InventoryDeductionEngine` (FIFO-by-expiry) + `applyCookEvent` / `applyMealConsumption` / `applyStandaloneConsumption` → **provisions-01g**
- `GroceryImportProcessor` + `applyGroceryOrder` → **provisions-01h**
- `StapleStateTransitioner` (stocked → low → out) + staple-replenishment list endpoint → **provisions-01i**
- `BatchCookSplitter` (fridge/freezer split) → **provisions-01j**
- `ExpiryRule` registry + `ItemNearingExpiryEvent` daily sweep → **provisions-01k**
- `applyFeedback(ProvisionsFeedbackCommand)` → **provisions-01l**
- Pantry-tracking-disabled gate (`pantry_tracking_enabled` from preference) → **provisions-01m**
- `mark-spoiled` / `markExhausted` lifecycle endpoints → **provisions-01b** (small; tucks in next to equipment)
- Retention sweep (`@Scheduled` daily 04:00 UTC) → **provisions-01k** (with the expiry sweep)
- `ProvisionChangedEvent` (sealed) + the rest of the events → **provisions-01g** (event hierarchy lands when a writer event-driven flow lands)

01a unblocks downstream callers needing "list user's pantry" + "create / update an item" — that's the read-by-others contract.

This is the first provisions ticket. Module package is currently empty.

**LLD divergence note**: the LLD's `ProvisionChangedEvent` is described as a sealed event base (line 52) — to keep 01a self-contained we add only one concrete event, `InventoryItemUpsertedEvent(UUID itemId, UUID userId, AuditActor actor, UUID traceId, Instant occurredAt)`, and defer the sealed-base introduction to 01g where the cook-event flow needs it. The 01g ticket can refactor the 01a event to extend the new sealed base; deferring avoids over-engineering an empty hierarchy now.

## Behavioural spec

### Aggregate shape — single-table for both tracking modes

1. `InventoryItem` is the aggregate root. Single table `provision_inventory` discriminated by `tracking_mode` ∈ `{quantity, status}` per [LLD V20260502120000 lines 82-110](../../lld/provisions.md). One row per pantry item.
2. **Common columns**: `id (UUID), userId, name (varchar 128), category (varchar 64), storageLocation (StorageLocation enum: FRIDGE|FREEZER|CUPBOARD|SPICE_RACK), trackingMode (TrackingMode: QUANTITY|STATUS), expiryDate (LocalDate nullable), ingredientMappingKey (varchar 128 nullable), notes (varchar 255 nullable), source (ItemSource: TESCO_ORDER|OTHER_SHOP|MANUAL_ADD|BATCH_COOK|GIFT), sourceRef (varchar 128 nullable), itemStatus (ItemLifecycleStatus: ACTIVE|EXHAUSTED|SPOILED|WASTED — default ACTIVE), version (@Version Long), createdAt, updatedAt`.
3. **Quantity-tracked-only** (nullable when status-tracked): `quantity (BigDecimal 10,3)`, `unit (varchar 16)`, `costPaid (BigDecimal 8,2)`. CHECK: `tracking_mode <> 'quantity' OR (quantity IS NOT NULL AND unit IS NOT NULL)`. CHECK: `quantity IS NULL OR quantity >= 0`.
4. **Status-tracked-only** (nullable when quantity-tracked): `status (StapleStatus: STOCKED|LOW|OUT)`, `isStaple (boolean default false)`. CHECK: `tracking_mode <> 'status' OR status IS NOT NULL`.
5. **Freezer-only** (nullable otherwise; mapped to a `FreezerExtensionDto` on the wire): `frozenAt (LocalDate)`, `maxFreezeWeeks (Integer)`, `defrostMethod (DefrostMethod: OVERNIGHT_FRIDGE|ROOM_TEMP|MICROWAVE|QUICK_DEFROST)`, `defrostLeadTimeHours (Integer)`, `sourceRecipeId (UUID — soft FK, no JPA association)`.
6. Indexes per LLD lines 113-123: `idx_prov_inventory_user_status (user_id, item_status)`; partial `idx_prov_inventory_user_expiry` on `(user_id, expiry_date) WHERE item_status='active' AND expiry_date IS NOT NULL`; partial `idx_prov_inventory_user_mapping_key WHERE item_status='active' AND ingredient_mapping_key IS NOT NULL`; partial `idx_prov_inventory_user_staples WHERE is_staple=true`; partial `idx_prov_inventory_source_ref WHERE source_ref IS NOT NULL` (for grocery-import idempotency in 01h). All indexes ship in 01a so future tickets layer flows on them.

### Audit log

7. `InventoryAuditLog` per [LLD V20260502120500 lines 226-238](../../lld/provisions.md). Fields: `id, inventoryItemId (soft FK; UUID), userId, actor (AuditActor: USER|COOK_EVENT|GROCERY_IMPORT|NUTRITION_LOGGER|SYSTEM), actorUserId (nullable), fieldChanged (varchar 64), previousValueJson (JsonNode JSONB), newValueJson (JsonNode JSONB), occurredAt (Instant)`. Append-only. Index `(inventory_item_id, occurred_at DESC)`. **Used in 01a** by `createInventoryItem` and `updateInventoryItem` — one row per changed field.

### Endpoints (01a-only subset)

8. `GET /api/v1/provisions/inventory?storageLocation=&isStaple=&page=&size=` — paginated `Page<InventoryItemDto>` for the calling user, **active items only** (`item_status = ACTIVE`). Query params are optional filters. Default size 20, max 100. Cookie-auth required.
9. `GET /api/v1/provisions/inventory/{itemId}` — single `InventoryItemDto` (200) or 404 `InventoryItemNotFoundException`. Authorisation: item's `userId` must match the caller's `userId` — otherwise 404 (not 403; don't leak existence).
10. `POST /api/v1/provisions/inventory` — accepts `CreateInventoryItemRequest`. Server resolves `userId` via `CurrentUserResolver`, never accepted from the request. Returns 201 + `InventoryItemDto` + `Location: /api/v1/provisions/inventory/{itemId}`. Validates the tracking-mode invariant (see below). Writes one `InventoryAuditLog` row with `actor = USER`, `fieldChanged = 'created'`, `previousValueJson = null`-equivalent JSON, `newValueJson = ` full snapshot.
11. `PUT /api/v1/provisions/inventory/{itemId}` — full replacement; carries `expectedVersion`. Mismatch → 409. Caller must own the item (else 404). Writes one `InventoryAuditLog` row per *changed* field with `actor = USER`. **Mark-as-spoiled, delete, mark-exhausted** are out of scope for 01a — 01b layers them on. **Quantity-adjust (`PATCH /quantity`) is out of scope** — 01b/01g.

### Validation

12. Standard Jakarta annotations on `CreateInventoryItemRequest` and `UpdateInventoryItemRequest`. **Custom validators in scope for 01a**:
    - `@ValidQuantity` (class-level on the quantity field): non-negative when present, scale ≤ 3, magnitude ≤ 1,000,000 (catches unit confusion). Null is valid (status-tracked items).
    - `@ValidStorageLocation` (class-level on the request): `SPICE_RACK` requires `STATUS` tracking, the others require `QUANTITY`. `freezerExtension` non-null iff `storageLocation = FREEZER`.
13. The DB CHECK constraints from line 108-110 are an additional safety net; the entity should also `@PrePersist` validate them so we get a Java exception (mapped to 422 `InvalidInventoryQuantityException`) before the row hits the DB.

### Cross-module facade + boundary

14. `ProvisionsModule.java` facade re-exports `ProvisionQueryService` and `ProvisionUpdateService` (interfaces only; partial — 01a methods only).
15. Repositories package-private. `ProvisionsBoundaryTest` at `src/test/java/com/example/mealprep/provisions/ProvisionsBoundaryTest.java`: classes outside `com.example.mealprep.provisions..` must not depend on `com.example.mealprep.provisions..domain.repository..`.

### Errors

16. New module-root `ProvisionsException extends RuntimeException`. Subclasses for 01a: `InventoryItemNotFoundException` (404), `InvalidInventoryQuantityException` (400). New `ProvisionsExceptionHandler` `@RestControllerAdvice` at `com.example.mealprep.provisions.api`, **annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`**. Maps both to ProblemDetails with `type` URIs `.../inventory-item-not-found` and `.../invalid-inventory-quantity`.
17. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler`. Validation failures via `MethodArgumentNotValidException` likewise.
18. **Defer to later tickets**: `EquipmentNotFoundException`, `BudgetNotFoundException`, `SupplierProductNotFoundException`, `InventoryUnderflowException`, `WasteExceedsInventoryException`, `DuplicateGroceryImportException`. Add the handlers when their flows land.

### Events

19. `InventoryItemUpsertedEvent(UUID itemId, UUID userId, AuditActor actor, UUID traceId, Instant occurredAt)` published `AFTER_COMMIT` after `createInventoryItem` and `updateInventoryItem`. No listeners in 01a. Plain record; the sealed `ProvisionChangedEvent` base lands with 01g (cook-event flow) — see LLD divergence note in the summary.

## Database

```
src/main/resources/db/migration/V20260601700000__provision_create_inventory.sql       new
src/main/resources/db/migration/V20260601700100__provision_create_inventory_audit.sql new
```

Schema mirrors LLD V20260502120000 lines 82-123 and V20260502120500 lines 226-238 — renumbered to `V20260601700xxx` to sequence after preference (V20260601300000+), household (V20260601500xxx), nutrition (V20260601600xxx).

**Do NOT create**: `provision_equipment`, `provision_budget`, `provision_supplier_products`, `provision_waste_log`. **Do NOT seed** the equipment catalogue (`R__provision_seed_equipment_catalogue.sql`). All deferred.

## OpenAPI updates

### New `src/main/resources/openapi/paths/provisions.yaml`

```yaml
inventory:
  get:
    tags: [Provisions]
    operationId: listInventory
    summary: List the calling user's active pantry items.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: storageLocation
        schema: { $ref: '../schemas/provisions.yaml#/StorageLocation' }
        required: false
      - in: query
        name: isStaple
        schema: { type: boolean }
        required: false
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: Page of items.
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/InventoryItemDtoPage' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  post:
    tags: [Provisions]
    operationId: createInventoryItem
    summary: Create a new pantry item.
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/CreateInventoryItemRequest' }
    responses:
      '201':
        description: Created.
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/InventoryItemDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
inventoryItem:
  get:
    tags: [Provisions]
    operationId: getInventoryItem
    summary: Get a single pantry item by id.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: itemId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200': { description: Item, content: { application/json: { schema: { $ref: '../schemas/provisions.yaml#/InventoryItemDto' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  put:
    tags: [Provisions]
    operationId: updateInventoryItem
    summary: Replace a pantry item (full replacement; expectedVersion required).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: itemId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/UpdateInventoryItemRequest' }
    responses:
      '200': { description: Updated, content: { application/json: { schema: { $ref: '../schemas/provisions.yaml#/InventoryItemDto' } } } }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### New `src/main/resources/openapi/schemas/provisions.yaml`

Schemas: `StorageLocation`, `TrackingMode`, `StapleStatus`, `ItemSource`, `ItemLifecycleStatus`, `DefrostMethod`, `FreezerExtensionDto`, `InventoryItemDto`, `InventoryItemDtoPage`, `CreateInventoryItemRequest`, `UpdateInventoryItemRequest`. Field shapes per LLD lines 269-321.

### Two-region edit to `src/main/resources/openapi/openapi.yaml`

Append under `paths:`:

```yaml
  /api/v1/provisions/inventory:
    $ref: 'paths/provisions.yaml#/inventory'
  /api/v1/provisions/inventory/{itemId}:
    $ref: 'paths/provisions.yaml#/inventoryItem'
```

Append under `components.schemas:` (one ref line per schema):

```yaml
    StorageLocation: { $ref: 'schemas/provisions.yaml#/StorageLocation' }
    TrackingMode: { $ref: 'schemas/provisions.yaml#/TrackingMode' }
    StapleStatus: { $ref: 'schemas/provisions.yaml#/StapleStatus' }
    ItemSource: { $ref: 'schemas/provisions.yaml#/ItemSource' }
    ItemLifecycleStatus: { $ref: 'schemas/provisions.yaml#/ItemLifecycleStatus' }
    DefrostMethod: { $ref: 'schemas/provisions.yaml#/DefrostMethod' }
    FreezerExtensionDto: { $ref: 'schemas/provisions.yaml#/FreezerExtensionDto' }
    InventoryItemDto: { $ref: 'schemas/provisions.yaml#/InventoryItemDto' }
    CreateInventoryItemRequest: { $ref: 'schemas/provisions.yaml#/CreateInventoryItemRequest' }
    UpdateInventoryItemRequest: { $ref: 'schemas/provisions.yaml#/UpdateInventoryItemRequest' }
```

## Verbatim shape snippets

### Entity — single-table aggregate with @PrePersist invariant check

```java
@Entity
@Table(name = "provision_inventory")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InventoryItem {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @Column(name = "category", nullable = false, length = 64)
  private String category;

  @Enumerated(EnumType.STRING)
  @Column(name = "storage_location", nullable = false, length = 16)
  private StorageLocation storageLocation;

  @Enumerated(EnumType.STRING)
  @Column(name = "tracking_mode", nullable = false, length = 16)
  private TrackingMode trackingMode;

  // Quantity-tracked
  @Column(name = "quantity", precision = 10, scale = 3)
  private BigDecimal quantity;
  @Column(name = "unit", length = 16)
  private String unit;
  @Column(name = "cost_paid", precision = 8, scale = 2)
  private BigDecimal costPaid;

  // Status-tracked
  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 16)
  private StapleStatus status;
  @Column(name = "is_staple", nullable = false)
  private boolean isStaple;

  @Column(name = "expiry_date")
  private LocalDate expiryDate;
  @Column(name = "ingredient_mapping_key", length = 128)
  private String ingredientMappingKey;
  @Column(name = "notes", length = 255)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 16)
  private ItemSource source;
  @Column(name = "source_ref", length = 128)
  private String sourceRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "item_status", nullable = false, length = 16)
  private ItemLifecycleStatus itemStatus;

  // Freezer extension
  @Column(name = "frozen_at") private LocalDate frozenAt;
  @Column(name = "max_freeze_weeks") private Integer maxFreezeWeeks;
  @Enumerated(EnumType.STRING) @Column(name = "defrost_method", length = 32)
  private DefrostMethod defrostMethod;
  @Column(name = "defrost_lead_time_hours") private Integer defrostLeadTimeHours;
  @Column(name = "source_recipe_id") private UUID sourceRecipeId;

  @Version @Column(name = "version", nullable = false) private long version;

  @CreationTimestamp @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
  @UpdateTimestamp @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist @PreUpdate
  private void validateTrackingModeInvariant() {
    if (trackingMode == TrackingMode.QUANTITY && (quantity == null || unit == null)) {
      throw new InvalidInventoryQuantityException(
          "tracking_mode=quantity requires both quantity and unit");
    }
    if (trackingMode == TrackingMode.STATUS && status == null) {
      throw new InvalidInventoryQuantityException(
          "tracking_mode=status requires status");
    }
    if (quantity != null && quantity.signum() < 0) {
      throw new InvalidInventoryQuantityException("quantity must be non-negative");
    }
  }
}
```

### Audit-log entity (JSONB pattern)

```java
@Entity @Table(name = "provision_inventory_audit")
public class InventoryAuditLog {
  @Id private UUID id;
  @Column(name = "inventory_item_id", nullable = false) private UUID inventoryItemId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Enumerated(EnumType.STRING) @Column(name = "actor", nullable = false, length = 32)
  private AuditActor actor;
  @Column(name = "actor_user_id") private UUID actorUserId;
  @Column(name = "field_changed", nullable = false, length = 64) private String fieldChanged;

  @Type(JsonBinaryType.class)
  @Column(name = "previous_value_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode previousValueJson;

  @Type(JsonBinaryType.class)
  @Column(name = "new_value_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode newValueJson;

  @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
}
```

### Repository — package-private

```java
interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {
  Page<InventoryItem> findAllByUserIdAndItemStatus(UUID userId, ItemLifecycleStatus status, Pageable p);
  // For storageLocation / isStaple filter combos use Specifications or @Query w/ optional predicates.
  Optional<InventoryItem> findByIdAndUserId(UUID id, UUID userId);
}

interface InventoryAuditLogRepository extends JpaRepository<InventoryAuditLog, UUID> {
  Page<InventoryAuditLog> findByInventoryItemIdOrderByOccurredAtDesc(UUID itemId, Pageable p);
}
```

### Service interfaces (01a subset only)

```java
public interface ProvisionQueryService {
  Optional<InventoryItemDto> getInventoryItem(UUID itemId, UUID requestingUserId);   // ownership-checked
  Page<InventoryItemDto> listActiveInventory(UUID userId, InventorySearchCriteria criteria, Pageable pageable);
}

public interface ProvisionUpdateService {
  InventoryItemDto createInventoryItem(UUID userId, CreateInventoryItemRequest request, AuditActor actor);
  InventoryItemDto updateInventoryItem(UUID itemId, UUID requestingUserId, UpdateInventoryItemRequest request);
}
```

The full LLD service surface (LLD lines 380-475) spans 25+ methods across query/update/planner-bundle — **all out of scope for 01a**.

## Edge-case checklist

- [ ] `POST /inventory` quantity-tracked with `quantity = -1` → 400 `invalid-inventory-quantity` (caught by `@PrePersist`)
- [ ] `POST /inventory` quantity-tracked without `unit` → 400 (Jakarta validation OR `@PrePersist`)
- [ ] `POST /inventory` `STORAGE_LOCATION = SPICE_RACK` with `tracking_mode = QUANTITY` → 400 (`@ValidStorageLocation` class-level)
- [ ] `POST /inventory` `STORAGE_LOCATION = FREEZER` without `freezerExtension` → 400 (`@ValidStorageLocation`)
- [ ] `POST /inventory` valid → 201, `Location` header set, audit row written with `actor = USER`, event published once after commit
- [ ] `GET /inventory/{itemId}` for an item owned by another user → 404 (NOT 403 — don't leak existence)
- [ ] `GET /inventory` paginated, only `ACTIVE` items returned, default size 20, `size > 100` clamped
- [ ] `PUT /inventory/{itemId}` with stale `expectedVersion` → 409
- [ ] `PUT /inventory/{itemId}` writes one audit row per *changed* field, none for no-op
- [ ] `PUT /inventory/{itemId}` for an item owned by another user → 404
- [ ] Anonymous request to any inventory endpoint → 401
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter)
- [ ] `ProvisionsBoundaryTest` passes — outside-module classes cannot import `provisions.domain.repository`
- [ ] `JdbcTemplate`-direct insert that violates a CHECK constraint surfaces a `DataIntegrityViolationException` (not silently accepted) — verifies migration CHECKs are real
- [ ] `userId` resolved server-side, never accepted from request body or query

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601700000__provision_create_inventory.sql
NEW   src/main/resources/db/migration/V20260601700100__provision_create_inventory_audit.sql

NEW   src/main/java/com/example/mealprep/provisions/ProvisionsModule.java
NEW   src/main/java/com/example/mealprep/provisions/api/controller/InventoryController.java
NEW   src/main/java/com/example/mealprep/provisions/api/ProvisionsExceptionHandler.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/InventoryItemDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/FreezerExtensionDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/CreateInventoryItemRequest.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/UpdateInventoryItemRequest.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/InventorySearchCriteria.java
NEW   src/main/java/com/example/mealprep/provisions/api/mapper/InventoryItemMapper.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/InventoryItem.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/InventoryAuditLog.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/StorageLocation.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/TrackingMode.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/StapleStatus.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/ItemSource.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/ItemLifecycleStatus.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/DefrostMethod.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/AuditActor.java
NEW   src/main/java/com/example/mealprep/provisions/domain/repository/InventoryItemRepository.java
NEW   src/main/java/com/example/mealprep/provisions/domain/repository/InventoryAuditLogRepository.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionQueryService.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionUpdateService.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java
NEW   src/main/java/com/example/mealprep/provisions/event/InventoryItemUpsertedEvent.java
NEW   src/main/java/com/example/mealprep/provisions/exception/ProvisionsException.java
NEW   src/main/java/com/example/mealprep/provisions/exception/InventoryItemNotFoundException.java
NEW   src/main/java/com/example/mealprep/provisions/exception/InvalidInventoryQuantityException.java
NEW   src/main/java/com/example/mealprep/provisions/validation/ValidQuantity.java
NEW   src/main/java/com/example/mealprep/provisions/validation/ValidQuantityValidator.java
NEW   src/main/java/com/example/mealprep/provisions/validation/ValidStorageLocation.java
NEW   src/main/java/com/example/mealprep/provisions/validation/ValidStorageLocationValidator.java

NEW   src/main/resources/openapi/paths/provisions.yaml
NEW   src/main/resources/openapi/schemas/provisions.yaml
MOD   src/main/resources/openapi/openapi.yaml                                                  (2 lines under paths:; ~10 lines under components.schemas:)

NEW   src/test/java/com/example/mealprep/provisions/ProvisionServiceImplTest.java
NEW   src/test/java/com/example/mealprep/provisions/InventoryFlowIT.java
NEW   src/test/java/com/example/mealprep/provisions/ProvisionsBoundaryTest.java
NEW   src/test/java/com/example/mealprep/provisions/InventoryConstraintIT.java
NEW   src/test/java/com/example/mealprep/provisions/testdata/ProvisionsTestData.java
```

**Files this ticket does NOT modify** (cross-cutting; sibling Wave-2 tickets must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`

## Dependencies

- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged).
- **Sibling tickets running in parallel** (Wave 2 round 1): `household-01a`, `nutrition-01a`, `recipe-01a`. None should touch any provisions file or any cross-cutting file.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `ProvisionsExceptionHandler` annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`
- [ ] No regression on existing tests
- [ ] DB CHECK constraints from the migration verified by an `InventoryConstraintIT` (raw `JdbcTemplate` insert that violates each CHECK and asserts `DataIntegrityViolationException`)

## What's NOT in scope

- `Equipment` table + endpoints + seed migration → **provisions-01b**
- `Budget` table + endpoints + spend-tracking derivation → **provisions-01c**
- `SupplierProduct` table + endpoints + substitution-history JSONB → **provisions-01d**
- `WasteEntry` (`provision_waste_log`) + endpoints + `WasteValidator` (`@ValidWasteQuantity`) → **provisions-01e**
- `ProvisionForPlannerBundleDto` + `ProvisionForPlannerService.getBundle` → **provisions-01f**
- `InventoryDeductionEngine`, `applyCookEvent`, `applyMealConsumption`, `applyStandaloneConsumption`, `MealCookedEvent` listener, sealed `ProvisionChangedEvent` base → **provisions-01g**
- `GroceryImportProcessor`, `applyGroceryOrder`, `GroceryOrderConfirmedEvent` listener, idempotency on `(user_id, source, source_ref)` → **provisions-01h**
- `StapleStateTransitioner`, `getStaplesNeedingReplenishment`, `getAvailableStaples` endpoints → **provisions-01i**
- `BatchCookSplitter` (fridge/freezer split) → **provisions-01j**
- `ExpiryRule` registry + `ItemNearingExpiryEvent` daily sweep + retention sweep `@Scheduled` job → **provisions-01k**
- `applyFeedback(ProvisionsFeedbackCommand)` → **provisions-01l**
- Pantry-tracking-disabled gate (reading `pantry_tracking_enabled` from preference's lifestyle config) → **provisions-01m**
- `markExhausted`, `markSpoiled`, soft-delete `DELETE /inventory/{itemId}`, `PATCH /quantity` adjust → **provisions-01b** (small admin endpoints; bundled with equipment)
- `getInventoryAuditLog` GET endpoint (audit-log paginated read) → **provisions-01b** (the table exists in 01a; the endpoint is one method away)
- Cross-controller `EquipmentController`, `BudgetController`, `WasteController`, `SupplierProductsController` → land with their own table tickets

Squash-merge with: `feat(provisions): 01a — inventory aggregate (single-table) + CRUD endpoints + boundary test`
