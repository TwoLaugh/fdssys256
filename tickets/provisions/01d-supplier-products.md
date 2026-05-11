# Ticket: provisions — 01d Supplier Products + Substitution History

## Summary

Layer the **`SupplierProduct`** aggregate on top of the 01a/01b/01c provisions module: the `provision_supplier_products` table per [LLD V20260502120300 lines 183-203](../../lld/provisions.md) (one row per `(supplier, productId)`, JSONB `substitution_history` for the user-correction substitute trail), the package-private repository, mapper, the `SupplierProductsController` endpoints under `/api/v1/provisions/supplier-products` (`POST /` upsert, `GET /` paginated search by `mappingKey` / `supplier`, `POST /{supplierProductId}/substitutions` record substitution event), and the cross-module read helpers `getSupplierProductByMappingKey(String)`, `getSupplierProductsByMappingKeys(Collection<String>)`, `getStaleSupplierProducts(LocalDate cutoff, Pageable)` on `ProvisionQueryService` per [LLD lines 402-404](../../lld/provisions.md). Plus `SupplierProductDto`, `UpsertSupplierProductRequest`, `SubstitutionRecordDto` DTOs per [LLD §DTOs lines 269 + §Entities line 257-258 + line 263](../../lld/provisions.md). Per [`lld/provisions.md`](../../lld/provisions.md) §V20260502120300, §`SupplierProduct` entity (line 257), §`SubstitutionRecord` JSONB inner shape (line 263), §`SupplierProductRepository` (line 370), §Service Interfaces (`upsertSupplierProduct` + `recordSubstitution` lines 435-436), §`SupplierProductsController` (LLD §REST line 510), §Events (`SubstitutionAcceptedEvent` line 567 — sealed variant).

**Defers** (still out of scope after 01d):

- `WasteEntry` + endpoints + `WasteValidator` → **provisions-01e**
- `ProvisionForPlannerBundleDto` + bundle service → **provisions-01f** (depends on 01b/01c/01d)
- `InventoryDeductionEngine` (FIFO-by-expiry) + cook-event flows → **provisions-01g**
- Grocery import + idempotency → **provisions-01h** (the source-of-truth populator of `SupplierProduct` rows for the user-facing UX; 01d's `POST /supplier-products` is the **manual** insertion path — admin-tool / direct API; grocery imports auto-upsert via `applyGroceryOrder`)
- `StapleStateTransitioner` + replenishment list endpoint → **provisions-01i**
- Stale-supplier-product sweep (`@Scheduled` job that flags rows where `last_checked < now - 4 weeks`) → **provisions-01j**
- Spend-tracking derivation populating `BudgetDto.spendTracking` from order history → **provisions-01f/01h** (per 01c's deferral note)

01d unblocks the **shopping-list cost-estimation** read path for the planner (01f's `ProvisionForPlannerBundleDto.supplierPricesByMappingKey` reads from this table via `getSupplierProductsByMappingKeys`). It also unblocks **substitution-acceptance UX** in the grocery-import flow — the user's "Tesco swapped my onion for a red onion" decision lands as one append to `substitution_history`. Without 01d, the only price source for the cost estimator is a hardcoded fallback; substitution tracking is impossible.

**LLD divergence note** — **`/supplier-products` endpoint scope**: LLD §REST line 510 specifies `GET /supplier-products?mappingKey=&supplier=` returning `Page<SupplierProductDto>`. **01d ships the GET listing endpoint plus a manual `POST /supplier-products` upsert path and a `POST /supplier-products/{id}/substitutions` record-substitution path** — the LLD's REST table only enumerates the GET, but the LLD's `ProvisionUpdateService` declares both `upsertSupplierProduct(UpsertSupplierProductRequest)` and `recordSubstitution(UUID, SubstitutionRecordDto, boolean)` (lines 435-436). **Without REST surfacing, the methods are unreachable from outside the JVM** — the grocery-import flow (01h) is the in-process caller, but admin tooling needs an HTTP path. **01d locks the REST shape** so 01h doesn't have to retrofit it. **Worth user review** — alternative is to keep `upsertSupplierProduct` in-process only until grocery-import lands; rejected because the (already-merged) 01c budget endpoint follows the same pattern (admin-tool reachable via REST), and the cost is two extra controller methods.

**LLD divergence note** — **`recordSubstitution` flow shape**: LLD §`recordSubstitution(UUID supplierProductId, SubstitutionRecordDto record, boolean userAccepted)` (line 436) is a JSONB-append: each call appends one `SubstitutionRecord { LocalDate date, String substitutedWithProductId, boolean accepted, String notes }` (LLD line 263) to the `substitution_history` JSONB column. **01d implements this as a read-modify-write**: load the entity, deserialise `substitutionHistory: List<SubstitutionRecord>`, append, persist. **Race**: two concurrent substitutions could overwrite each other (JSONB column has no list-level concurrency). **Resolution**: the entity carries `@Version` (LLD line 257). The append happens under `@Transactional`; concurrent appends collide on `@Version` → 409 retry. **Worth user review** — alternative is a separate `provision_supplier_product_substitutions` table to avoid JSONB-append concurrency entirely; rejected because LLD line 205 explicitly pins "substitution_history is the only JSONB column — append-only, read whole, qualifies under the style guide's JSONB rule" and converting it to a table would touch more of the LLD than the parent intends for 01d.

## Behavioural spec

### Aggregate shape — `SupplierProduct`

1. `SupplierProduct` is a **standalone aggregate root** per [LLD §Entities line 257](../../lld/provisions.md): "Aggregate root. `substitutionHistory` mapped to `List<SubstitutionRecord>` via JSONB. Unique `(supplier, productId)`. `@Version Long version`." Fields per [LLD V20260502120300 lines 184-198](../../lld/provisions.md):
   - `id (UUID, application-set)`
   - `productId (varchar 128 NOT NULL — supplier-native SKU; "tesco:567-onion-medium-loose")`
   - `supplier (varchar 32 NOT NULL — "tesco" / "ocado" / "sainsburys")`
   - `name (varchar 255 NOT NULL — supplier's display name)`
   - `price (BigDecimal(8,2) nullable — null when the supplier has no public price)`
   - `pricePerUnit (BigDecimal(8,4) nullable — fine-grained for per-kg / per-litre comparisons)`
   - `unit (varchar 16 nullable — "kg" / "l" / "pack")`
   - `packSizeG (Integer nullable — pack weight in grams; null when not weight-based)`
   - `packSizeUnit (varchar 16 nullable — "ml" / "pcs" / "kg")`
   - `category (varchar 64 nullable — supplier's category taxonomy)`
   - `clubcardPrice (BigDecimal(8,2) nullable — Tesco-specific loyalty pricing; null on non-Tesco)`
   - `lastChecked (LocalDate NOT NULL — when the price was last refreshed; staleness sweep reads this)`
   - `substitutionHistory (JSONB NOT NULL DEFAULT '[]'::jsonb — `List<SubstitutionRecord>` via `@Type(JsonType.class)`)`
   - `ingredientMappingKey (varchar 128 nullable — link to the ingredient-mapping cache from nutrition-01d; nullable because some supplier products don't normalise cleanly)`
   - `version (@Version Long)`
   - `createdAt (@CreatedDate)`, `updatedAt (@LastModifiedDate)`
2. **Database constraints / indexes** per [LLD lines 197-202](../../lld/provisions.md):
   - `UNIQUE (supplier, product_id)` (natural key — one row per supplier-SKU).
   - `CREATE INDEX idx_prov_supplier_products_mapping_key ON provision_supplier_products (ingredient_mapping_key)` — backs `findAllByIngredientMappingKeyIn` (the planner-bundle read path).
   - `CREATE INDEX idx_prov_supplier_products_last_checked ON provision_supplier_products (last_checked)` — backs the stale-sweep listing (deferred to 01j).
3. **`SubstitutionRecord` JSONB inner shape** per [LLD line 263](../../lld/provisions.md):
   ```java
   public record SubstitutionRecord(
       LocalDate date,
       String substitutedWithProductId,                // the SKU the supplier delivered instead
       boolean accepted,                                // user accepted or rejected the substitution
       String notes                                     // optional free-text — nullable
   ) {}
   ```
   Stored as a JSON array on the column; never queried by sub-field (always read-whole).

### `upsertSupplierProduct` flow

4. `POST /api/v1/provisions/supplier-products`. Authenticated. Body: `UpsertSupplierProductRequest { @NotBlank @Size(max = 128) String productId, @NotBlank @Size(max = 32) String supplier, @NotBlank @Size(max = 255) String name, @Digits(integer=6, fraction=2) @DecimalMin("0.0") BigDecimal price /* nullable */, @Digits(integer=4, fraction=4) @DecimalMin("0.0") BigDecimal pricePerUnit /* nullable */, @Size(max = 16) String unit /* nullable */, Integer packSizeG /* nullable */, @Size(max = 16) String packSizeUnit /* nullable */, @Size(max = 64) String category /* nullable */, @DecimalMin("0.0") BigDecimal clubcardPrice /* nullable */, @NotNull @PastOrPresent LocalDate lastChecked, @Size(max = 128) String ingredientMappingKey /* nullable */ }`.
5. **No user-scoping**: supplier products are **global reference data** per LLD line 257 ("Aggregate root" — no `userId` column). The supplier's pricing is shared across all users (one Tesco onion price for the whole platform). Authentication required (filter chain enforces 401 on anonymous) but no per-user ownership.
6. Single `@Transactional` write:
   - **Find by natural key**: `findBySupplierAndProductId(supplier, productId)`.
   - **Insert path** (no row): generate UUID, persist with the request's fields. `substitutionHistory = []` (empty list). Return 201 with `SupplierProductDto`. `Location: /api/v1/provisions/supplier-products/{id}`.
   - **Update path** (row exists): update all fields **except** `substitutionHistory` (preserved). JPA bumps `@Version`. Return 200. **No `expectedVersion` enforcement on this endpoint** — supplier-product pricing churn is high-frequency (every grocery import refreshes); enforcing optimistic-lock collisions would cause user-facing 409s on concurrent imports.
7. **No-op detection**: if every request field equals the existing row's value AND `substitutionHistory` is unchanged → **`lastChecked` still updated** (the "freshness" semantic is the only field that always changes; refreshing the staleness clock is the whole point of a no-op upsert). No event emitted on freshness-only refresh.

### `getSupplierProductByMappingKey` (cross-module batch sibling)

8. Append to existing `ProvisionQueryService`:
   ```java
   Optional<SupplierProductDto> getSupplierProductByMappingKey(String key);
   Map<String, SupplierProductDto> getSupplierProductsByMappingKeys(Collection<String> keys);
   Page<SupplierProductDto> getStaleSupplierProducts(LocalDate cutoff, Pageable p);
   ```
   Verbatim from [LLD lines 402-404](../../lld/provisions.md). **No HTTP exposure** for `getSupplierProductByMappingKey` (internal cross-module use only); `getStaleSupplierProducts` is also internal (the admin-staleness UI is deferred to 01j).
9. **Cardinality** of `getSupplierProductByMappingKey`: multiple rows can share the same `ingredient_mapping_key` (e.g. Tesco's regular onion AND Tesco's organic onion both map to `"onion"`). The method returns **one** — **the cheapest by `price_per_unit`**, breaking ties on `last_checked DESC` (freshest first). Repository: a sorted `@Query` returning `List<SupplierProduct>` with `LIMIT 1` semantics handled by `Optional<SupplierProduct>` via Spring Data's `findTopBy...`. **LLD divergence noted** — LLD doesn't specify the tie-break; 01d locks "cheapest, freshest" because that's the most useful default for cost estimation.
10. **`getSupplierProductsByMappingKeys`** batch sibling: single `IN (...)` query (`findAllByIngredientMappingKeyIn`) followed by an in-memory group-by-mapping-key + cheapest-pick per key. Returns `Map<String, SupplierProductDto>` keyed by `ingredientMappingKey`. Missing keys silently absent.

### `GET /api/v1/provisions/supplier-products`

11. `GET /api/v1/provisions/supplier-products?mappingKey=&supplier=&page=&size=`. Authenticated. Both filters optional; when both null, returns all rows paginated. Repository: dynamic `@Query` (Spring Data `Specification` or `@Query` with conditional WHERE) hitting either of the two indexes.
12. Pagination: default size 20, max 100. Sort: `last_checked DESC` (freshest first). Returns `Page<SupplierProductDto>`.

### `POST /api/v1/provisions/supplier-products/{supplierProductId}/substitutions`

13. Authenticated. Server resolves `actorUserId` via `CurrentUserResolver`. Body: `RecordSubstitutionRequest { @NotNull SubstitutionRecordDto record, boolean userAccepted, long expectedVersion }`.
14. **404 ladder**: row not found → 404 `SupplierProductNotFoundException` (LLD line 527).
15. **Stale `expectedVersion`** → 409 via `OptimisticLockingFailureException`.
16. Single `@Transactional` write:
   - Load entity by id.
   - Append `record` to `substitutionHistory` (in-Java list mutation; `@Type(JsonType.class)` re-serialises on flush).
   - JPA bumps `@Version`. Return 200 with updated `SupplierProductDto`.
17. **Event**: publish `SubstitutionAcceptedEvent(UUID userId, UUID supplierProductId, String orderedProductId, String substitutedProductId, UUID traceId, Instant occurredAt)` `AFTER_COMMIT` per [LLD line 567](../../lld/provisions.md) — sealed variant of `ProvisionChangedEvent`. **`userId` is the `actorUserId`** even though the row itself is user-agnostic (the audit trail wants to know who recorded the substitution decision).

### Service interfaces — append-only

18. Append to `ProvisionQueryService` (already declares `getBudget`, `getBudgetsByUserIds` from 01c):
    ```java
    Optional<SupplierProductDto> getSupplierProductByMappingKey(String key);
    Map<String, SupplierProductDto> getSupplierProductsByMappingKeys(Collection<String> keys);
    Page<SupplierProductDto> getStaleSupplierProducts(LocalDate cutoff, Pageable p);
    Page<SupplierProductDto> searchSupplierProducts(String mappingKey, String supplier, Pageable p);
    ```
    The last method (`searchSupplierProducts`) is new — backs the `GET /supplier-products` listing endpoint with both query filters optional.
19. Append to `ProvisionUpdateService` (already declares `upsertBudget` + deprecated forwarders from 01c):
    ```java
    SupplierProductDto upsertSupplierProduct(UpsertSupplierProductRequest request);
    SupplierProductDto recordSubstitution(UUID supplierProductId, SubstitutionRecordDto record,
                                          boolean userAccepted, UUID actorUserId, long expectedVersion);
    ```
    The `actorUserId` parameter is **added** beyond LLD line 436 — needed for the event payload (`userId` field) and for the future audit log. LLD line 436's `(UUID, SubstitutionRecordDto, boolean)` shape is a strict subset of 01d's; document the divergence on the interface Javadoc.

### Repository — package-private

20. ```java
    interface SupplierProductRepository extends JpaRepository<SupplierProduct, UUID> {
      Optional<SupplierProduct> findBySupplierAndProductId(String supplier, String productId);
      List<SupplierProduct> findAllByIngredientMappingKeyIn(Collection<String> mappingKeys);
      Page<SupplierProduct> findAllByLastCheckedBefore(LocalDate cutoff, Pageable p);

      // Search endpoint — both filters optional. Uses @Query for conditional WHEREs.
      @Query("""
          select sp from SupplierProduct sp
           where (:mappingKey is null or sp.ingredientMappingKey = :mappingKey)
             and (:supplier   is null or sp.supplier             = :supplier)""")
      Page<SupplierProduct> search(@Param("mappingKey") String mappingKey,
                                   @Param("supplier") String supplier,
                                   Pageable p);
    }
    ```
    The first three methods are verbatim from [LLD line 370](../../lld/provisions.md). `search` is new — see invariant 18.
21. **Boundary**: existing `ProvisionsBoundaryTest` from 01a covers the new repo (`domain/repository/`). **No changes to the test**.

### Errors

22. New module exception subclass `SupplierProductNotFoundException` (404, `type = .../supplier-product-not-found`) — LLD line 527 names it. Extends the existing `ProvisionsException` from 01a.
23. **Append one new `@ExceptionHandler` method** to the existing `ProvisionsExceptionHandler` `@RestControllerAdvice` from 01a (already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler` (409).

## Database

```
src/main/resources/db/migration/V20260601700400__provision_create_supplier_products.sql   new
```

Schema mirrors [LLD V20260502120300 lines 184-202](../../lld/provisions.md), renumbered to the provisions timestamp range (`V20260601700400` is the next free slot after 01c's `V20260601700300__provision_create_budget.sql`):

```sql
-- V20260601700400
CREATE TABLE provision_supplier_products (
    id                       uuid PRIMARY KEY,
    product_id               varchar(128) NOT NULL,
    supplier                 varchar(32) NOT NULL,
    name                     varchar(255) NOT NULL,
    price                    numeric(8,2),
    price_per_unit           numeric(8,4),
    unit                     varchar(16),
    pack_size_g              integer,
    pack_size_unit           varchar(16),
    category                 varchar(64),
    clubcard_price           numeric(8,2),
    last_checked             date NOT NULL,
    substitution_history     jsonb NOT NULL DEFAULT '[]'::jsonb,
    ingredient_mapping_key   varchar(128),
    version                  bigint NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    UNIQUE (supplier, product_id)
);
CREATE INDEX idx_prov_supplier_products_mapping_key
    ON provision_supplier_products (ingredient_mapping_key);
CREATE INDEX idx_prov_supplier_products_last_checked
    ON provision_supplier_products (last_checked);
```

`product_id` width: LLD pins `varchar(128)` — supplier SKUs are short, but Ocado-style category-encoded SKUs can run to ~80 chars; 128 carries comfortable headroom. `supplier` width: LLD pins `varchar(32)` — names are short ("tesco", "ocado", "sainsburys"). `category` width 64 matches supplier category-name conventions. `clubcard_price` non-Tesco rows leave the column null.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/provisions.yaml`

(File created by 01a, extended by 01b/01c — append three new path-items below 01c's budget blocks. Do NOT touch existing path-items.)

```yaml
provisionsSupplierProducts:
  get:
    tags: [Provisions]
    operationId: searchSupplierProducts
    summary: Paginated supplier-products search; both filters optional. Sorted last_checked DESC.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: mappingKey
        required: false
        schema: { type: string, maxLength: 128 }
      - in: query
        name: supplier
        required: false
        schema: { type: string, maxLength: 32 }
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: Page of supplier products.
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/SupplierProductDtoPage' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  post:
    tags: [Provisions]
    operationId: upsertSupplierProduct
    summary: Insert or update a supplier product. Refreshes `lastChecked` on every call; preserves `substitutionHistory`.
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/UpsertSupplierProductRequest' }
    responses:
      '200':
        description: Supplier product updated.
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/SupplierProductDto' }
      '201':
        description: Supplier product created.
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/SupplierProductDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
provisionsSupplierProductSubstitution:
  post:
    tags: [Provisions]
    operationId: recordSupplierProductSubstitution
    summary: Append a substitution event to a supplier product's history (JSONB append).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: supplierProductId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/RecordSubstitutionRequest' }
    responses:
      '200':
        description: Substitution recorded.
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/SupplierProductDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Supplier product not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/provisions.yaml`

(Append five new schemas. Do NOT touch 01a/01b/01c schemas.)

```yaml
SubstitutionRecordDto:
  type: object
  required: [date, substitutedWithProductId, accepted]
  properties:
    date: { type: string, format: date }
    substitutedWithProductId: { type: string, maxLength: 128 }
    accepted: { type: boolean }
    notes:
      type: string
      maxLength: 1000
      nullable: true
SupplierProductDto:
  type: object
  required: [id, productId, supplier, name, lastChecked, substitutionHistory, version]
  properties:
    id: { type: string, format: uuid }
    productId: { type: string, maxLength: 128 }
    supplier: { type: string, maxLength: 32 }
    name: { type: string, maxLength: 255 }
    price:
      type: number
      format: double
      minimum: 0
      nullable: true
    pricePerUnit:
      type: number
      format: double
      minimum: 0
      nullable: true
    unit:
      type: string
      maxLength: 16
      nullable: true
    packSizeG:
      type: integer
      nullable: true
    packSizeUnit:
      type: string
      maxLength: 16
      nullable: true
    category:
      type: string
      maxLength: 64
      nullable: true
    clubcardPrice:
      type: number
      format: double
      minimum: 0
      nullable: true
    lastChecked: { type: string, format: date }
    substitutionHistory:
      type: array
      items: { $ref: '#/SubstitutionRecordDto' }
    ingredientMappingKey:
      type: string
      maxLength: 128
      nullable: true
    version: { type: integer, format: int64 }
UpsertSupplierProductRequest:
  type: object
  required: [productId, supplier, name, lastChecked]
  properties:
    productId: { type: string, minLength: 1, maxLength: 128 }
    supplier: { type: string, minLength: 1, maxLength: 32 }
    name: { type: string, minLength: 1, maxLength: 255 }
    price:
      type: number
      format: double
      minimum: 0
      nullable: true
    pricePerUnit:
      type: number
      format: double
      minimum: 0
      nullable: true
    unit:
      type: string
      maxLength: 16
      nullable: true
    packSizeG:
      type: integer
      nullable: true
    packSizeUnit:
      type: string
      maxLength: 16
      nullable: true
    category:
      type: string
      maxLength: 64
      nullable: true
    clubcardPrice:
      type: number
      format: double
      minimum: 0
      nullable: true
    lastChecked: { type: string, format: date }
    ingredientMappingKey:
      type: string
      maxLength: 128
      nullable: true
RecordSubstitutionRequest:
  type: object
  required: [record, userAccepted, expectedVersion]
  properties:
    record: { $ref: '#/SubstitutionRecordDto' }
    userAccepted: { type: boolean }
    expectedVersion: { type: integer, format: int64, minimum: 0 }
SupplierProductDtoPage:
  type: object
  additionalProperties: true
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/SupplierProductDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }
```

**Gotcha applied**: every nullable scalar uses **inline** `nullable: true` (NOT `$ref + nullable: true`).

**Gotcha applied**: `SupplierProductDtoPage` uses the **flat** `Page<T>` shape with `additionalProperties: true`.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# provisions` block in `paths:` (after 01c's budget ref). Append two new path-item refs:

```yaml
  /api/v1/provisions/supplier-products:
    $ref: 'paths/provisions.yaml#/provisionsSupplierProducts'
  /api/v1/provisions/supplier-products/{supplierProductId}/substitutions:
    $ref: 'paths/provisions.yaml#/provisionsSupplierProductSubstitution'
```

**Location**: under `components.schemas:`, append five new schema refs in the existing `# provisions` block (alphabetical):

```yaml
    RecordSubstitutionRequest: { $ref: 'schemas/provisions.yaml#/RecordSubstitutionRequest' }
    SubstitutionRecordDto: { $ref: 'schemas/provisions.yaml#/SubstitutionRecordDto' }
    SupplierProductDto: { $ref: 'schemas/provisions.yaml#/SupplierProductDto' }
    SupplierProductDtoPage: { $ref: 'schemas/provisions.yaml#/SupplierProductDtoPage' }
    UpsertSupplierProductRequest: { $ref: 'schemas/provisions.yaml#/UpsertSupplierProductRequest' }
```

## Verbatim shape snippets

### Entity (with JSONB list mapping)

```java
@Entity
@Table(name = "provision_supplier_products",
       uniqueConstraints = @UniqueConstraint(columnNames = {"supplier", "product_id"}))
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SupplierProduct {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "product_id", nullable = false, updatable = false, length = 128)
  private String productId;

  @Column(name = "supplier", nullable = false, updatable = false, length = 32)
  private String supplier;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "price", precision = 8, scale = 2)
  private BigDecimal price;

  @Column(name = "price_per_unit", precision = 8, scale = 4)
  private BigDecimal pricePerUnit;

  @Column(name = "unit", length = 16)
  private String unit;

  @Column(name = "pack_size_g")
  private Integer packSizeG;

  @Column(name = "pack_size_unit", length = 16)
  private String packSizeUnit;

  @Column(name = "category", length = 64)
  private String category;

  @Column(name = "clubcard_price", precision = 8, scale = 2)
  private BigDecimal clubcardPrice;

  @Column(name = "last_checked", nullable = false)
  private LocalDate lastChecked;

  @Type(JsonType.class)
  @Column(name = "substitution_history", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<SubstitutionRecord> substitutionHistory = List.of();

  @Column(name = "ingredient_mapping_key", length = 128)
  private String ingredientMappingKey;

  @Version @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
```

### JSONB record (inner shape — package-level)

```java
public record SubstitutionRecord(
    LocalDate date,
    String substitutedWithProductId,
    boolean accepted,
    String notes                    // nullable
) {}
```

### Service-impl — `upsertSupplierProduct` skeleton

```java
@Transactional
public SupplierProductDto upsertSupplierProduct(UpsertSupplierProductRequest request) {
  Optional<SupplierProduct> existing = repo.findBySupplierAndProductId(
      request.supplier(), request.productId());
  if (existing.isPresent()) {
    SupplierProduct e = existing.get();
    e.setName(request.name());
    e.setPrice(request.price());
    e.setPricePerUnit(request.pricePerUnit());
    e.setUnit(request.unit());
    e.setPackSizeG(request.packSizeG());
    e.setPackSizeUnit(request.packSizeUnit());
    e.setCategory(request.category());
    e.setClubcardPrice(request.clubcardPrice());
    e.setLastChecked(request.lastChecked());
    e.setIngredientMappingKey(request.ingredientMappingKey());
    // substitutionHistory deliberately untouched
    return mapper.toDto(repo.saveAndFlush(e));                              // gotcha: flush so @Version increments
  }
  SupplierProduct toSave = SupplierProduct.builder()
      .id(UUID.randomUUID())
      .productId(request.productId()).supplier(request.supplier())
      .name(request.name()).price(request.price()).pricePerUnit(request.pricePerUnit())
      .unit(request.unit()).packSizeG(request.packSizeG()).packSizeUnit(request.packSizeUnit())
      .category(request.category()).clubcardPrice(request.clubcardPrice())
      .lastChecked(request.lastChecked()).ingredientMappingKey(request.ingredientMappingKey())
      .substitutionHistory(List.of())
      .build();
  return mapper.toDto(repo.save(toSave));
}
```

### Service-impl — `recordSubstitution` skeleton

```java
@Transactional
public SupplierProductDto recordSubstitution(UUID supplierProductId,
                                             SubstitutionRecordDto record,
                                             boolean userAccepted,
                                             UUID actorUserId,
                                             long expectedVersion) {
  SupplierProduct e = repo.findById(supplierProductId)
      .orElseThrow(SupplierProductNotFoundException::new);
  if (e.getVersion() != expectedVersion) {
    throw new OptimisticLockingFailureException("stale expectedVersion");
  }
  List<SubstitutionRecord> next = new ArrayList<>(e.getSubstitutionHistory());
  next.add(new SubstitutionRecord(record.date(), record.substitutedWithProductId(),
      userAccepted, record.notes()));
  e.setSubstitutionHistory(next);                                            // mutate-then-replace; JPA dirty-checks on reference equality
  SupplierProduct saved = repo.saveAndFlush(e);

  publisher.publishEvent(new SubstitutionAcceptedEvent(
      actorUserId, supplierProductId, e.getProductId(),
      record.substitutedWithProductId(),
      traceIdFromMdcOrRandom(), Instant.now()));
  return mapper.toDto(saved);
}
```

## Edge-case checklist

- [ ] `POST /supplier-products` for an unseen `(supplier, productId)` → 201; `Location` header set; row persisted with all fields; `substitutionHistory = []`
- [ ] `POST /supplier-products` for an existing `(supplier, productId)` → 200; updated fields reflected; **`substitutionHistory` preserved** (verify by pre-populating then re-POSTing without history field)
- [ ] `POST /supplier-products` re-POST identical body → 200; `lastChecked` updated (the freshness clock always ticks); `@Version` bumped; **no event** emitted
- [ ] `POST /supplier-products` validation: `productId` blank → 400; `supplier` blank → 400; `lastChecked` in the future → 400 (`@PastOrPresent`); `price < 0` → 400
- [ ] `POST /supplier-products` with `ingredientMappingKey = null` → 200; subsequent `getSupplierProductsByMappingKeys` for any key returns absent (the null-key row never matches)
- [ ] `GET /supplier-products` with both filters → paginated, sorted `last_checked DESC`; verify only matching rows returned
- [ ] `GET /supplier-products` with no filters → returns all rows paginated
- [ ] `GET /supplier-products?mappingKey=onion` with multiple Tesco onion rows → returns all; `getSupplierProductByMappingKey("onion")` cross-module method picks **the cheapest** (verified via `JdbcTemplate` insert of three rows with distinct prices)
- [ ] `POST /supplier-products/{id}/substitutions` happy path → 200; `substitutionHistory` JSONB array grew by one element with the correct fields; `SubstitutionAcceptedEvent` published with `userId = actorUserId`
- [ ] `POST /supplier-products/{id}/substitutions` validation: `record.date` null → 400; `record.substitutedWithProductId` blank → 400
- [ ] `POST /supplier-products/{id}/substitutions` for unknown id → 404 `supplier-product-not-found`
- [ ] `POST /supplier-products/{id}/substitutions` stale `expectedVersion` → 409
- [ ] **JSONB round-trip**: write entity with three substitution records → re-read → all three present in order, fields intact (including null `notes`)
- [ ] Concurrent `POST /supplier-products/{id}/substitutions` x 2 → one wins, the other 409s on `@Version` collision (the loser retries with refreshed version)
- [ ] `getSupplierProductByMappingKey("nonexistent")` → `Optional.empty()`
- [ ] `getSupplierProductsByMappingKeys(["onion", "garlic", "missing"])` → map with two entries (cheapest per key); `missing` silently absent
- [ ] `getStaleSupplierProducts(cutoff = today - 14d, p)` returns rows where `last_checked < cutoff`, paginated, sorted by `last_checked ASC` (oldest-first for the staleness UI)
- [ ] Cross-module helpers: no N+1 — `getSupplierProductsByMappingKeys` issues a single SQL `IN (...)` (verified via Hibernate stats)
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `ProvisionsBoundaryTest` (from 01a) still passes — new repo in `domain/repository/`
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the new handler method
- [ ] No raw `userId` accepted from request body / query — `actorUserId` server-resolved via `CurrentUserResolver` on the substitution endpoint
- [ ] `saveAndFlush` used in upsert + substitution paths so the response payload reflects the bumped `@Version`

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601700400__provision_create_supplier_products.sql

NEW   src/main/java/com/example/mealprep/provisions/api/controller/SupplierProductsController.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/SupplierProductDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/SubstitutionRecordDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/UpsertSupplierProductRequest.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/RecordSubstitutionRequest.java
NEW   src/main/java/com/example/mealprep/provisions/api/mapper/SupplierProductMapper.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/SupplierProduct.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/SubstitutionRecord.java   (package-level JSONB record)
NEW   src/main/java/com/example/mealprep/provisions/domain/repository/SupplierProductRepository.java
NEW   src/main/java/com/example/mealprep/provisions/event/SubstitutionAcceptedEvent.java   (sealed variant of ProvisionChangedEvent — check 01a's existing sealed-interface to confirm 01d only adds a permitted variant; if 01a's interface is closed, declare as a separate record event instead and document)
NEW   src/main/java/com/example/mealprep/provisions/exception/SupplierProductNotFoundException.java

MOD   src/main/java/com/example/mealprep/provisions/api/ProvisionsExceptionHandler.java                (append 1 @ExceptionHandler method; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionQueryService.java         (append getSupplierProductByMappingKey, getSupplierProductsByMappingKeys, getStaleSupplierProducts, searchSupplierProducts)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionUpdateService.java        (append upsertSupplierProduct, recordSubstitution)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java (implement the six new methods)

MOD   src/main/resources/openapi/paths/provisions.yaml      (append 2 new path-items below 01c's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/provisions.yaml    (append 5 new schemas)
MOD   src/main/resources/openapi/openapi.yaml               (2 lines under paths: in the `# provisions` block; 5 lines under components.schemas: in the `# provisions` block)

NEW   src/test/java/com/example/mealprep/provisions/SupplierProductsServiceTest.java
NEW   src/test/java/com/example/mealprep/provisions/SupplierProductsFlowIT.java
MOD   src/test/java/com/example/mealprep/provisions/testdata/ProvisionsTestData.java                  (append supplier-product + substitution builders)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-4 tickets running in parallel must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`, migrations, entities — none touched.
- 01a's `InventoryItem` / 01b's `Equipment` / 01c's `Budget` aggregates — peer; no cross-aggregate associations.
- `ProvisionsBoundaryTest` is unchanged.
- 01a's existing controllers (`InventoryController`) and 01b/01c's controllers (`EquipmentController`, `BudgetController`) — supplier products gets its own controller.
- **`ProvisionChangedEvent` sealed interface** — 01d's `SubstitutionAcceptedEvent` is **already listed** in 01a's permits clause per LLD line 567 ("permits ItemAddedFromGroceryEvent, ItemSpoiledEvent, ItemRanOutEvent, **SubstitutionAcceptedEvent**, ..."). 01d implements the variant; if 01a's actual permits clause is shorter (because the variant was deferred), 01d **modifies the permits clause** in `ProvisionChangedEvent.java` — that's the ONE shared file the agent may touch. Verify 01a's actual state before deciding; document in the report.

## Dependencies

- **Hard dependency**: `provisions-01a` (merged) — `ProvisionQueryService`, `ProvisionUpdateService`, `ProvisionsExceptionHandler`, `ProvisionsBoundaryTest`, `ProvisionsException`, the `hypersistence-utils-hibernate-63` JSONB plumbing, the `ProvisionChangedEvent` sealed interface (`SubstitutionAcceptedEvent` is one of its permitted variants per LLD line 567).
- **Hard dependency**: `provisions-01b` (merged) — extends the same two service interfaces; the per-module YAML / advice append-only convention.
- **Hard dependency**: `provisions-01c` (merged) — extends the same two service interfaces; the `@ExceptionHandler` ordering pattern.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Soft dependency**: `nutrition-01d` (parallel sibling) — the `ingredientMappingKey` column's referent comes from nutrition-01d's `IngredientMapping.search_term`. **No FK** — soft reference (LLD line 194's `varchar(128)` column has no FK, by design — supplier products outlive the ingredient cache's regeneration cycles). Sibling parallelism is safe.
- **Sibling tickets running in parallel** (Wave 2 round 4): `household-01d`, `nutrition-01d`, `recipe-01d`. None should touch any provisions file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# provisions` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after the new method is appended
- [ ] `saveAndFlush` used in the upsert + substitution paths so the response payload reflects the bumped `@Version`
- [ ] OpenAPI 3.0 nullable scalars use **inline** `nullable: true` (NOT `$ref + nullable: true`)
- [ ] `SupplierProductDtoPage` uses the **flat** Page<T> shape with `additionalProperties: true`
- [ ] JSONB `substitution_history` round-trips correctly via `hypersistence-utils-hibernate-63` `@Type(JsonType.class)` — verified by an IT that writes a 3-element history then re-reads
- [ ] No regression on existing tests, including 01a's `InventoryFlowIT`, 01b's `EquipmentFlowIT`, 01c's `BudgetFlowIT`
- [ ] No N+1 on `getSupplierProductsByMappingKeys` — single SQL `IN (...)` verified
- [ ] `ProvisionChangedEvent` sealed interface state confirmed — either `SubstitutionAcceptedEvent` is in the permits clause from 01a or 01d adds it (document the choice)
- [ ] No pom.xml dependency adds

## What's NOT in scope

- `WasteEntry` + endpoints + `WasteValidator` → **provisions-01e**
- `ProvisionForPlannerBundleDto` + `ProvisionForPlannerService.getBundle` → **provisions-01f**
- `InventoryDeductionEngine` (FIFO-by-expiry) + cook-event flows → **provisions-01g**
- Grocery import + idempotency (the in-process caller that upserts supplier products from order data) → **provisions-01h**
- `StapleStateTransitioner` + replenishment list endpoint → **provisions-01i**
- Stale-supplier-product `@Scheduled` sweep → **provisions-01j**
- Spend-tracking derivation from order history → **provisions-01f/01h** (per 01c's deferral note)
- Pantry-tracking-disabled gate → **provisions-01m**
- Multi-supplier price-comparison endpoint (return *all* suppliers for one mappingKey) — locked-out in 01d; the cross-module helper returns the cheapest only
- Bulk-upsert endpoint — none specified in the LLD
- Audit log for supplier-product mutations — LLD doesn't declare one; the `SubstitutionAcceptedEvent` is the audit trail for substitution events; upsert events are intentionally silent (high-churn refresh path)

Squash-merge with: `feat(provisions): 01d — supplier products aggregate + JSONB substitution history + upsert/search/record-substitution endpoints`
