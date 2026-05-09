# Ticket: provisions — 01b Equipment + Inventory Admin Endpoints

## Summary

Layer two small concerns on the 01a inventory aggregate: (a) the **`Equipment`** root + endpoints + repeatable seed migration `R__provision_seed_equipment_catalogue.sql`; and (b) the four small **inventory admin endpoints** that were deferred from 01a — `markSpoiled`, `markExhausted`, soft-delete `DELETE /inventory/{itemId}`, and the audit-log read `GET /inventory/{itemId}/audit-log`. All four admin endpoints were called out for deferral in [`tickets/provisions/01a-inventory-aggregate.md`](01a-inventory-aggregate.md) §"What's NOT in scope". Per [`lld/provisions.md`](../../lld/provisions.md) §V20260502120100, §`R__provision_seed_equipment_catalogue.sql`, §Service Interfaces (`upsertEquipment`, `deleteEquipment`, `markSpoiled`, `markExhausted`, `getInventoryAuditLog`), §REST Controllers, §Events (`EquipmentChangedEvent`, `ItemSpoiledEvent`).

**Defers** (still out of scope after 01b):
- `Budget` table + endpoints + spend-tracking derivation → **provisions-01c**
- `SupplierProduct` + endpoints + substitution-history JSONB → **provisions-01d**
- `WasteEntry` + endpoints + `WasteValidator` → **provisions-01e**
- `ProvisionForPlannerBundleDto` + bundle service → **provisions-01f**
- `InventoryDeductionEngine` (FIFO-by-expiry) + cook-event flows → **provisions-01g**
- Grocery import + idempotency → **provisions-01h**
- `StapleStateTransitioner` + replenishment list endpoint → **provisions-01i**
- Batch cook splitter, expiry sweep, retention sweep, feedback dispatch, pantry-disabled gate → **provisions-01j..01m**
- `PATCH /inventory/{itemId}/quantity` (`adjustQuantity`) → **provisions-01g** (cook-event partner)

01b unblocks the planner's "what tools does this user own" read path — 01f's bundle pulls it in.

**LLD divergence note**: 01a's deferral list mentions `markExhausted`, `markSpoiled`, soft-delete, and `getInventoryAuditLog` as "small admin endpoints; bundled with equipment." This ticket honours that. **No new LLD divergences introduced**.

## Behavioural spec

### Equipment aggregate

1. `Equipment` is an aggregate root per [LLD V20260502120100 lines 147-156](../../lld/provisions.md). Fields: `id (UUID, application-set), userId (UUID NOT NULL), name (varchar 64 — free-text per LLD; e.g. 'oven', 'air_fryer'), available (boolean NOT NULL), details (varchar 255 nullable), version (@Version Long), createdAt (@CreatedDate), updatedAt (@LastModifiedDate)`. UNIQUE `(user_id, name)`.
2. `EquipmentRepository` (package-private):
   ```java
   interface EquipmentRepository extends JpaRepository<Equipment, UUID> {
     List<Equipment> findAllByUserId(UUID userId);
     List<Equipment> findAllByUserIdAndAvailableTrue(UUID userId);
     Optional<Equipment> findByUserIdAndName(UUID userId, String name);
   }
   ```
3. `EquipmentDto`: `(UUID id, UUID userId, String name, boolean available, String details, long version)`.

### Equipment endpoints

4. `GET /api/v1/provisions/equipment` returns `List<EquipmentDto>` for the calling user. Cookie-auth required. Sorted by `name` ascending.
5. `PUT /api/v1/provisions/equipment/{name}` accepts `UpsertEquipmentRequest { @NotBlank @Size(max=64) @Pattern(regexp="^[a-z0-9_]+$") String displayNameOverride /* nullable — name comes from path */, @NotNull boolean available, @Size(max=255) String details, Long expectedVersion /* nullable for create, required for update */ }`. Server resolves `userId`; `name` from path. Upsert behaviour:
   - If no row exists for `(userId, name)` → INSERT (`version = 0` after first save). `expectedVersion` ignored on insert.
   - If row exists → UPDATE; `expectedVersion` MUST match `version`, else 409 (mapped by `GlobalExceptionHandler`).
6. `DELETE /api/v1/provisions/equipment/{name}` returns 204; deletes the `(userId, name)` row. 404 if not present.
7. **Authorisation**: equipment is per-user (per LLD). All three endpoints reject anonymous (401) and silently scope by `userId` from `CurrentUserResolver` — no path or query parameter accepts `userId`.
8. Validation: `name` path segment must match `^[a-z0-9_]+$` (lower-case, digits, underscore — matches the seed catalogue values). 400 on mismatch.
9. **Equipment events**: publish `EquipmentChangedEvent(UUID userId, String equipmentName, boolean nowAvailable, UUID traceId, Instant occurredAt)` `AFTER_COMMIT` after both `upsertEquipment` and `deleteEquipment` (delete sets `nowAvailable = false`). No listeners in 01b.

### Inventory admin endpoints (deferred from 01a)

10. `POST /api/v1/provisions/inventory/{itemId}/mark-spoiled` (no body) — sets `itemStatus = SPOILED`. 200 with the updated `InventoryItemDto`. Authorisation: caller must own the item, else 404 (don't leak existence — same rule as 01a's `getInventoryItem`). Stale `expectedVersion`: this endpoint does NOT take one — it's a status-change tagger. Concurrent mark-spoiled + mark-exhausted on the same row resolve via `@Version` conflict on the underlying save (Hibernate increments). Idempotent: marking an already-`SPOILED` row → 200, no audit row, no event.
11. `POST /api/v1/provisions/inventory/{itemId}/mark-exhausted` — same shape; sets `itemStatus = EXHAUSTED`. Idempotent.
12. `DELETE /api/v1/provisions/inventory/{itemId}` (soft-delete) — per LLD `item_status` is the soft-delete-with-reason. **01b's DELETE sets `itemStatus = WASTED`** (the LLD's "wasted" status; `WasteEntry` table itself is 01e — soft-deleting the inventory row uses the existing enum value). Returns 204. 404 if not owned. Idempotent on already-`WASTED` rows.
13. **Audit semantics for the three lifecycle endpoints**: each writes one `InventoryAuditLog` row (table created in 01a) with `actor = USER`, `actorUserId = caller`, `fieldChanged = "itemStatus"`, `previousValueJson = {"itemStatus": "<prev>"}`, `newValueJson = {"itemStatus": "<new>"}`. **Skip** the audit row when the status didn't change (idempotency case).
14. **Lifecycle events**: publish `ItemSpoiledEvent(UUID userId, List<UUID> affectedItemIds, String reason, UUID traceId, Instant occurredAt)` `AFTER_COMMIT` for `mark-spoiled` (with `reason = "user_marked"`) and `ItemRanOutEvent(UUID userId, List<UUID> affectedItemIds, String ingredientMappingKey, boolean wasStaple, UUID traceId, Instant occurredAt)` for `mark-exhausted`. Soft-delete (`DELETE`) publishes neither — the row is going away, listeners shouldn't react. **LLD divergence note**: LLD §Events declares both as variants of a sealed `ProvisionChangedEvent` interface; 01a deferred the sealed base to 01g. 01b therefore declares both events as **plain records** (not sealed-interface variants); 01g will refactor them to extend the sealed base. Document in the event class Javadoc.

### `getInventoryAuditLog` endpoint

15. `GET /api/v1/provisions/inventory/{itemId}/audit-log?page=&size=` returns paginated `Page<InventoryAuditEntryDto>` newest-first, default size 20, max 100. Authorisation: caller must own the item, else 404. Reads from `provision_inventory_audit` (created in 01a).
16. `InventoryAuditEntryDto`: `(UUID id, UUID inventoryItemId, AuditActor actor, UUID actorUserId, String fieldChanged, JsonNode previousValue, JsonNode newValue, Instant occurredAt)`. The 01a audit-log entity ships ready; this ticket adds only the DTO + repository pagination method + controller method.

### Cross-module facade + boundary

17. Append to existing `ProvisionQueryService`:
    ```java
    List<EquipmentDto> getEquipment(UUID userId);
    List<EquipmentDto> getAvailableEquipment(UUID userId);
    Page<InventoryAuditEntryDto> getInventoryAuditLog(UUID itemId, UUID requestingUserId, Pageable pageable);
    ```
18. Append to existing `ProvisionUpdateService`:
    ```java
    EquipmentDto upsertEquipment(UUID userId, String name, UpsertEquipmentRequest request);
    void deleteEquipment(UUID userId, String name);
    InventoryItemDto markSpoiled(UUID itemId, UUID actorUserId);
    InventoryItemDto markExhausted(UUID itemId, UUID actorUserId);
    void softDeleteInventoryItem(UUID itemId, UUID actorUserId);
    ```
19. The new `EquipmentRepository` is **package-private**; cross-module callers go through the service interface. Existing `ProvisionsBoundaryTest` from 01a covers the new repo (lives in `domain/repository/`). **No changes to the test**.

### Errors

20. New module exception subclass `EquipmentNotFoundException` (404, `type = .../equipment-not-found`) extending the existing `ProvisionsException` from 01a.
21. **Append** one new `@ExceptionHandler` method to the existing `ProvisionsExceptionHandler` `@RestControllerAdvice` from 01a (which is already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler. Do **NOT** modify `config/GlobalExceptionHandler.java`. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler`.

## Database

```
src/main/resources/db/migration/V20260601700200__provision_create_equipment.sql           new
src/main/resources/db/migration/R__provision_seed_equipment_catalogue.sql                 new
```

The 01a inventory + inventory-audit migrations stay at `V20260601700000` / `V20260601700100`. Equipment lands at `V20260601700200`. The repeatable migration runs on every schema-change because Flyway re-applies repeatable migrations whenever their checksum changes (the LLD's seed-catalogue rationale).

```sql
-- V20260601700200
CREATE TABLE provision_equipment (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL,
    name          varchar(64) NOT NULL,
    available     boolean NOT NULL,
    details       varchar(255),
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    UNIQUE (user_id, name)
);
-- The (user_id, name) unique covers the only access path. Skipping a separate index.
```

```sql
-- R__provision_seed_equipment_catalogue.sql
-- Repeatable migration: seeds the canonical equipment catalogue per LLD line 244.
-- Idempotent — INSERTs are gated by NOT EXISTS so re-runs are no-ops.
-- This seed is REFERENCE DATA: it pre-populates a per-(canonical-)name lookup
-- exposed via a separate read-only admin endpoint OR consumed by the onboarding
-- wizard. The user_id column on provision_equipment is a foreign-data field for
-- per-user customisation; the seed catalogue itself lives in a SEPARATE table:

CREATE TABLE IF NOT EXISTS provision_equipment_catalogue (
    name          varchar(64) PRIMARY KEY,
    display_name  varchar(64) NOT NULL,
    sort_order    integer NOT NULL
);

INSERT INTO provision_equipment_catalogue (name, display_name, sort_order) VALUES
  ('oven',            'Oven',            10),
  ('hob',             'Hob',             20),
  ('microwave',       'Microwave',       30),
  ('air_fryer',       'Air fryer',       40),
  ('slow_cooker',     'Slow cooker',     50),
  ('blender',         'Blender',         60),
  ('food_processor',  'Food processor',  70),
  ('grill',           'Grill',           80),
  ('bbq',             'BBQ',             90),
  ('rice_cooker',     'Rice cooker',     100),
  ('stand_mixer',     'Stand mixer',     110),
  ('pressure_cooker', 'Pressure cooker', 120),
  ('kettle',          'Kettle',          130),
  ('toaster',         'Toaster',         140),
  ('dishwasher',      'Dishwasher',      150)
ON CONFLICT (name) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  sort_order   = EXCLUDED.sort_order;
```

The catalogue table is a separate read-side reference. `Equipment` (per-user state) FK-references it logically (by `name` matching `provision_equipment_catalogue.name`) but **no DB-level FK** — users may add equipment names not in the canonical catalogue (LLD: "free-text name (not enum) so users can add unusual items without a migration"). The `^[a-z0-9_]+$` validator on the controller is the only gatekeeper.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/provisions.yaml`

(File created by 01a — append five new path-items. Do NOT touch 01a's `inventory` / `inventoryItem`.)

```yaml
equipment:
  get:
    tags: [Provisions]
    operationId: listEquipment
    summary: List the calling user's equipment.
    security: [{ cookieAuth: [] }]
    responses:
      '200':
        description: Equipment list.
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/provisions.yaml#/EquipmentDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
equipmentByName:
  put:
    tags: [Provisions]
    operationId: upsertEquipment
    summary: Create or update an equipment row by canonical name.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: name
        required: true
        schema: { type: string, minLength: 1, maxLength: 64, pattern: '^[a-z0-9_]+$' }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/UpsertEquipmentRequest' }
    responses:
      '200': { description: Updated, content: { application/json: { schema: { $ref: '../schemas/provisions.yaml#/EquipmentDto' } } } }
      '201': { description: Created, content: { application/json: { schema: { $ref: '../schemas/provisions.yaml#/EquipmentDto' } } } }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  delete:
    tags: [Provisions]
    operationId: deleteEquipment
    summary: Delete an equipment row.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: name
        required: true
        schema: { type: string, minLength: 1, maxLength: 64, pattern: '^[a-z0-9_]+$' }
    responses:
      '204': { description: Deleted }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
inventoryItemMarkSpoiled:
  post:
    tags: [Provisions]
    operationId: markInventoryItemSpoiled
    summary: Mark an inventory item as spoiled (idempotent).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: itemId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200': { description: Marked, content: { application/json: { schema: { $ref: '../schemas/provisions.yaml#/InventoryItemDto' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not found or not owned, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
inventoryItemMarkExhausted:
  post:
    tags: [Provisions]
    operationId: markInventoryItemExhausted
    summary: Mark an inventory item as exhausted (idempotent).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: itemId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200': { description: Marked, content: { application/json: { schema: { $ref: '../schemas/provisions.yaml#/InventoryItemDto' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not found or not owned, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
inventoryItemAuditLog:
  get:
    tags: [Provisions]
    operationId: getInventoryItemAuditLog
    summary: Paginated audit log for an inventory item.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: itemId
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200': { description: Audit page, content: { application/json: { schema: { $ref: '../schemas/provisions.yaml#/InventoryAuditEntryDtoPage' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not found or not owned, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

The DELETE on `/inventory/{itemId}` re-uses the same path key as 01a's GET/PUT, so it's added under that existing path-item rather than a new one:

```yaml
# Inside the existing inventoryItem path-item from 01a — APPEND a delete: section:
inventoryItem:
  # get + put already present from 01a
  delete:
    tags: [Provisions]
    operationId: softDeleteInventoryItem
    summary: Soft-delete an inventory item (sets itemStatus=wasted).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: itemId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '204': { description: Deleted }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not found or not owned, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/provisions.yaml`

```yaml
EquipmentDto:
  type: object
  required: [id, userId, name, available, version]
  properties:
    id: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    name: { type: string, minLength: 1, maxLength: 64, pattern: '^[a-z0-9_]+$' }
    available: { type: boolean }
    details: { type: string, maxLength: 255, nullable: true }
    version: { type: integer, format: int64 }
UpsertEquipmentRequest:
  type: object
  required: [available]
  properties:
    available: { type: boolean }
    details: { type: string, maxLength: 255, nullable: true }
    expectedVersion:
      type: integer
      format: int64
      minimum: 0
      nullable: true
      description: Required for update; ignored for insert.
AuditActor:
  type: string
  enum: [user, cook_event, grocery_import, nutrition_logger, system]
InventoryAuditEntryDto:
  type: object
  required: [id, inventoryItemId, actor, fieldChanged, previousValue, newValue, occurredAt]
  properties:
    id: { type: string, format: uuid }
    inventoryItemId: { type: string, format: uuid }
    actor: { $ref: '#/AuditActor' }
    actorUserId: { type: string, format: uuid, nullable: true }
    fieldChanged: { type: string, maxLength: 64 }
    previousValue: {}
    newValue: {}
    occurredAt: { type: string, format: date-time }
InventoryAuditEntryDtoPage:
  type: object
  additionalProperties: true
  required: [content, page]
  properties:
    content:
      type: array
      items: { $ref: '#/InventoryAuditEntryDto' }
    page:
      type: object
      additionalProperties: true
      properties:
        number: { type: integer, minimum: 0 }
        size: { type: integer, minimum: 1 }
        totalElements: { type: integer, format: int64 }
        totalPages: { type: integer }
```

**Gotcha applied**: `InventoryAuditEntryDtoPage.additionalProperties: true` so Spring's `Page<T>` `pageable` and `sort` properties pass validation.

### Append to entry `src/main/resources/openapi/openapi.yaml`

Under `paths:`:

```yaml
  /api/v1/provisions/equipment:
    $ref: 'paths/provisions.yaml#/equipment'
  /api/v1/provisions/equipment/{name}:
    $ref: 'paths/provisions.yaml#/equipmentByName'
  /api/v1/provisions/inventory/{itemId}/mark-spoiled:
    $ref: 'paths/provisions.yaml#/inventoryItemMarkSpoiled'
  /api/v1/provisions/inventory/{itemId}/mark-exhausted:
    $ref: 'paths/provisions.yaml#/inventoryItemMarkExhausted'
  /api/v1/provisions/inventory/{itemId}/audit-log:
    $ref: 'paths/provisions.yaml#/inventoryItemAuditLog'
```

(The DELETE was added inline under the existing 01a `/api/v1/provisions/inventory/{itemId}` path-item in `paths/provisions.yaml` — entry openapi.yaml's `$ref` to that path-item is unchanged.)

Under `components.schemas:` (one ref per new schema):

```yaml
    EquipmentDto: { $ref: 'schemas/provisions.yaml#/EquipmentDto' }
    UpsertEquipmentRequest: { $ref: 'schemas/provisions.yaml#/UpsertEquipmentRequest' }
    AuditActor: { $ref: 'schemas/provisions.yaml#/AuditActor' }
    InventoryAuditEntryDto: { $ref: 'schemas/provisions.yaml#/InventoryAuditEntryDto' }
```

## Verbatim shape snippets

### Equipment entity — mirrors the 01a `InventoryItem` shape

```java
@Entity
@Table(name = "provision_equipment",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "name"}))
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Equipment {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;
  @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
  @Column(name = "name", nullable = false, length = 64) private String name;
  @Column(name = "available", nullable = false) private boolean available;
  @Column(name = "details", length = 255) private String details;

  @Version @Column(name = "version", nullable = false) private long version;
  @CreationTimestamp @Column(name = "created_at", updatable = false, nullable = false) private Instant createdAt;
  @UpdateTimestamp  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
```

### Service-impl skeleton — upsertEquipment

```java
@Transactional
public EquipmentDto upsertEquipment(UUID userId, String name, UpsertEquipmentRequest request) {
  return equipmentRepository.findByUserIdAndName(userId, name)
      .map(existing -> {
        if (request.expectedVersion() == null || existing.getVersion() != request.expectedVersion()) {
          throw new OptimisticLockingFailureException("stale expectedVersion");
        }
        existing.setAvailable(request.available());
        existing.setDetails(request.details());
        Equipment saved = equipmentRepository.saveAndFlush(existing);   // gotcha: flush so @Version increments before mapping
        publisher.publishEvent(new EquipmentChangedEvent(
            userId, name, saved.isAvailable(), traceIdFromMdcOrRandom(), Instant.now()));
        return mapper.toDto(saved);
      })
      .orElseGet(() -> {
        Equipment created = Equipment.builder()
            .id(UUID.randomUUID())
            .userId(userId).name(name)
            .available(request.available())
            .details(request.details())
            .build();
        Equipment saved = equipmentRepository.saveAndFlush(created);
        publisher.publishEvent(new EquipmentChangedEvent(
            userId, name, saved.isAvailable(), traceIdFromMdcOrRandom(), Instant.now()));
        return mapper.toDto(saved);
      });
}
```

### Service-impl skeleton — markSpoiled (idempotent + audit)

```java
@Transactional
public InventoryItemDto markSpoiled(UUID itemId, UUID actorUserId) {
  InventoryItem item = inventoryItemRepository.findByIdAndUserId(itemId, actorUserId)
      .orElseThrow(InventoryItemNotFoundException::new);
  if (item.getItemStatus() == ItemLifecycleStatus.SPOILED) {
    return mapper.toDto(item);          // idempotent — no audit, no event
  }
  ItemLifecycleStatus prev = item.getItemStatus();
  item.setItemStatus(ItemLifecycleStatus.SPOILED);
  inventoryItemRepository.saveAndFlush(item);
  inventoryAuditLogRepository.save(buildAuditRow(
      item.getId(), actorUserId, "itemStatus",
      objectMapper.valueToTree(Map.of("itemStatus", prev.name())),
      objectMapper.valueToTree(Map.of("itemStatus", "SPOILED"))));
  publisher.publishEvent(new ItemSpoiledEvent(
      actorUserId, List.of(item.getId()), "user_marked", traceIdFromMdcOrRandom(), Instant.now()));
  return mapper.toDto(item);
}
```

## Edge-case checklist

- [ ] `PUT /equipment/{name}` first call (no row) → 201, audit irrelevant (equipment has no audit log in 01b), `EquipmentChangedEvent` published once
- [ ] `PUT /equipment/{name}` second call with correct `expectedVersion` → 200, version bumped
- [ ] `PUT /equipment/{name}` second call with stale `expectedVersion` → 409
- [ ] `PUT /equipment/{name}` second call without `expectedVersion` → 409 (treated as stale)
- [ ] `PUT /equipment/{name}` validation: `name` not matching `^[a-z0-9_]+$` → 400; `name > 64` → 400; `details > 255` → 400
- [ ] `GET /equipment` returns the user's rows sorted by `name`; another user's rows are not leaked
- [ ] `DELETE /equipment/{name}` for missing row → 404; existing → 204; `EquipmentChangedEvent(nowAvailable=false)` published once
- [ ] `POST /inventory/{itemId}/mark-spoiled` for an active item → 200, `itemStatus = SPOILED`, audit row written, `ItemSpoiledEvent` published once after commit
- [ ] `POST /inventory/{itemId}/mark-spoiled` for an already-spoiled item → 200 (idempotent), no new audit row, no event
- [ ] `POST /inventory/{itemId}/mark-spoiled` for an item owned by another user → 404
- [ ] `POST /inventory/{itemId}/mark-exhausted` analogous; emits `ItemRanOutEvent` with `wasStaple` mirroring the row's `isStaple`
- [ ] `DELETE /inventory/{itemId}` sets `itemStatus = WASTED`; subsequent `GET /inventory` (01a's listing of active items only) does NOT return it
- [ ] `DELETE /inventory/{itemId}` for already-`WASTED` row → 204 idempotent, no extra audit row, no event
- [ ] `GET /inventory/{itemId}/audit-log` newest-first, default size 20, `size > 100` clamped, single row per mark-spoiled / mark-exhausted action
- [ ] `GET /inventory/{itemId}/audit-log` for an item owned by another user → 404 (don't leak existence)
- [ ] No raw `userId` accepted from request body or query — always resolved server-side
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `ProvisionsBoundaryTest` (from 01a) still passes — new repos sit in the existing `domain/repository` package
- [ ] Repeatable migration `R__provision_seed_equipment_catalogue.sql` is idempotent — running it twice (Flyway repeatable behaviour) leaves 15 rows, not 30
- [ ] `R__provision_seed_equipment_catalogue.sql` runs after V20260601700200 because Flyway runs repeatables after all versioned migrations

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601700200__provision_create_equipment.sql
NEW   src/main/resources/db/migration/R__provision_seed_equipment_catalogue.sql

NEW   src/main/java/com/example/mealprep/provisions/api/controller/EquipmentController.java
NEW   src/main/java/com/example/mealprep/provisions/api/controller/InventoryAdminController.java          (mark-spoiled, mark-exhausted, audit-log GET; soft-delete DELETE lives on the existing InventoryController)
NEW   src/main/java/com/example/mealprep/provisions/api/dto/EquipmentDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/UpsertEquipmentRequest.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/InventoryAuditEntryDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/mapper/EquipmentMapper.java
NEW   src/main/java/com/example/mealprep/provisions/api/mapper/InventoryAuditMapper.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/Equipment.java
NEW   src/main/java/com/example/mealprep/provisions/domain/repository/EquipmentRepository.java
NEW   src/main/java/com/example/mealprep/provisions/event/EquipmentChangedEvent.java
NEW   src/main/java/com/example/mealprep/provisions/event/ItemSpoiledEvent.java
NEW   src/main/java/com/example/mealprep/provisions/event/ItemRanOutEvent.java
NEW   src/main/java/com/example/mealprep/provisions/exception/EquipmentNotFoundException.java

MOD   src/main/java/com/example/mealprep/provisions/api/controller/InventoryController.java               (append DELETE handler — softDeleteInventoryItem)
MOD   src/main/java/com/example/mealprep/provisions/api/ProvisionsExceptionHandler.java                   (append @ExceptionHandler(EquipmentNotFoundException.class); KEEP @Order(HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/provisions/domain/repository/InventoryAuditLogRepository.java    (already exists from 01a — pagination method may need to be added if 01a didn't ship findByInventoryItemIdOrderByOccurredAtDesc-paginated)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionQueryService.java             (append getEquipment, getAvailableEquipment, getInventoryAuditLog)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionUpdateService.java            (append upsertEquipment, deleteEquipment, markSpoiled, markExhausted, softDeleteInventoryItem)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java    (implement new methods)
MOD   src/main/java/com/example/mealprep/provisions/ProvisionsModule.java                                 (no change to re-exports — interfaces grow but module facade still exposes the same two)

MOD   src/main/resources/openapi/paths/provisions.yaml                                                   (append 5 new path-items; DELETE added inline under existing inventoryItem)
MOD   src/main/resources/openapi/schemas/provisions.yaml                                                 (append 5 new schemas)
MOD   src/main/resources/openapi/openapi.yaml                                                            (5 lines under paths:; 4 lines under components.schemas:)

NEW   src/test/java/com/example/mealprep/provisions/EquipmentFlowIT.java
NEW   src/test/java/com/example/mealprep/provisions/InventoryAdminFlowIT.java
NEW   src/test/java/com/example/mealprep/provisions/EquipmentSeedCatalogueIT.java                        (asserts 15 rows post-migration)
MOD   src/test/java/com/example/mealprep/provisions/ProvisionServiceImplTest.java                       (append unit coverage for upsert, idempotent marks, soft-delete)
MOD   src/test/java/com/example/mealprep/provisions/testdata/ProvisionsTestData.java                     (append equipment fixture builder)
```

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`
- `ProvisionsBoundaryTest` is unchanged (new repo in the existing package; rule covers it).

## Dependencies

- **Hard dependency**: `provisions-01a` (merged) — `InventoryItem`, `InventoryAuditLog`, `ProvisionQueryService`, `ProvisionUpdateService`, `ProvisionsExceptionHandler`, `ProvisionsBoundaryTest`, `ProvisionsException`, `ItemLifecycleStatus`, `AuditActor`, `InventoryItemNotFoundException`.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged).
- **Sibling tickets running serially in this round** (Wave 2 round 2): `household-01b`, `nutrition-01b`, `recipe-01b`. None touch provisions files; this ticket touches no household / nutrition / recipe files.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] **`ProvisionsExceptionHandler` continues to be `@Order(Ordered.HIGHEST_PRECEDENCE)`** — appending handlers must not remove the annotation
- [ ] `saveAndFlush` used in `upsertEquipment` and `markSpoiled`/`markExhausted` so response payloads reflect bumped `@Version`
- [ ] `R__provision_seed_equipment_catalogue.sql` is idempotent (verified by IT that re-runs Flyway repeatable phase and asserts row count unchanged)
- [ ] `provision_equipment_catalogue` table populated with exactly 15 rows after migrations
- [ ] No regression on existing tests, including 01a's inventory IT suite

## What's NOT in scope

- `Budget` table + endpoints + spend-tracking → **provisions-01c**
- `SupplierProduct` table + endpoints + substitution-history JSONB → **provisions-01d**
- `WasteEntry` (`provision_waste_log`) + endpoints + `WasteValidator` → **provisions-01e**
- `ProvisionForPlannerBundleDto` + `ProvisionForPlannerService.getBundle` → **provisions-01f**
- `InventoryDeductionEngine` (FIFO-by-expiry), `applyCookEvent`, `applyMealConsumption`, `applyStandaloneConsumption`, `MealCookedEvent` listener, sealed `ProvisionChangedEvent` base → **provisions-01g**
- `PATCH /inventory/{itemId}/quantity` (`adjustQuantity`) → **provisions-01g** (cook-event partner)
- `GroceryImportProcessor` + `applyGroceryOrder` + `GroceryOrderConfirmedEvent` listener → **provisions-01h**
- `StapleStateTransitioner`, `getStaplesNeedingReplenishment`, `getAvailableStaples` endpoints → **provisions-01i**
- `BatchCookSplitter` (fridge/freezer split) → **provisions-01j**
- `ExpiryRule` registry + `ItemNearingExpiryEvent` daily sweep + retention sweep `@Scheduled` job → **provisions-01k**
- `applyFeedback(ProvisionsFeedbackCommand)` → **provisions-01l**
- Pantry-tracking-disabled gate (reading `pantry_tracking_enabled` from preference's lifestyle config) → **provisions-01m**
- Equipment audit-log (LLD doesn't spec one for equipment; out of scope)
- Public `provision_equipment_catalogue` read endpoint (separate small ticket if/when frontend needs it; nothing in 01b reads it)
- Equipment-by-userIds batch read (`getEquipmentByUserIds`) → **provisions-01f** (planner bundle)

Squash-merge with: `feat(provisions): 01b — equipment aggregate + seed catalogue + inventory admin endpoints`
