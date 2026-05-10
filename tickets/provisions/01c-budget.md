# Ticket: provisions — 01c Budget Aggregate

## Summary

Layer the **`Budget`** aggregate on top of the 01a/01b provisions module: one row per user (`weekly_target` + `currency` + `tolerance_over` + `price_sensitivity` + `enabled` flag — the "configuration" half of the LLD's budget model), V…700300 migration, package-private repository, mapper, and the two endpoints `GET /api/v1/provisions/budget` and `PUT /api/v1/provisions/budget`. Per [`lld/provisions.md`](../../lld/provisions.md) §V20260502120200 (`provision_budget` table), §`BudgetDto` (lines 287-291), §`BudgetRepository` (line 369), §Service Interfaces (`getBudget`, `getBudgetsByUserIds`, `initialiseBudget`, `updateBudget`), §`BudgetController` table line 508-509, §Events (`BudgetChangedEvent` lines 579-580).

**Defers** (still out of scope after 01c, per the parent's instructions and the LLD's "spend-tracking is derived" rule):
- **Spend-tracking derivation** (`BudgetSpendTrackingDto` — `currentWeekTarget`, `currentWeekActual`, `currentWeekRemaining`, `currentWeekOrders`, `rollingFourWeekAverage`) → **provisions-01f or 01h** (depends on the grocery-import flow that ships in 01h to populate `current_week_orders`). 01c's `BudgetDto` ships `spendTracking = null` — the field is on the response shape but populated only when 01f/01h lands. **LLD divergence noted**.
- `SupplierProduct` + endpoints + substitution-history JSONB → **provisions-01d**
- `WasteEntry` + endpoints + `WasteValidator` → **provisions-01e**
- `ProvisionForPlannerBundleDto` + bundle service → **provisions-01f** (depends on 01b/01c/01d)
- `InventoryDeductionEngine` + cook-event flows → **provisions-01g**
- Grocery import + idempotency → **provisions-01h** (the source of `currentWeekOrders`)
- `StapleStateTransitioner` + replenishment list endpoint → **provisions-01i**
- Batch-cook splitter, expiry sweep, retention sweep, feedback dispatch, pantry-disabled gate → **provisions-01j..01m**

01c unblocks the planner's "what's the user's budget?" read path — 01f's `ProvisionForPlannerBundleDto` injects `BudgetDto` into the bundle for the planner's cost-estimation gate.

**LLD divergence note** — **`spendTracking` deferred but field reserved**: LLD line 290 declares `BudgetDto` with a non-null `BudgetSpendTrackingDto spendTracking` field. The actual derivation needs `provision_grocery_orders` history (01h-only) and `provision_inventory_audit` cost roll-ups (01a-shipped). 01c **declares the OpenAPI field as nullable** and the Java DTO field as nullable; 01c's `BudgetMapper` always sets `spendTracking = null`. When 01f/01h lands, the mapper switches to populate the derived structure. The contract evolution is additive (null → populated); no breaking change. **Worth user review** — alternative is to ship a no-op `BudgetSpendCalculator` that returns zeros; rejected because zeros are misleading (a user with no order history would see "£0 / week spent" which reads as success, not absence-of-data).

**LLD divergence note** — **`initialiseBudget` vs `updateBudget`**: LLD lines 433-434 separate `initialiseBudget(UUID userId, UpdateBudgetRequest)` (insert path) from `updateBudget(UUID userId, UpdateBudgetRequest)` (update path). 01c **collapses to a single PUT-as-upsert** — the controller calls `BudgetService.upsertBudget(userId, request)` which detects the absence of a row via `findByUserId` and either inserts or updates. The `UpdateBudgetRequest.expectedVersion` field is **ignored on insert** (no row to lock against; treated as 0) and **enforced on update** (mismatch → 409). The two LLD methods become one; the service interface still exposes both names (deprecating `initialiseBudget` in a Javadoc) so 01f's planner-bundle wiring doesn't break. The `GET /budget` returns 404 when no row exists (LLD §REST line 508 — "200 / 404"); a follow-up PUT bootstraps. **Worth user review** — simplifies the controller substantially.

## Behavioural spec

### Aggregate shape

1. `Budget` is a **standalone aggregate root** — not a child of any other entity. Per [LLD §Entities line 256](../../lld/provisions.md): "Aggregate root. One per user. `@Version Long version`. `enabled` flag captures the HLD's 'budget is optional' rule." Fields per [LLD V20260502120200 lines 164-176](../../lld/provisions.md):
   - `id (UUID, application-set)`
   - `userId (UUID NOT NULL UNIQUE)`
   - `weeklyTarget (BigDecimal 8,2 NOT NULL — must be > 0; CHECK constraint at DB level)`
   - `currency (varchar 3 NOT NULL DEFAULT 'GBP' — ISO-4217 alpha-3 code)`
   - `toleranceOver (BigDecimal 8,2 NOT NULL DEFAULT 0 — must be >= 0; CHECK constraint)`
   - `priceSensitivity (PriceSensitivity enum: LOW|MODERATE|HIGH; varchar(16) NOT NULL DEFAULT 'moderate'; stored as lowercase to match the LLD's `'moderate'` default)`
   - `enabled (boolean NOT NULL DEFAULT true — HLD: budget is optional; users can pause without losing config)`
   - `version (@Version Long, default 0)`
   - `createdAt (@CreatedDate)`, `updatedAt (@LastModifiedDate)`
2. **`PriceSensitivity` enum** local to the module: `LOW`, `MODERATE`, `HIGH`. **Lowercase enum values in the DB** (LLD line 169 — `'moderate'`). Use lowercase Java enum constants (`low`, `moderate`, `high`) so JPA's default name-based mapping matches without a converter — same pattern as 01a's `HouseholdRole` (lowercase enums for DB-mapping parity).
3. **Database constraints** per LLD lines 173-176:
   - `UNIQUE (user_id)` (one budget per user) — enforced via `UNIQUE` column constraint AND a redundant `CREATE UNIQUE INDEX idx_prov_budget_user ON provision_budget (user_id)` (LLD line 176; the redundant index is harmless and makes the intent explicit).
   - `CHECK chk_weekly_target_pos (weekly_target > 0)`.
   - `CHECK chk_tolerance_nonneg (tolerance_over >= 0)`.

### `getBudget` (read-by-others)

4. `GET /api/v1/provisions/budget` returns `BudgetDto` (200) for the calling user, or 404 `BudgetNotFoundException` if no row exists yet. Cookie-auth required. Server resolves `userId` via `CurrentUserResolver` — never accepted from query/body.
5. Repository: `findByUserId(UUID userId)`. Single SELECT — no children, no joins.
6. `BudgetDto.spendTracking` is **always `null`** in 01c (see "LLD divergence note" in the summary). `BudgetMapper.toDto` does NOT call any spend-derivation service in 01c.

### `updateBudget` (PUT-as-upsert)

7. `PUT /api/v1/provisions/budget` accepts `UpdateBudgetRequest { @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer=6, fraction=2) BigDecimal weeklyTarget, @NotNull @Size(min=3, max=3) @Pattern(regexp="^[A-Z]{3}$") String currency, @DecimalMin("0.0") @Digits(integer=6, fraction=2) BigDecimal toleranceOver, @NotNull PriceSensitivity priceSensitivity, boolean enabled, long expectedVersion /* required on update; ignored on insert */ }`.
8. Single `@Transactional` write:
   - Load via `findByUserId(callerUserId)`.
   - **Insert path** (no row): generate UUID, persist with the request's fields. **`expectedVersion` ignored**. Return 200 with the new `BudgetDto`. Publish `BudgetChangedEvent` with `previousWeeklyTarget = null` (or — see invariant 13 — `BigDecimal.ZERO` if the event field is non-null).
   - **Update path** (row exists): if `existing.version != request.expectedVersion()` → throw `OptimisticLockingFailureException` (mapped to 409 by `GlobalExceptionHandler`'s existing handler). Update fields. JPA bumps `@Version`. Return 200.
9. **Currency conservation rule**: if `existing.currency != request.currency` and `existing != null`, **reject with 422 `BudgetCurrencyChangeException`** (new exception subclass; new `@ExceptionHandler` method). Justification: changing the currency unit silently invalidates spend-tracking historical data (when 01f/01h lands). Forcing the user to delete + recreate (or to use a future explicit migration endpoint) preserves data integrity. **LLD divergence noted** — LLD doesn't specify currency-change semantics; 01c locks "reject" because the conservative path is reversible later (relax to "allow" is easy; tighten "allow" to "reject" is breaking). **Worth user review.**
10. **Event**: publish `BudgetChangedEvent(UUID userId, BigDecimal previousWeeklyTarget /* nullable on insert */, BigDecimal newWeeklyTarget, PriceSensitivity newPriceSensitivity, UUID traceId, Instant occurredAt)` `AFTER_COMMIT`. **Skip the publish entirely when no field actually changed** (re-PUT of identical body — load existing, compare, diff is empty → no event). LLD divergence note: LLD line 579 declares the event with `previousWeeklyTarget` non-null; 01c **makes it nullable** to accommodate the insert path. Document on the record's Javadoc.

### `getBudgetsByUserIds` (cross-module batch — ships in 01c, used by 01f)

11. Append `List<BudgetDto> getBudgetsByUserIds(List<UUID> userIds)` to the existing `ProvisionQueryService` interface from 01a/01b. Used by the future planner-bundle aggregator (01f). Repository: `findAllByUserIdIn(Collection<UUID> userIds)` per [LLD line 369](../../lld/provisions.md). Single round-trip; result is `BudgetDto`s with `spendTracking = null` (same caveat). **No HTTP exposure** for this method — internal cross-module use only.

### Service interfaces — append-only to existing 01a/01b interfaces

12. Append to `ProvisionQueryService`:
    ```java
    Optional<BudgetDto> getBudget(UUID userId);
    List<BudgetDto> getBudgetsByUserIds(List<UUID> userIds);
    ```
13. Append to `ProvisionUpdateService`:
    ```java
    BudgetDto upsertBudget(UUID userId, UpdateBudgetRequest request);                     // 01c collapsed PUT-as-upsert
    @Deprecated default BudgetDto initialiseBudget(UUID userId, UpdateBudgetRequest r) { return upsertBudget(userId, r); }
    @Deprecated default BudgetDto updateBudget(UUID userId, UpdateBudgetRequest r)     { return upsertBudget(userId, r); }
    ```
    The two `@Deprecated` defaults preserve the LLD's two-method signature for any future caller that follows the LLD's interface verbatim. **Documented**: 01f's planner-bundle should call `upsertBudget` (or `getBudget` for reads).

### Repository — package-private

14. ```java
    interface BudgetRepository extends JpaRepository<Budget, UUID> {
      Optional<Budget> findByUserId(UUID userId);
      List<Budget> findAllByUserIdIn(Collection<UUID> userIds);
    }
    ```
15. **Boundary**: existing `ProvisionsBoundaryTest` from 01a covers the new repo (lives in `domain/repository/`). **No changes to the test**.

### Errors

16. New module exception subclasses extending the existing `ProvisionsException` from 01a:
    - `BudgetNotFoundException` (404, `type = .../budget-not-found`).
    - `BudgetCurrencyChangeException` (422, `type = .../budget-currency-change-rejected`) — see invariant 9.
17. **Append two new `@ExceptionHandler` methods** to the existing `ProvisionsExceptionHandler` `@RestControllerAdvice` from 01a (which is already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler` (409 for stale `expectedVersion`). `MethodArgumentNotValidException` (validation) likewise.

## Database

```
src/main/resources/db/migration/V20260601700300__provision_create_budget.sql   new
```

Schema mirrors [LLD V20260502120200 lines 161-177](../../lld/provisions.md), renumbered to the provisions timestamp range (`V20260601700xxx+`, after 01b's equipment migration `V20260601700200`). The repeatable seed migration from 01b (`R__provision_seed_equipment_catalogue.sql`) is unrelated; budget has no seed.

```sql
-- V20260601700300
CREATE TABLE provision_budget (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL UNIQUE,
    weekly_target   numeric(8,2) NOT NULL,
    currency        varchar(3) NOT NULL DEFAULT 'GBP',
    tolerance_over  numeric(8,2) NOT NULL DEFAULT 0,
    price_sensitivity varchar(16) NOT NULL DEFAULT 'moderate',
    enabled         boolean NOT NULL DEFAULT true,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT chk_weekly_target_pos CHECK (weekly_target > 0),
    CONSTRAINT chk_tolerance_nonneg  CHECK (tolerance_over >= 0)
);
CREATE UNIQUE INDEX idx_prov_budget_user ON provision_budget (user_id);
```

`price_sensitivity` width: longest enum value is `'moderate'` (8 chars); LLD's `varchar(16)` carries headroom. `currency` width: ISO-4217 alpha-3 codes are exactly 3 chars; LLD pins `varchar(3)` — preserve. **DO NOT** widen any column from LLD spec — the validation pattern `^[A-Z]{3}$` on the DTO matches the column width exactly.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/provisions.yaml`

(File created by 01a, extended by 01b — append two new path-items below 01b's equipment / inventory-admin blocks. Do NOT touch existing path-items.)

```yaml
provisionsBudget:
  get:
    tags: [Provisions]
    operationId: getProvisionsBudget
    summary: Return the calling user's budget configuration, or 404 if not yet initialised.
    security: [{ cookieAuth: [] }]
    responses:
      '200':
        description: Budget configuration; spendTracking is null in v1 (derivation deferred to provisions-01f/01h).
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/BudgetDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Budget not initialised for the calling user, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  put:
    tags: [Provisions]
    operationId: upsertProvisionsBudget
    summary: Insert or update the calling user's budget configuration.
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/provisions.yaml#/UpdateBudgetRequest' }
    responses:
      '200':
        description: Budget upserted (insert and update both return 200).
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/BudgetDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion (update path only), content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: Currency change rejected (use an explicit reset endpoint when one lands), content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/provisions.yaml`

(Append four new schemas. Do NOT touch 01a/01b schemas.)

```yaml
PriceSensitivity:
  type: string
  enum: [low, moderate, high]
BudgetSpendTrackingDto:
  type: object
  description: Reserved for v1.5; populated by provisions-01f/01h. v1 leaves this null on every BudgetDto.
  required: [currentWeekTarget, currentWeekActual, currentWeekRemaining, rollingFourWeekAverage]
  properties:
    currentWeekTarget: { type: number, format: double }
    currentWeekActual: { type: number, format: double }
    currentWeekRemaining: { type: number, format: double }
    currentWeekOrders:
      type: array
      items: { $ref: '#/BudgetOrderRefDto' }
    rollingFourWeekAverage: { type: number, format: double }
BudgetOrderRefDto:
  type: object
  required: [supplier, orderRef, totalCost, deliveredOn]
  properties:
    supplier: { type: string, maxLength: 32 }
    orderRef: { type: string, maxLength: 128 }
    totalCost: { type: number, format: double }
    deliveredOn: { type: string, format: date }
BudgetDto:
  type: object
  required: [id, userId, weeklyTarget, currency, toleranceOver, priceSensitivity, enabled, version]
  properties:
    id: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    weeklyTarget: { type: number, format: double, exclusiveMinimum: true, minimum: 0 }
    currency:
      type: string
      minLength: 3
      maxLength: 3
      pattern: '^[A-Z]{3}$'
    toleranceOver: { type: number, format: double, minimum: 0 }
    priceSensitivity: { $ref: '#/PriceSensitivity' }
    enabled: { type: boolean }
    spendTracking:
      type: object
      nullable: true
      description: Always null in v1 (provisions-01c). Populated by 01f/01h once order history is wired.
      properties:
        currentWeekTarget: { type: number, format: double }
        currentWeekActual: { type: number, format: double }
        currentWeekRemaining: { type: number, format: double }
        currentWeekOrders:
          type: array
          items: { $ref: '#/BudgetOrderRefDto' }
        rollingFourWeekAverage: { type: number, format: double }
    version: { type: integer, format: int64 }
UpdateBudgetRequest:
  type: object
  required: [weeklyTarget, currency, priceSensitivity, expectedVersion]
  properties:
    weeklyTarget: { type: number, format: double, exclusiveMinimum: true, minimum: 0 }
    currency:
      type: string
      minLength: 3
      maxLength: 3
      pattern: '^[A-Z]{3}$'
    toleranceOver: { type: number, format: double, minimum: 0, default: 0 }
    priceSensitivity: { $ref: '#/PriceSensitivity' }
    enabled: { type: boolean, default: true, nullable: true }
    expectedVersion: { type: integer, format: int64, minimum: 0, default: 0 }
```

**Gotcha applied**: `BudgetDto.spendTracking` uses **inline** `type: object` + `nullable: true` (NOT `$ref` to `BudgetSpendTrackingDto` with `nullable: true` — sibling keywords on `$ref` are silently ignored by swagger-parser per the agent-prompt-template gotcha list). The named `BudgetSpendTrackingDto` schema is still declared (for 01f/01h to switch the inline object to a `$ref` once the field is no longer nullable), but `BudgetDto` itself uses the inline form for nullability.

**Gotcha applied**: `UpdateBudgetRequest.enabled` is a Boolean with a default but explicitly marked `nullable: true` (per the gotcha — Jackson serialises an unset boxed `Boolean` to `null`, which a non-nullable schema rejects). Alternative: declare the DTO field as primitive `boolean` (unset stays `false`); 01c picks `nullable: true` because the LLD's "budget is optional" semantics may justify a future "no preference" tri-state. **Worth user review.**

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# provisions` block in `paths:` (after 01b's equipment / inventory-admin refs). Append one new path-item ref:

```yaml
  /api/v1/provisions/budget:
    $ref: 'paths/provisions.yaml#/provisionsBudget'
```

**Location**: under `components.schemas:`, append five new schema refs in the existing `# provisions` block (alphabetical order — `BudgetDto`, `BudgetOrderRefDto`, `BudgetSpendTrackingDto`, `PriceSensitivity`, `UpdateBudgetRequest`):

```yaml
    BudgetDto: { $ref: 'schemas/provisions.yaml#/BudgetDto' }
    BudgetOrderRefDto: { $ref: 'schemas/provisions.yaml#/BudgetOrderRefDto' }
    BudgetSpendTrackingDto: { $ref: 'schemas/provisions.yaml#/BudgetSpendTrackingDto' }
    PriceSensitivity: { $ref: 'schemas/provisions.yaml#/PriceSensitivity' }
    UpdateBudgetRequest: { $ref: 'schemas/provisions.yaml#/UpdateBudgetRequest' }
```

## Verbatim shape snippets

### Entity

Mirrors 01b's `Equipment` shape (single-table aggregate root, no JSONB, no children). No `text[]`/JSONB workaround needed.

```java
@Entity
@Table(name = "provision_budget")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Budget {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false, unique = true)
  private UUID userId;

  @Column(name = "weekly_target", nullable = false, precision = 8, scale = 2)
  private BigDecimal weeklyTarget;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "tolerance_over", nullable = false, precision = 8, scale = 2)
  private BigDecimal toleranceOver;

  @Enumerated(EnumType.STRING)
  @Column(name = "price_sensitivity", nullable = false, length = 16)
  private PriceSensitivity priceSensitivity;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

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

### Repository — package-private

```java
interface BudgetRepository extends JpaRepository<Budget, UUID> {
  Optional<Budget> findByUserId(UUID userId);
  List<Budget> findAllByUserIdIn(Collection<UUID> userIds);
}
```

### Mapper

`BudgetMapper.toDto` always sets `spendTracking = null` in 01c. The two-arg overload is reserved for 01f/01h.

```java
@Mapper(componentModel = "spring")
public interface BudgetMapper {
  /** v1 mapper — spendTracking always null. 01f/01h adds a two-arg overload that takes a tracking DTO. */
  @Mapping(target = "spendTracking", ignore = true)
  BudgetDto toDto(Budget entity);

  List<BudgetDto> toDtos(List<Budget> entities);
}
```

### Service-impl — upsert path skeleton

```java
@Transactional
public BudgetDto upsertBudget(UUID userId, UpdateBudgetRequest request) {
  Optional<Budget> existing = repo.findByUserId(userId);
  Budget toSave;
  BigDecimal previousWeeklyTarget = null;
  if (existing.isPresent()) {
    Budget e = existing.get();
    if (e.getVersion() != request.expectedVersion()) {
      throw new OptimisticLockingFailureException("stale expectedVersion");          // → 409
    }
    if (!e.getCurrency().equals(request.currency())) {
      throw new BudgetCurrencyChangeException("currency change requires an explicit reset");
    }
    previousWeeklyTarget = e.getWeeklyTarget();
    boolean noChange =
        e.getWeeklyTarget().compareTo(request.weeklyTarget()) == 0
        && e.getToleranceOver().compareTo(request.toleranceOver()) == 0
        && e.getPriceSensitivity() == request.priceSensitivity()
        && e.isEnabled() == request.enabled();
    if (noChange) {
      return mapper.toDto(e);                                                        // no event
    }
    e.setWeeklyTarget(request.weeklyTarget());
    e.setToleranceOver(request.toleranceOver());
    e.setPriceSensitivity(request.priceSensitivity());
    e.setEnabled(request.enabled());
    toSave = repo.saveAndFlush(e);                                                   // gotcha: flush so @Version increments
  } else {
    toSave = repo.save(Budget.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .weeklyTarget(request.weeklyTarget())
        .currency(request.currency())
        .toleranceOver(request.toleranceOver() == null ? BigDecimal.ZERO : request.toleranceOver())
        .priceSensitivity(request.priceSensitivity())
        .enabled(request.enabled())
        .build());
  }
  publisher.publishEvent(new BudgetChangedEvent(
      userId, previousWeeklyTarget, toSave.getWeeklyTarget(), toSave.getPriceSensitivity(),
      traceIdFromMdcOrRandom(), Instant.now()));
  return mapper.toDto(toSave);
}
```

## Edge-case checklist

- [ ] `GET /budget` for a user with no row → 404 `budget-not-found` ProblemDetail
- [ ] `GET /budget` happy path → 200, `spendTracking` field present and JSON-`null` (NOT omitted)
- [ ] `GET /budget` without cookie → 401
- [ ] `PUT /budget` insert path (no existing row) → 200; row visible on subsequent `GET`; `BudgetChangedEvent` published with `previousWeeklyTarget = null`
- [ ] `PUT /budget` update path with stale `expectedVersion` → 409 (`OptimisticLockException` → `concurrent-update` ProblemDetail from `GlobalExceptionHandler`)
- [ ] `PUT /budget` update path with matching `expectedVersion` → 200; `version` bumped; `BudgetChangedEvent` published with non-null `previousWeeklyTarget`
- [ ] `PUT /budget` re-PUT identical body → 200; `version` NOT bumped; **no event published**
- [ ] `PUT /budget` validation: `weeklyTarget <= 0` → 400; `weeklyTarget = null` → 400; `currency` not `^[A-Z]{3}$` → 400; `priceSensitivity = null` → 400; `toleranceOver < 0` → 400
- [ ] `PUT /budget` with `currency != existing.currency` (update path) → 422 `budget-currency-change-rejected`
- [ ] `PUT /budget` with `currency != null` on **insert path** → 200 (no existing currency to compare against)
- [ ] `PUT /budget` with `enabled = false` toggles the flag without affecting other fields
- [ ] DB CHECK `chk_weekly_target_pos` rejects a direct INSERT/UPDATE with `weekly_target = 0` (covered by an IT bypassing the service via `JdbcTemplate`)
- [ ] DB CHECK `chk_tolerance_nonneg` rejects a direct INSERT/UPDATE with `tolerance_over = -0.01` (covered by IT)
- [ ] DB UNIQUE on `user_id` rejects a second INSERT for the same user via direct `JdbcTemplate`
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `ProvisionsBoundaryTest` (from 01a) still passes — new repo in `domain/repository/` package
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the two new handler methods
- [ ] No N+1 on `getBudget` / `getBudgetsByUserIds` — single SELECT verified via Hibernate stats or statement-count assertion in IT
- [ ] No raw `userId` accepted from request body / query — server-resolved via `CurrentUserResolver` on the two endpoints
- [ ] `BudgetMapper.toDto` always sets `spendTracking = null` in 01c (compile-time `@Mapping(target = "spendTracking", ignore = true)` enforces this)

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601700300__provision_create_budget.sql

NEW   src/main/java/com/example/mealprep/provisions/api/controller/BudgetController.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/BudgetDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/BudgetSpendTrackingDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/BudgetOrderRefDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/UpdateBudgetRequest.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/PriceSensitivity.java
NEW   src/main/java/com/example/mealprep/provisions/api/mapper/BudgetMapper.java
NEW   src/main/java/com/example/mealprep/provisions/domain/entity/Budget.java
NEW   src/main/java/com/example/mealprep/provisions/domain/repository/BudgetRepository.java
NEW   src/main/java/com/example/mealprep/provisions/event/BudgetChangedEvent.java
NEW   src/main/java/com/example/mealprep/provisions/exception/BudgetNotFoundException.java
NEW   src/main/java/com/example/mealprep/provisions/exception/BudgetCurrencyChangeException.java

MOD   src/main/java/com/example/mealprep/provisions/api/ProvisionsExceptionHandler.java                (append 2 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionQueryService.java         (append getBudget, getBudgetsByUserIds)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionUpdateService.java        (append upsertBudget + 2 @Deprecated default forwarders for LLD parity)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java (implement the three new methods)

MOD   src/main/resources/openapi/paths/provisions.yaml      (append 1 new path-item below 01b's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/provisions.yaml    (append 5 new schemas: PriceSensitivity, BudgetSpendTrackingDto, BudgetOrderRefDto, BudgetDto, UpdateBudgetRequest)
MOD   src/main/resources/openapi/openapi.yaml               (1 line under paths: in the `# provisions` block; 5 lines under components.schemas: in the `# provisions` block)

NEW   src/test/java/com/example/mealprep/provisions/BudgetServiceTest.java
NEW   src/test/java/com/example/mealprep/provisions/BudgetFlowIT.java
MOD   src/test/java/com/example/mealprep/provisions/testdata/ProvisionsTestData.java                  (append budget-builder fixture)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-3 tickets running in parallel must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exceptions go in the existing `ProvisionsExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule lives in the existing `ProvisionsBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`, migrations, entities — none touched.
- 01a's `InventoryItem` / 01b's `Equipment` aggregates — budget is a peer aggregate; no cross-aggregate associations.
- `ProvisionsBoundaryTest` is unchanged (new repo lives in the same `domain/repository` package; rule already covers it).
- 01a's `InventoryController` and 01b's `EquipmentController` — budget gets its own controller.

## Dependencies

- **Hard dependency**: `provisions-01a` (merged) — `ProvisionQueryService`, `ProvisionUpdateService`, `ProvisionsExceptionHandler`, `ProvisionsBoundaryTest`, `ProvisionsException`. Audit-log infrastructure (created in 01a) is **not used** by 01c — budget changes audit through the `BudgetChangedEvent` only.
- **Hard dependency**: `provisions-01b` (merged) — extends the same two service interfaces; the `@ExceptionHandler` ordering pattern; the per-module YAML / advice append-only convention.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Sibling tickets running in parallel** (Wave 2 round 3): `household-01c`, `nutrition-01c`, `recipe-01c`. None should touch any provisions file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# provisions` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `ProvisionsExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after the two new methods are appended
- [ ] `saveAndFlush` used in the update path so the response payload reflects the bumped `@Version` (gotcha #3 from preference-01a)
- [ ] OpenAPI 3.0 nullable `spendTracking` uses **inline** `type: object` + `nullable: true` (NOT `$ref + nullable: true`)
- [ ] `enabled` Boolean field has `nullable: true` on the OpenAPI schema (matches the boxed `Boolean` Jackson behaviour) OR the Java DTO uses primitive `boolean` (pick one and document)
- [ ] No regression on existing tests, including 01a's inventory ITs and 01b's equipment / inventory-admin ITs
- [ ] No N+1 on `getBudget` / `getBudgetsByUserIds` — single SELECT verified via Hibernate stats or statement-count assertion in IT
- [ ] No pom.xml dependency adds (uses existing JPA / MapStruct)

## What's NOT in scope

- Spend-tracking derivation (`BudgetSpendTrackingDto` populated, `currentWeekActual`, `currentWeekOrders`, `rollingFourWeekAverage`) → **provisions-01f or 01h**
- `SupplierProduct` + endpoints + substitution-history JSONB → **provisions-01d**
- `WasteEntry` + endpoints + `WasteValidator` → **provisions-01e**
- `ProvisionForPlannerBundleDto` + `ProvisionForPlannerService.getBundle` → **provisions-01f**
- `InventoryDeductionEngine` (FIFO-by-expiry) + cook-event flows → **provisions-01g**
- Grocery import + idempotency → **provisions-01h** (the source of `currentWeekOrders`)
- `StapleStateTransitioner` + replenishment list endpoint → **provisions-01i**
- Batch-cook splitter, expiry sweep, retention sweep → **provisions-01j..01k**
- `applyFeedback(ProvisionsFeedbackCommand)` → **provisions-01l**
- Pantry-tracking-disabled gate (`pantry_tracking_enabled` from preference) → **provisions-01m**
- Currency change endpoint (explicit reset that wipes spend-tracking history) — locked-out in 01c (see invariant 9)
- Multi-currency support per user (the `currency` column is per-row; multi-currency would need a per-period audit table)

Squash-merge with: `feat(provisions): 01c — budget aggregate + GET/PUT endpoints (spend-tracking deferred to 01f/01h)`
