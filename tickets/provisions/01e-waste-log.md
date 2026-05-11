# Ticket: provisions — 01e Waste Log + `@ValidWasteQuantity` + Inventory Deduction on Waste

## Summary

Layer the **append-only waste log** on top of the 01a/01b/01c/01d provisions module per [LLD V20260502120400 (lines 207-222)](../../lld/provisions.md), [LLD §Flow 5 (lines 656-662)](../../lld/provisions.md), [LLD §`WasteController` (lines 491, 511-513)](../../lld/provisions.md). Ships the `WasteEntry` append-only entity (`provision_waste_log` table — no `@Version`, no `@LastModifiedDate`; LLD line 258), the `WasteEntryRepository` per LLD line 371, the four endpoints under `/api/v1/provisions/waste`: `POST /` (log waste), `GET /?from=&to=&page=&size=` (paginated history), `GET /summary?from=&to=` (count + cost-sum grouped by reason — LLD §`WasteSummaryDto` line 298), plus the class-level `@ValidWasteQuantity` custom validator (LLD line 548 — shape-only; the cross-resource "waste ≤ inventory" rule is **service-side** per LLD line 550). Behaviour per LLD §Flow 5: validate quantity vs remaining inventory when tracking active → persist waste entry (immutable) → **deduct from linked inventory row** + write `InventoryAuditLog` row with `actor = USER` + floor at zero + mark `WASTED` if exhausted → publish `ItemQuantityAdjustedEvent` with `source = WASTE`. Cross-module helper `getWasteForUserInWindow(userId, from, to)` appended to `ProvisionQueryService` for the (deferred) analytics aggregator. The `WasteExceedsInventoryException` (422, LLD line 530) when active-tracking quantity exceeds the linked inventory item's remaining quantity.

**LLD divergence note** — **`WasteSummaryDto` shape**: LLD line 298 declares `WasteSummaryDto(LocalDate from, LocalDate to, BigDecimal totalCostEstimate, int totalEntries, Map<WasteReason, Integer> countByReason, List<TopWastedItemDto> topItems)`. **`TopWastedItemDto` is not declared** in the LLD; 01e inlines its shape:

```java
public record TopWastedItemDto(String itemName, int entryCount, BigDecimal totalCost) {}
```

`topItems` is sorted by `entryCount DESC` with ties broken on `totalCost DESC`, returns top 10. The repository computes via `@Query` group-by on `(item_name)`. **Worth user review** — the LLD's `WasteSummaryDto` doesn't pin top-N or sort order; 01e picks top-10 / `entryCount DESC` because that's the most useful "what am I wasting most often?" UX surface.

**LLD divergence note** — **inventory deduction on waste vs `nullable inventory_item_id`**: LLD line 212 says `inventory_item_id uuid` (nullable); LLD §Flow 5 step 3 says "Deduct the wasted quantity from the linked inventory row." Reconciliation:

- **`inventoryItemId != null`** (linked inventory, tracking-on): full deduction flow runs — load inventory, validate quantity ≤ remaining, deduct, audit `actor = USER`, mark `WASTED` if exhausted, publish `ItemQuantityAdjustedEvent`.
- **`inventoryItemId == null`** (free-form waste log, tracking-off): persist the waste entry as denormalised data; **NO deduction, NO audit row, NO `ItemQuantityAdjustedEvent`**. The waste entry stands alone as analytics data. LLD line 213 already commits to denormalisation: "`item_name varchar(128) NOT NULL — denormalised at point of waste (immutable per HLD)`."
- **`inventoryItemId != null` but tracking-off on that item**: LLD line 659 says "Validate quantity vs remaining inventory when tracking active. Otherwise unconstrained per HLD." So validation is skipped, but **deduction still happens** when the inventory row is `tracking_mode = STATUS` (the LLD's status-tracked items have `quantity = null` in the DB, so deduction is a no-op). 01e's implementation: load inventory → if `trackingMode == STATUS`, skip the validation step but still write the `WASTED` lifecycle marker; if `trackingMode == QUANTITY`, run full validation + deduction.

**Worth user review**: the third case (`inventoryItemId != null` + `trackingMode = STATUS`) — should the deduction happen even though there's no quantity to decrement? 01e ships: lifecycle mark `WASTED` runs (1 row updated), no audit row for quantity (quantity didn't change), `ItemSpoiledEvent` (LLD line 565) published instead of `ItemQuantityAdjustedEvent` because the change is status, not quantity. Document on the impl Javadoc.

**LLD divergence note** — **`POST /waste` request shape**: LLD line 511 references `LogWasteRequest` but **doesn't pin the field list**. 01e locks the shape:

```java
public record LogWasteRequest(
    UUID inventoryItemId,                      // nullable — free-form waste log
    @NotBlank @Size(max = 128) String itemName, // required even when inventoryItemId present (denormalised)
    @ValidQuantity BigDecimal quantity,         // nullable for status-tracked items
    @Size(max = 16) String unit,                // nullable
    @NotNull WasteReason reason,
    @DecimalMin("0.0") @Digits(integer = 6, fraction = 2) BigDecimal costEstimate,  // nullable
    @NotNull @PastOrPresent LocalDate occurredOn,
    @Size(max = 255) String notes               // nullable
) {}
```

Class-level `@ValidWasteQuantity` enforces shape-only rules (LLD line 548):
- If `inventoryItemId != null` AND `quantity != null` → both non-null. (Permitted.)
- If `inventoryItemId == null` → `quantity` MAY be null (free-form pre-tracking waste). (Permitted.)
- If `quantity != null` → `unit != null`. (Consistency.)

The cross-resource "waste quantity ≤ inventory.quantity" rule is **service-side** (LLD line 548-550): runs only when tracking-active on the linked item; throws `WasteExceedsInventoryException` (422).

**Defers** (still out of scope after 01e):

- Cross-module helper exposure to a future analytics module — `getWasteForUserInWindow` ships on the interface but no analytics consumer in v1
- `WasteSummaryDto` `topItems` enrichment (e.g. linking back to current inventory rows to show "you still have X of this") → analytics-01a or later
- Free-form waste log → automatic inventory inference (ML mapping of `itemName` to `mapping_key`) → out of scope
- Waste-trends event (`WasteTrendDetectedEvent` — "you're throwing out X 3x more than last month") → planner module concern when it lands

01e unblocks the **waste tracking** dashboard and feeds the (future) planner's learning loop: per HLD's "waste-data → planner learning" hook (LLD line 745), the planner consumes `WasteSummaryDto` to adjust portion sizes and grocery-list quantities. Without 01e, the planner has no waste data to learn from.

## Behavioural spec

### Aggregate shape — `WasteEntry` (append-only)

1. `WasteEntry` is an **append-only entity** per [LLD §Entities line 258](../../lld/provisions.md): "Append-only. No `@Version`, no `@LastModifiedDate`. `inventoryItemId` nullable. `reason` enum."
2. Fields per [LLD V20260502120400 lines 210-218](../../lld/provisions.md):
   - `id (UUID, application-set)`
   - `userId (UUID NOT NULL)`
   - `inventoryItemId (UUID nullable — soft FK; free-form when null)`
   - `itemName (varchar 128 NOT NULL — denormalised at write time per LLD line 213)`
   - `quantity (BigDecimal(10,3) nullable — null for status-tracked or pre-tracking waste)`
   - `unit (varchar 16 nullable)`
   - `reason (WasteReason enum: EXPIRED | LEFTOVER_NOT_EATEN | DIDNT_LIKE | SPOILED_EARLY | MADE_TOO_MUCH; varchar 32; lowercase in DB matching LLD line 215)`
   - `costEstimate (BigDecimal(8,2) nullable — derived at write time if possible; explicit override accepted)`
   - `occurredOn (LocalDate NOT NULL — the date the user threw the item out; may be in the past)`
   - `notes (varchar 255 nullable)`
   - `createdAt (@CreatedDate)` — **NO `updatedAt`** (append-only).
3. **DB constraints / indexes** per LLD lines 220-221:
   - `CREATE INDEX idx_prov_waste_log_user_date ON provision_waste_log (user_id, occurred_on DESC)`
   - `CREATE INDEX idx_prov_waste_log_user_reason ON provision_waste_log (user_id, reason)`
4. **No `@Version`** — append-only; corrections create a new row (LLD line 660 "Persist the waste entry (immutable: no `@Version`, no update path)").

### `logWaste` flow (LLD §Flow 5)

5. `POST /api/v1/provisions/waste`. Authenticated. Server resolves `userId` via `CurrentUserResolver`. Body: `LogWasteRequest` (shape above).
6. **Validation cascade** (in order — surface the most actionable error first):
   1. Jakarta `@Valid` on the request → 400 on shape failures (incl. `@ValidWasteQuantity` class-level rule).
   2. `inventoryItemId != null` → load `inventoryRepository.findByIdAndUserId(inventoryItemId, userId)` → 404 `InventoryItemNotFoundException` if missing or not owned.
   3. **Service-side quantity check** (LLD line 550) — only when `tracking_mode == QUANTITY` AND `request.quantity != null`: if `request.quantity > inventory.quantity` → 422 `WasteExceedsInventoryException`.
7. **Single `@Transactional` write** (LLD line 658):
   1. Persist `WasteEntry` row. `id = UUID.randomUUID()`. `userId = actorUserId`. `itemName = request.itemName()` (denormalised verbatim per LLD line 213). `createdAt` auto via `@CreatedDate`.
   2. **If `inventoryItemId != null`**:
      - **`trackingMode == QUANTITY`**: deduct `request.quantity` from `inventory.quantity`. **Floor at zero** (LLD line 661 — "floor at zero"). Write `InventoryAuditLog` row with `actor = USER`, `actor_user_id = userId`, `field_changed = "quantity"`, `previous_value_json = {quantity: X}`, `new_value_json = {quantity: Y}` per LLD §V20260502120500. If post-deduct quantity == 0 → set `inventory.itemStatus = WASTED` (LLD line 661). JPA bumps `@Version` on the inventory row.
      - **`trackingMode == STATUS`**: NO quantity deduction; set `inventory.itemStatus = WASTED` (lifecycle update only); write `InventoryAuditLog` row with `field_changed = "itemStatus"`. JPA bumps `@Version`.
   3. **If `inventoryItemId == null`**: skip the inventory mutation entirely. The waste entry stands alone.
   4. **Idempotency**: not specified by LLD; 01e doesn't add a dedupe key. Concurrent identical POSTs land as two waste rows. **Worth user review** — alternative is `(user_id, inventory_item_id, occurred_on, quantity, reason)` natural-key dedupe; rejected because waste is genuinely repeated ("I threw out 2 separate bunches of celery yesterday"). Document on the controller Javadoc.
8. **Event** (LLD line 662):
   - `inventoryItemId != null` AND `trackingMode == QUANTITY`: publish `ItemQuantityAdjustedEvent(userId, affectedItemIds = [inventoryItemId], source = WASTE, traceId, occurredAt)` `AFTER_COMMIT`.
   - `inventoryItemId != null` AND `trackingMode == STATUS`: publish `ItemSpoiledEvent(userId, affectedItemIds = [inventoryItemId], reason = "wasted", traceId, occurredAt)` — re-uses LLD line 565's existing variant.
   - `inventoryItemId == null`: **NO inventory event** (no inventory was touched). 01e considers introducing `StandaloneWasteLoggedEvent` but defers — no listener yet; the waste row itself is the data.
9. Return 201 + `WasteEntryDto`. `Location: /api/v1/provisions/waste/{wasteEntryId}` (though no GET-by-id endpoint exists in 01e — `Location` set anyway for REST hygiene).

### `getWasteEntries` flow

10. `GET /api/v1/provisions/waste?from=&to=&page=&size=`. Authenticated. Server-resolved `userId`. `from` / `to` optional `LocalDate` query params; default `from = today - 90 days`, `to = today`. Validation: `from <= to`, else 400 `MethodArgumentNotValidException` via class-level `@ValidWasteDateRange` (NEW custom validator — class-level on the controller method's query params via a wrapper record `WasteListQuery`).
11. Repository: `findAllByUserIdAndOccurredOnBetweenOrderByOccurredOnDesc(userId, from, to, Pageable)`. Default page size 20, max 100.
12. Returns `Page<WasteEntryDto>`.

### `getWasteSummary` flow

13. `GET /api/v1/provisions/waste/summary?from=&to=`. Authenticated. Server-resolved `userId`. Same date-range validation as the list endpoint.
14. Repository: `@Query` aggregating `count(*)`, `sum(cost_estimate)`, group-by `reason` AND a second query group-by `item_name` for `topItems`. **One round-trip preferred** — the agent may use two queries if a single union-style query gets ugly; document the choice.
15. Builds `WasteSummaryDto` and returns 200.

### Cross-module helper

16. Append to `ProvisionQueryService`:
    ```java
    List<WasteEntryDto> getWasteForUserInWindow(UUID userId, LocalDate from, LocalDate to);
    ```
    Returns ALL entries in the window (no pagination — caller is the analytics aggregator, knows the volume). **Capped at 1000 rows** server-side via `PageRequest.of(0, 1000)`; if cap exceeded, returns the first 1000 and logs WARN "waste-window-cap-exceeded user=...". Document on the interface Javadoc.

### Service interfaces — append-only

17. Append to `ProvisionQueryService` (already declares `getSupplierProductByMappingKey` from 01d):
    ```java
    Page<WasteEntryDto> getWasteEntries(UUID userId, LocalDate from, LocalDate to, Pageable p);
    WasteSummaryDto getWasteSummary(UUID userId, LocalDate from, LocalDate to);
    List<WasteEntryDto> getWasteForUserInWindow(UUID userId, LocalDate from, LocalDate to);
    ```
    `getWasteEntries` and `getWasteSummary` verbatim from [LLD lines 405-406](../../lld/provisions.md).
18. Append to `ProvisionUpdateService` (already declares `upsertSupplierProduct` from 01d):
    ```java
    WasteEntryDto logWaste(UUID userId, LogWasteRequest request);
    ```
    Verbatim from [LLD line 439](../../lld/provisions.md).

### Repository — new

19. ```java
    interface WasteEntryRepository extends JpaRepository<WasteEntry, UUID> {
      Page<WasteEntry> findAllByUserIdAndOccurredOnBetweenOrderByOccurredOnDesc(
          UUID userId, LocalDate from, LocalDate to, Pageable p);

      List<WasteEntry> findAllByUserIdAndOccurredOnBetween(
          UUID userId, LocalDate from, LocalDate to, Pageable p);

      @Query("""
          select new com.example.mealprep.provisions.api.dto.ReasonAggregateRow(w.reason, count(w), coalesce(sum(w.costEstimate), 0))
            from WasteEntry w
           where w.userId = :userId and w.occurredOn between :from and :to
           group by w.reason""")
      List<ReasonAggregateRow> aggregateByReason(@Param("userId") UUID userId,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

      @Query("""
          select new com.example.mealprep.provisions.api.dto.TopWastedItemDto(w.itemName, count(w), coalesce(sum(w.costEstimate), 0))
            from WasteEntry w
           where w.userId = :userId and w.occurredOn between :from and :to
           group by w.itemName
           order by count(w) desc, coalesce(sum(w.costEstimate), 0) desc""")
      List<TopWastedItemDto> findTopWastedItems(@Param("userId") UUID userId,
                                                 @Param("from") LocalDate from,
                                                 @Param("to") LocalDate to,
                                                 Pageable p);
    }
    ```
    Package-private. `ReasonAggregateRow` is an internal projection record (declared in `api/dto/`, only used by the impl).

### Errors

20. New module exception subclass `WasteExceedsInventoryException` (422, `.../waste-exceeds-inventory`) — LLD line 530 names it. Extends the existing `ProvisionsException` from 01a.
21. **Append one new `@ExceptionHandler` method** to the existing `ProvisionsExceptionHandler` from 01a/01b/01c/01d (already `@Order(Ordered.HIGHEST_PRECEDENCE)`).

### Validation

22. **`@ValidWasteQuantity`** custom class-level annotation on `LogWasteRequest` per LLD line 548 — shape only:
    - `quantity != null ⇒ unit != null`.
    - `inventoryItemId == null` permits `quantity == null` (free-form pre-tracking waste).
    - **Does NOT check quantity vs inventory** (that's service-side per LLD line 550).
23. **`@ValidWasteDateRange`** NEW class-level annotation on `WasteListQuery` wrapper — checks `from <= to`. **Worth user review** — alternative is a service-side check; 01e picks Jakarta-level because it surfaces in the 400 error consistently.

## Database

```
src/main/resources/db/migration/V20260601700500__provision_create_waste_log.sql   new
```

Schema mirrors [LLD V20260502120400 lines 210-221](../../lld/provisions.md), renumbered to the provisions timestamp range (`V20260601700500` is the next free slot after 01d's `V20260601700400__provision_create_supplier_products.sql`):

```sql
-- V20260601700500
CREATE TABLE provision_waste_log (
    id                   uuid PRIMARY KEY,
    user_id              uuid NOT NULL,
    inventory_item_id    uuid,                                      -- nullable: free-form when tracking off
    item_name            varchar(128) NOT NULL,                     -- denormalised at point of waste (immutable per HLD)
    quantity             numeric(10,3),
    unit                 varchar(16),
    reason               varchar(32) NOT NULL,                      -- expired | leftover_not_eaten | didnt_like | spoiled_early | made_too_much
    cost_estimate        numeric(8,2),
    occurred_on          date NOT NULL,
    notes                varchar(255),
    created_at           timestamptz NOT NULL                       -- no updated_at, no @Version: append-only
);
CREATE INDEX idx_prov_waste_log_user_date   ON provision_waste_log (user_id, occurred_on DESC);
CREATE INDEX idx_prov_waste_log_user_reason ON provision_waste_log (user_id, reason);
```

`inventory_item_id` is intentionally **NOT FK-constrained** (LLD line 229's pattern for `inventory_audit.inventory_item_id` — soft FK so inventory deletions don't cascade-delete history; same applies to waste log).

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/provisions.yaml`

(Append two new path-items below 01d's supplier-product blocks. Do NOT touch existing path-items.)

```yaml
provisionsWaste:
  post:
    tags: [Provisions]
    operationId: logWaste
    summary: 'Log a wasted item; deducts from linked inventory when tracking-active.'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/LogWasteRequest' }
    responses:
      '201':
        description: 'Waste entry persisted.'
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/WasteEntryDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Linked inventory item not found / not owned', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Waste quantity exceeds remaining inventory (tracking active)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  get:
    tags: [Provisions]
    operationId: listWasteEntries
    summary: 'Paginated waste history; defaults to last 90 days.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: from
        required: false
        schema: { type: string, format: date }
      - in: query
        name: to
        required: false
        schema: { type: string, format: date }
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: 'Page of waste entries sorted occurred_on DESC.'
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/WasteEntryDtoPage' }
      '400': { description: 'Invalid date range (from > to)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
provisionsWasteSummary:
  get:
    tags: [Provisions]
    operationId: getWasteSummary
    summary: 'Aggregate waste for the date window: total cost, total entries, counts by reason, top wasted items.'
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
      '200':
        description: 'Summary.'
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/WasteSummaryDto' }
      '400': { description: 'Invalid date range', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/provisions.yaml`

```yaml
WasteReason:
  type: string
  enum: [EXPIRED, LEFTOVER_NOT_EATEN, DIDNT_LIKE, SPOILED_EARLY, MADE_TOO_MUCH]
WasteEntryDto:
  type: object
  required: [id, userId, itemName, reason, occurredOn, createdAt]
  properties:
    id: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    inventoryItemId:
      type: string
      format: uuid
      nullable: true
    itemName: { type: string, maxLength: 128 }
    quantity:
      type: number
      format: double
      minimum: 0
      nullable: true
    unit:
      type: string
      maxLength: 16
      nullable: true
    reason: { $ref: '#/WasteReason' }
    costEstimate:
      type: number
      format: double
      minimum: 0
      nullable: true
    occurredOn: { type: string, format: date }
    notes:
      type: string
      maxLength: 255
      nullable: true
    createdAt: { type: string, format: date-time }
LogWasteRequest:
  type: object
  required: [itemName, reason, occurredOn]
  properties:
    inventoryItemId:
      type: string
      format: uuid
      nullable: true
    itemName: { type: string, minLength: 1, maxLength: 128 }
    quantity:
      type: number
      format: double
      minimum: 0
      nullable: true
    unit:
      type: string
      maxLength: 16
      nullable: true
    reason: { $ref: '#/WasteReason' }
    costEstimate:
      type: number
      format: double
      minimum: 0
      nullable: true
    occurredOn: { type: string, format: date }
    notes:
      type: string
      maxLength: 255
      nullable: true
TopWastedItemDto:
  type: object
  required: [itemName, entryCount, totalCost]
  properties:
    itemName: { type: string, maxLength: 128 }
    entryCount: { type: integer, minimum: 0 }
    totalCost: { type: number, format: double, minimum: 0 }
WasteSummaryDto:
  type: object
  required: [from, to, totalCostEstimate, totalEntries, countByReason, topItems]
  properties:
    from: { type: string, format: date }
    to: { type: string, format: date }
    totalCostEstimate: { type: number, format: double, minimum: 0 }
    totalEntries: { type: integer, minimum: 0 }
    countByReason:
      type: object
      additionalProperties: { type: integer, minimum: 0 }
    topItems:
      type: array
      items: { $ref: '#/TopWastedItemDto' }
WasteEntryDtoPage:
  type: object
  additionalProperties: true
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/WasteEntryDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }
```

**Gotcha applied**: every nullable scalar uses **inline** `nullable: true`. `WasteReason` enum is `$ref`'d without nullable (required field).

**Gotcha applied**: `WasteEntryDtoPage` flat shape with `additionalProperties: true`.

**Gotcha applied**: all YAML descriptions with `,` `:` `'` single-quoted.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# provisions` block in `paths:`. Append two new path-item refs:

```yaml
  /api/v1/provisions/waste:
    $ref: 'paths/provisions.yaml#/provisionsWaste'
  /api/v1/provisions/waste/summary:
    $ref: 'paths/provisions.yaml#/provisionsWasteSummary'
```

**Location**: under `components.schemas:`, append five new schema refs in the existing `# provisions` block (alphabetical):

```yaml
    LogWasteRequest: { $ref: 'schemas/provisions.yaml#/LogWasteRequest' }
    TopWastedItemDto: { $ref: 'schemas/provisions.yaml#/TopWastedItemDto' }
    WasteEntryDto: { $ref: 'schemas/provisions.yaml#/WasteEntryDto' }
    WasteEntryDtoPage: { $ref: 'schemas/provisions.yaml#/WasteEntryDtoPage' }
    WasteReason: { $ref: 'schemas/provisions.yaml#/WasteReason' }
    WasteSummaryDto: { $ref: 'schemas/provisions.yaml#/WasteSummaryDto' }
```

## Verbatim shape snippets

### Entity (append-only — NO `@Version`, NO `@LastModifiedDate`)

```java
@Entity
@Table(name = "provision_waste_log")
@Getter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class WasteEntry {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "inventory_item_id", updatable = false)
  private UUID inventoryItemId;

  @Column(name = "item_name", nullable = false, updatable = false, length = 128)
  private String itemName;

  @Column(name = "quantity", updatable = false, precision = 10, scale = 3)
  private BigDecimal quantity;

  @Column(name = "unit", updatable = false, length = 16)
  private String unit;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, updatable = false, length = 32)
  private WasteReason reason;

  @Column(name = "cost_estimate", updatable = false, precision = 8, scale = 2)
  private BigDecimal costEstimate;

  @Column(name = "occurred_on", nullable = false, updatable = false)
  private LocalDate occurredOn;

  @Column(name = "notes", updatable = false, length = 255)
  private String notes;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
  // NO @Version, NO @LastModifiedDate — append-only per LLD line 258
}
```

### Service-impl — `logWaste` skeleton

```java
@Transactional
public WasteEntryDto logWaste(UUID userId, LogWasteRequest request) {
  InventoryItem inventory = null;
  if (request.inventoryItemId() != null) {
    inventory = inventoryRepository.findByIdAndUserId(request.inventoryItemId(), userId)
        .orElseThrow(InventoryItemNotFoundException::new);
    if (inventory.getTrackingMode() == TrackingMode.QUANTITY
        && request.quantity() != null
        && request.quantity().compareTo(inventory.getQuantity()) > 0) {
      throw new WasteExceedsInventoryException(request.inventoryItemId(),
          request.quantity(), inventory.getQuantity());
    }
  }
  WasteEntry entry = WasteEntry.builder()
      .id(UUID.randomUUID())
      .userId(userId)
      .inventoryItemId(request.inventoryItemId())
      .itemName(request.itemName())
      .quantity(request.quantity()).unit(request.unit())
      .reason(request.reason()).costEstimate(request.costEstimate())
      .occurredOn(request.occurredOn()).notes(request.notes())
      .build();
  WasteEntry saved = wasteEntryRepository.save(entry);

  if (inventory != null) {
    deductInventoryForWaste(inventory, request, userId);   // helper writes audit + bumps lifecycle + saves
  }
  // event publication delegated to the deducter / publisher per the 01a ProvisionEventBatcher pattern
  return mapper.toDto(saved);
}
```

### `WasteExceedsInventoryException`

```java
public class WasteExceedsInventoryException extends ProvisionsException {
  private static final URI TYPE = URI.create("https://mealprep.example.com/problems/waste-exceeds-inventory");
  private final UUID inventoryItemId;
  private final BigDecimal requested;
  private final BigDecimal remaining;

  public WasteExceedsInventoryException(UUID inventoryItemId, BigDecimal requested, BigDecimal remaining) {
    super("Waste quantity " + requested + " exceeds remaining inventory " + remaining
        + " for item " + inventoryItemId);
    this.inventoryItemId = inventoryItemId;
    this.requested = requested;
    this.remaining = remaining;
  }
  @Override public URI getType() { return TYPE; }
  @Override public HttpStatus getStatus() { return HttpStatus.UNPROCESSABLE_ENTITY; }
  // getters for ProblemDetail extension fields
}
```

## Edge-case checklist

- [ ] `POST /waste` with linked `inventoryItemId` (QUANTITY tracking), `quantity = 100g`, inventory.quantity = 500g → 201; inventory.quantity = 400g; waste row persisted; audit row written; `ItemQuantityAdjustedEvent` with `source = WASTE` published; `@Version` on inventory bumped
- [ ] `POST /waste` with linked `inventoryItemId` (QUANTITY), `quantity = 500g`, inventory.quantity = 500g → 201; inventory.quantity = 0; `itemStatus = WASTED`; audit row; event
- [ ] `POST /waste` with linked `inventoryItemId` (QUANTITY), `quantity = 600g`, inventory.quantity = 500g → 422 `waste-exceeds-inventory` with `inventoryItemId`, `requested`, `remaining` in ProblemDetail
- [ ] `POST /waste` with linked `inventoryItemId` (STATUS tracking) → 201; inventory.itemStatus = WASTED; audit row for `field_changed = itemStatus`; `ItemSpoiledEvent` published (NOT `ItemQuantityAdjustedEvent`)
- [ ] `POST /waste` with `inventoryItemId = null` (free-form) → 201; only waste row persisted; NO inventory mutation, NO audit row, NO inventory event
- [ ] `POST /waste` for other-user's inventory item → 404 `inventory-item-not-found`
- [ ] `POST /waste` validation: `itemName` blank → 400; `reason = null` → 400; `occurredOn = tomorrow` → 400 (`@PastOrPresent`); `quantity = -1` → 400 (`@ValidQuantity`); `quantity != null` without `unit` → 400 (`@ValidWasteQuantity`)
- [ ] `POST /waste` with `costEstimate = null` → 201 (cost is optional)
- [ ] `POST /waste` re-POST identical body → 2 rows persisted (no dedupe per LLD)
- [ ] **Append-only**: there is no `PUT /waste/{id}` or `DELETE /waste/{id}` endpoint; the LLD says immutable
- [ ] `WasteEntry` entity has NO `@Version` field, NO `@LastModifiedDate` (verified via reflection in a unit test OR by lack of getter)
- [ ] `GET /waste` default date range → returns last 90 days; sorted `occurred_on DESC`; paginated
- [ ] `GET /waste?from=2026-04-01&to=2026-03-01` → 400 `validation-error` (from > to)
- [ ] `GET /waste/summary?from=&to=` → `WasteSummaryDto` with correct `totalEntries`, `totalCostEstimate` (sum of non-null costs), `countByReason` map (one entry per reason that had at least one waste), `topItems` (top 10 by entryCount DESC)
- [ ] `getWasteForUserInWindow` returns up to 1000 entries, capped; logs WARN when cap exceeded (verified via test log capture)
- [ ] **`InventoryAuditLog` integration**: every quantity-deducting waste log writes exactly ONE audit row tied to the inventory item (verified via `JdbcTemplate` count on `provision_inventory_audit` after a `POST /waste`)
- [ ] Concurrent `POST /waste` on the same inventory item — first wins, second 409 (inventory's `@Version` collides) OR retries via Resilience4j (per 01a's `applyCookEvent` retry pattern — verify which applies here; **the agent should NOT add Resilience4j to `logWaste`** — let the second concurrent waste 409 cleanly; manual retry is acceptable UX for waste logging which is human-driven). Document the choice.
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `ProvisionsBoundaryTest` (from 01a) still passes — new repo in `domain/repository/`
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the new method
- [ ] `WasteEntryDtoPage` flat shape validates
- [ ] No N+1 — `logWaste` happy path: 1 SELECT inventory, 1 INSERT waste, 1 INSERT audit, 1 UPDATE inventory (4 statements)
- [ ] No regression on existing tests, including 01d's `SupplierProductsFlowIT`, 01c's `BudgetFlowIT`, 01b's `EquipmentFlowIT`, 01a's `InventoryFlowIT`

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601700500__provision_create_waste_log.sql

NEW   src/main/java/com/example/mealprep/provisions/api/controller/WasteController.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/LogWasteRequest.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/TopWastedItemDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/WasteEntryDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/WasteReason.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/WasteSummaryDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/ReasonAggregateRow.java          (internal JPA projection record)
NEW   src/main/java/com/example/mealprep/provisions/api/dto/WasteListQuery.java               (controller-method wrapper for @ValidWasteDateRange)
NEW   src/main/java/com/example/mealprep/provisions/api/mapper/WasteEntryMapper.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/WasteEntry.java
NEW   src/main/java/com/example/mealprep/provisions/domain/repository/WasteEntryRepository.java
NEW   src/main/java/com/example/mealprep/provisions/exception/WasteExceedsInventoryException.java
NEW   src/main/java/com/example/mealprep/provisions/validation/ValidWasteQuantity.java
NEW   src/main/java/com/example/mealprep/provisions/validation/WasteQuantityValidator.java
NEW   src/main/java/com/example/mealprep/provisions/validation/ValidWasteDateRange.java
NEW   src/main/java/com/example/mealprep/provisions/validation/WasteDateRangeValidator.java

MOD   src/main/java/com/example/mealprep/provisions/api/ProvisionsExceptionHandler.java                 (append 1 @ExceptionHandler method; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionQueryService.java         (append getWasteEntries, getWasteSummary, getWasteForUserInWindow)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionUpdateService.java        (append logWaste)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java (implement the four new methods; wire WasteEntryRepository + extend the audit/event helpers from 01a)

MOD   src/main/resources/openapi/paths/provisions.yaml      (append 2 new path-items below 01d's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/provisions.yaml    (append 6 new schemas)
MOD   src/main/resources/openapi/openapi.yaml               (2 lines under paths: in the `# provisions` block; 6 lines under components.schemas: in the `# provisions` block)

NEW   src/test/java/com/example/mealprep/provisions/WasteValidatorTest.java                          (LLD line 710: quantity ≤ inventory when tracking active, no rule when tracking off; both at validator level and service level)
NEW   src/test/java/com/example/mealprep/provisions/WasteLoggingIT.java                              (LLD line 725: deducts inventory, sets itemStatus = WASTED on exhaustion, exceeds-inventory 422, unconstrained when tracking off; STATUS-tracked emits ItemSpoiledEvent; standalone free-form emits no inventory event)
NEW   src/test/java/com/example/mealprep/provisions/WasteSummaryFlowIT.java                          (summary aggregation; topItems sort; date-range validation)
MOD   src/test/java/com/example/mealprep/provisions/testdata/ProvisionsTestData.java                 (append waste-request + waste-entry builders)
```

**Files this ticket does NOT modify**:

- `config/GlobalExceptionHandler.java`; `archunit/ModuleBoundaryTest.java`.
- Other modules' files (household, nutrition, recipe) — none touched.
- 01a's `InventoryItem` entity — used as-is via setters; no new fields, no migration on it.
- 01a's `InventoryAuditLog` entity — used as-is (existing audit infrastructure); 01e writes audit rows via the existing helper.
- 01a's `ProvisionEventBatcher` — used as-is to coalesce the event; 01e's waste path adds itself to the batcher's `WASTE` source enum value (which already exists per LLD line 568 — verify in 01a; if absent, **01e adds it** to the existing `ItemAdjustmentSource` enum). **The agent must check 01a's enum and document.**
- 01a's `ProvisionChangedEvent` sealed interface — uses existing `ItemQuantityAdjustedEvent` and `ItemSpoiledEvent` variants; no new event class.
- `ProvisionsBoundaryTest` is unchanged.
- 01a/01b/01c/01d's controllers — independent.

## Dependencies

- **Hard dependency**: `provisions-01a` (merged) — `InventoryItem`, `InventoryAuditLog`, `InventoryItemRepository`, `TrackingMode` enum, `ItemLifecycleStatus.WASTED` value, `ProvisionQueryService`, `ProvisionUpdateService`, `ProvisionsExceptionHandler`, `ProvisionsBoundaryTest`, `ProvisionsException`, `ItemQuantityAdjustedEvent` + `ItemSpoiledEvent` sealed variants of `ProvisionChangedEvent`, `ItemAdjustmentSource.WASTE` enum value, `ProvisionEventBatcher` helper, `@ValidQuantity` validator.
- **Hard dependency**: `provisions-01b` (merged) — extends the same two service interfaces.
- **Hard dependency**: `provisions-01c` (merged) — extends the same two service interfaces.
- **Hard dependency**: `provisions-01d` (merged) — extends the same two service interfaces; `paths/provisions.yaml` and `schemas/provisions.yaml` already have appended blocks; 01e appends below.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged).
- **Sibling tickets running in parallel** (Wave 2 round 5): `household-01e`, `nutrition-01e`, `recipe-01e`. Only collision is the entry `openapi.yaml`.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR
- [ ] All edge-case items above ticked
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the new method
- [ ] **`WasteEntry` entity has NO `@Version` and NO `@LastModifiedDate`** (append-only per LLD line 258 — verified in a unit test)
- [ ] OpenAPI 3.0 nullable scalars use **inline** `nullable: true`; `WasteEntryDtoPage` uses flat `Page<T>` shape with `additionalProperties: true`
- [ ] All YAML descriptions with `,` `:` `'` single-quoted
- [ ] `@ValidWasteQuantity` shape-only (no DB lookup); cross-resource rule service-side
- [ ] Inventory audit row written on every quantity-deducting waste log
- [ ] `ItemQuantityAdjustedEvent(source = WASTE)` emitted for QUANTITY-tracked deductions; `ItemSpoiledEvent` for STATUS-tracked; NO inventory event for free-form
- [ ] `getWasteForUserInWindow` caps at 1000 rows, logs WARN on overflow
- [ ] No N+1 — `logWaste` ≤ 4 SQL statements (verified via Hibernate stats); `getWasteSummary` ≤ 2 queries
- [ ] No regression on existing tests, including 01a's `InventoryFlowIT`
- [ ] No pom.xml dependency adds
- [ ] `ItemAdjustmentSource.WASTE` enum value confirmed (already exists in 01a per LLD line 568; if missing, 01e adds — document in report)

## What's NOT in scope

- `WasteEntry` correction / amend endpoint — append-only per LLD line 660; corrections create a new entry
- `WasteSummaryDto.topItems` enriched with current-inventory cross-references → analytics-01a or later
- ML-driven free-form-waste → inventory inference → out of scope (no plan)
- `WasteTrendDetectedEvent` (e.g. "wasting 3x more this month") → planner module concern when it lands
- Cross-module helper consumer (analytics aggregator) — 01e ships the helper interface but no consumer in v1
- `ProvisionForPlannerBundleDto` carrying `WasteSummaryDto` → **provisions-01f**
- `GET /waste/{id}` single-fetch endpoint — none specified in the LLD (LLD line 511-513 doesn't list it)

Squash-merge with: `feat(provisions): 01e — waste log (append-only) + log/list/summary endpoints + WasteExceedsInventoryException + inventory deduction integration`
