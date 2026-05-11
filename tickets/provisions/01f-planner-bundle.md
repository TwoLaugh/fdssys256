# Ticket: provisions — 01f `ProvisionForPlannerService` + Planner-Bundle Endpoint

## Summary

Layer the **`ProvisionForPlannerService`** cross-module facade + `ProvisionForPlannerBundleDto` (snapshot of inventory + equipment + budget + supplier-products) + the single endpoint `GET /api/v1/provisions/planner-bundle` on top of the 01a/01b/01c/01d/01e provisions module. Per [LLD §`ProvisionForPlannerBundleDto` lines 304-319](../../lld/provisions.md), [LLD §`ProvisionForPlannerService` lines 469-478](../../lld/provisions.md), [LLD §`PlannerBundleController` row in the REST table — `GET /planner-bundle` line 514](../../lld/provisions.md). Read-only, no events, no migrations. The bundle aggregates **per the parent's per-module guidance**: active inventory items (from 01a — `InventoryItemRepository.findAllByUserIdAndItemStatus(userId, ACTIVE)`), staples needing replenishment (from 01a — `findAllByUserIdAndIsStapleTrueAndStatusIn`), available equipment (from 01b — `EquipmentRepository.findAllByUserIdAndAvailableTrue`), budget snapshot (from 01c — `BudgetRepository.findByUserId`; includes spend-tracking iff 01c shipped it; null otherwise), and frequently-used supplier-products (from 01d — top N by use; **N = 50** for v1 — see LLD divergence below). Plus `BundleStaleness` per LLD lines 314-318 (`supplierCacheCoverageBps` + `inRampUpWindow` + `generatedAt`).

**LLD divergence note** — **frequently-used supplier-products: top-N selection**:

LLD line 310 declares `supplierPricesByMappingKey: Map<String, SupplierProductDto>` — a key-value map of mapping-key → product. **The LLD doesn't pin "top N by use" semantics or the value of N**. Parent's per-module guidance says "top N by use." Choices:

- **Top N = 50** for v1. Sourced from existing `SupplierProductRepository.findAllByIngredientMappingKeyIn(...)` filtered on the user's recent shopping-list keys. **Actually**: 01d's `SupplierProduct` entity has no per-user "uses" counter — supplier products are global (LLD §`SupplierProduct` line 257). 01f's "top N" instead means: **for each `ingredient_mapping_key` appearing in the user's active inventory**, look up the user's preferred supplier product (most recently checked across all users — `lastCheckedAt DESC` from the repo's existing query surface).
- 01f's algorithm:
  1. Collect distinct `ingredientMappingKey` values across the user's active inventory rows (from step 1) + staples list (from step 2).
  2. Cap at 50 keys (arbitrary; tuned by profile later).
  3. Bulk-fetch supplier products via `supplierProductRepository.findAllByIngredientMappingKeyIn(keys)`.
  4. Group by `ingredientMappingKey`; pick the entry with the most recent `lastCheckedAt`.
  5. Build the map: `key -> SupplierProductDto`.
- **`supplierCacheCoverageBps`** (LLD line 315) = `(keys with at least one supplier product) / (keys requested) * 10000`. Integer 0..10000. **Default = 0** if no keys requested (empty inventory + no staples).

**Worth user review**: top-N = 50 + "most-recently-checked across users" is a v1 heuristic. A better signal would be "supplier-product the user actually orders" — but that requires the grocery-import flow (01h or later) to track per-user order history. The LLD's intent is "give the planner enough price signal to score provisions utilisation"; 01f's heuristic produces a non-empty map for any user with active inventory.

**LLD divergence note** — **`BudgetDto` null handling**:

LLD line 309 says `budget: BudgetDto`. **01c's `BudgetRepository.findByUserId` returns `Optional`** — users without a budget row return empty. **01f maps empty to `null` on the bundle DTO** with `nullable: true` on the OpenAPI schema. The LLD's record signature is `BudgetDto budget` (non-nullable in Java records) — but the LLD itself notes "Budget snapshot (from 01c, includes spend-tracking if 01c shipped it; null otherwise)" in the parent's per-module guidance. **01f changes `budget` to `@Nullable BudgetDto` on the record** — minor LLD divergence.

**LLD divergence note** — **`inRampUpWindow` calculation**:

LLD line 316 declares `inRampUpWindow: boolean` ("first 8 weeks per HLD"). The "first 8 weeks" anchors on the user's account creation. 01f doesn't have direct access to `User.createdAt` (that's in the auth module). **01f sources the anchor from the existing `HouseholdMember.createdAt` if the caller is in a household** (household-01a's `HouseholdMember` row carries `@CreatedDate`), **else `false`** if the caller has no household yet (defensive). Computed: `inRampUpWindow = (Instant.now() - membershipCreatedAt) < Duration.ofDays(56)`.

**Worth user review**: anchoring on household membership rather than user account creation drifts from the LLD's intent slightly. Alternative is to add a cross-module call to auth-01a's `UserQueryService.getUserById(userId).createdAt` — but auth doesn't expose this in its current query surface, and adding it is sibling-scope creep. 01f's fallback approximates correctly for users who created their household at sign-up (the common case). When auth-01a exposes user-creation date, the resolver picks it up.

**Cross-module read pattern — household.spi for `inRampUpWindow`**:

01f reads `HouseholdMember.createdAt`. **Direct repo access** violates module boundaries (household repos are package-private per its ArchUnit). 01f uses the existing public `HouseholdQueryService.getMembershipForUser(userId)` from household-01a — this returns `Optional<HouseholdMemberDto>` with a `createdAt` field. **Verify the DTO carries `createdAt`**; if not, **01f's resolver falls back to `inRampUpWindow = false`** (and adds a TODO comment for the future enhancement). **No new SPI required** — this is a read from an existing public interface.

**Defers** (still out of scope after 01f):

- The planner module itself — consumes 01f's bundle; not built yet.
- Caching the bundle (Spring `@Cacheable` with TTL keyed on `userId`) → operational concern; defer until profiling shows hotspot.
- Aggregating across multiple users (`getBundlesByUserIds`) — LLD line 474 lists this; **01f ships only the single-user form**. Multi-user bundle deferred to a follow-up if the planner needs it (currently no consumer).
- Real per-user supplier-product preferences — requires grocery-import history; defer until **provisions-01h**.
- Auth-01a exposing `User.createdAt` — sibling scope; out of scope for 01f.
- Cook events, grocery import, staples, batch-cook, expiry, feedback, pantry gate — **provisions-01g/h/i/j/k/l/m**.

01f unblocks the **planner module's provisions-utilisation sub-score**: per [meal-planner.md §scoring](../design/meal-planner.md#scoring-function) "Provisions utilisation incentive — score recipes higher when they use existing pantry/staples." Without 01f, the planner has to issue 4+ calls (inventory, equipment, budget, supplier-products) and reconstruct the bundle.

## Behavioural spec

### `ProvisionForPlannerService` — public interface

1. New public interface `com.example.mealprep.provisions.domain.service.ProvisionForPlannerService` per [LLD lines 472-475](../../lld/provisions.md):
   ```java
   public interface ProvisionForPlannerService {
     ProvisionForPlannerBundleDto getBundle(UUID userId);
     // List<ProvisionForPlannerBundleDto> getBundlesByUserIds(List<UUID> userIds);  // DEFERRED — see Defers above
   }
   ```
2. **Implemented by `ProvisionServiceImpl`** (existing class from 01a/01b/01c/01d/01e). The new method joins the existing impl per the LLD's single-impl convention (LLD line 378).
3. Returns `ProvisionForPlannerBundleDto`. No throwing — empty user state produces empty lists / null budget.

### `ProvisionForPlannerBundleDto` shape

4. New public record `com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto`:
   ```java
   public record ProvisionForPlannerBundleDto(
       UUID userId,
       List<InventoryItemDto> activeInventory,        // from 01a; non-null, may be empty
       List<InventoryItemDto> staplesAtLowOrOut,      // from 01a; non-null, may be empty
       List<EquipmentDto> equipment,                  // from 01b; available=true only; non-null
       BudgetDto budget,                              // from 01c; NULLABLE per LLD divergence
       Map<String, SupplierProductDto> supplierPricesByMappingKey,  // from 01d; non-null
       BundleStaleness staleness                      // see BundleStaleness shape below
   ) {}

   public record BundleStaleness(
       int supplierCacheCoverageBps,                  // 0..10000 inclusive
       boolean inRampUpWindow,
       Instant generatedAt
   ) {}
   ```
5. **Re-use** `InventoryItemDto` (01a), `EquipmentDto` (01b), `BudgetDto` (01c), `SupplierProductDto` (01d) verbatim. **Do NOT redefine.**

### `getBundle` flow

6. **Read-only**: `@Transactional(readOnly = true)`.
7. **Step 1 — active inventory**: `inventoryItemRepository.findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE)` → map via `inventoryItemMapper.toDtos(...)`.
8. **Step 2 — staples needing replenishment**: `inventoryItemRepository.findAllByUserIdAndIsStapleTrueAndStatusIn(userId, Set.of(StapleStatus.LOW, StapleStatus.OUT))` → map via `inventoryItemMapper.toDtos(...)`. **Verify the `StapleStatus` enum exists** in 01a/01b; if it doesn't, **fall back** to the lifecycle status `findAllByUserIdAndItemStatus` filter (the LLD's `is_staple` + `staple_status` exact shape lives in 01a/01b — verify before assuming).
9. **Step 3 — equipment**: `equipmentRepository.findAllByUserIdAndAvailableTrue(userId)` → map via `equipmentMapper.toDtos(...)`. **`available = true` only** per parent's per-module guidance.
10. **Step 4 — budget**: `budgetRepository.findByUserId(userId).map(budgetMapper::toDto).orElse(null)`. Null is legitimate; the planner handles missing budgets via "no budget gate."
11. **Step 5 — supplier-prices map**:
    - Collect `Set<String> keys = activeInventory.stream().map(InventoryItemDto::ingredientMappingKey).filter(Objects::nonNull).collect(toSet())` ∪ same from staples.
    - Cap `keys` at 50 (arbitrary; trim alphabetically for determinism).
    - If `keys.isEmpty()` → `supplierPricesByMappingKey = Map.of()`, `supplierCacheCoverageBps = 0`.
    - Else: `supplierProductRepository.findAllByIngredientMappingKeyIn(keys)` → group by `ingredientMappingKey` → pick `lastCheckedAt DESC` → first.
    - Build `Map<String, SupplierProductDto>` via `supplierProductMapper::toDto`.
    - `supplierCacheCoverageBps = round(map.size() * 10000 / keys.size())` (integer arithmetic — `BigDecimal` if precision matters).
12. **Step 6 — `inRampUpWindow`**: invoke `householdQueryService.getMembershipForUser(userId)`. If present and `createdAt` is non-null → `(Duration.between(membership.createdAt(), Instant.now()).toDays() < 56)`; else `false`.
13. **Step 7 — `generatedAt`**: `Instant.now()` at the end of resolution. Used by the planner for staleness checks if it caches.
14. **Build the DTO** per the shape above. Return.
15. **No persistence, no events**. Pure read.

### `GET /api/v1/provisions/planner-bundle`

16. New endpoint on a new controller `PlannerBundleController` under `provisions/api/controller/`. Authenticated (cookieAuth).
17. **Caller resolution**: `actorUserId = currentUserResolver.requireUserId()` (existing from auth-01a). The bundle is **always for the caller** — no `userId` path variable (matches LLD line 514 which has no path variable).
18. **No authorisation gate** (the user reads their own bundle).
19. Invokes `provisionForPlannerService.getBundle(actorUserId)`. Returns 200 with `ProvisionForPlannerBundleDto`.
20. Anonymous → 401 (existing `SessionAuthenticationFilter` from auth-01a rejects).

### Errors

21. **No new exceptions**. The flow is read-only and produces an empty-but-valid bundle for users with no state.
22. **No change** to `ProvisionsExceptionHandler` (whichever file exists from 01a). **DO NOT** modify `config/GlobalExceptionHandler.java`.

### Determinism

23. Same user state → byte-identical output (modulo `generatedAt`). Verified by a determinism test that calls the resolver twice and asserts equality on every field except `generatedAt`.
24. **Bounded N queries**: 5 SELECTs per request — 1 inventory, 1 staples, 1 equipment, 1 budget, 1 supplier-products (`findAllByIngredientMappingKeyIn` is a single IN-clause); +1 household-membership query for `inRampUpWindow`. Total **6 queries**. Verified via Hibernate Statistics in the IT.

### Cross-module facade

25. **Optional**: append `getBundle(UUID): ProvisionForPlannerBundleDto` to the `ProvisionsModule.PlannerService` facade if 01a follows the nested-class re-export pattern (same pattern as 01e — verify and skip if not). The `public` interface method is directly injectable either way.

## OpenAPI spec excerpt

### Append to `src/main/resources/openapi/paths/provisions.yaml`

(File extended by 01a/01b/01c/01d/01e — append one new path-item below 01e's most recent block. Do NOT touch existing path-items.)

```yaml
provisionsPlannerBundle:
  get:
    tags: [Provisions]
    operationId: getProvisionsPlannerBundle
    summary: 'Planner-friendly snapshot of the calling user''s inventory, equipment, budget, and supplier prices.'
    security: [{ cookieAuth: [] }]
    responses:
      '200':
        description: Planner-bundle snapshot.
        content:
          application/json:
            schema: { $ref: '../schemas/provisions.yaml#/ProvisionForPlannerBundleDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/provisions.yaml`

```yaml
ProvisionForPlannerBundleDto:
  type: object
  required: [userId, activeInventory, staplesAtLowOrOut, equipment, supplierPricesByMappingKey, staleness]
  properties:
    userId: { type: string, format: uuid }
    activeInventory:
      type: array
      items: { $ref: '#/InventoryItemDto' }
    staplesAtLowOrOut:
      type: array
      items: { $ref: '#/InventoryItemDto' }
    equipment:
      type: array
      items: { $ref: '#/EquipmentDto' }
    budget:
      type: object
      nullable: true
      description: 'Null when the user has no budget row; the planner falls back to no-gate.'
      properties:
        id: { type: string, format: uuid }
        userId: { type: string, format: uuid }
        weeklyTargetAmount: { type: number, format: double, minimum: 0 }
        currency: { type: string, minLength: 3, maxLength: 3 }
        spendTrackingEnabled: { type: boolean, nullable: true }
        optimisticVersion: { type: integer, format: int64 }
    supplierPricesByMappingKey:
      type: object
      additionalProperties: { $ref: '#/SupplierProductDto' }
    staleness:
      type: object
      required: [supplierCacheCoverageBps, inRampUpWindow, generatedAt]
      properties:
        supplierCacheCoverageBps:
          type: integer
          minimum: 0
          maximum: 10000
          description: 'Share of requested mapping keys with a fresh supplier-product entry (basis points).'
        inRampUpWindow: { type: boolean }
        generatedAt: { type: string, format: date-time }
```

**Gotcha applied**: `budget` is **inline** `nullable: true` with the full property block (NOT `$ref + nullable: true` — that's silently ignored by swagger-parser). The full `BudgetDto` shape is inlined here to dodge the trap. **Worth user review** — alternative is to leave the existing `BudgetDto` schema as-is (non-nullable) and have `budget` field always present but with all-null inner fields when empty. Rejected because that pushes a "this is the no-budget signal" convention onto the planner which is brittle.

**Gotcha applied**: every description string containing `,` `:` `'` is single-quoted per the round-4 lesson.

### Append to entry `src/main/resources/openapi/openapi.yaml`

```yaml
  /api/v1/provisions/planner-bundle:
    $ref: 'paths/provisions.yaml#/provisionsPlannerBundle'
```

Under `components.schemas:` in the `# provisions` block (alphabetical):

```yaml
    ProvisionForPlannerBundleDto: { $ref: 'schemas/provisions.yaml#/ProvisionForPlannerBundleDto' }
```

## Verbatim shape snippets

### Service-impl skeleton

```java
@Transactional(readOnly = true)
public ProvisionForPlannerBundleDto getBundle(UUID userId) {
  List<InventoryItem> activeRows = inventoryItemRepository
      .findAllByUserIdAndItemStatus(userId, ItemLifecycleStatus.ACTIVE);
  List<InventoryItem> staples = inventoryItemRepository
      .findAllByUserIdAndIsStapleTrueAndStatusIn(userId, Set.of(StapleStatus.LOW, StapleStatus.OUT));
  List<EquipmentItem> equipment = equipmentRepository.findAllByUserIdAndAvailableTrue(userId);
  BudgetDto budget = budgetRepository.findByUserId(userId).map(budgetMapper::toDto).orElse(null);

  Set<String> keys = Stream.concat(activeRows.stream(), staples.stream())
      .map(InventoryItem::getIngredientMappingKey)
      .filter(Objects::nonNull)
      .sorted()
      .limit(50)
      .collect(Collectors.toCollection(LinkedHashSet::new));

  Map<String, SupplierProductDto> prices = Map.of();
  int coverageBps = 0;
  if (!keys.isEmpty()) {
    List<SupplierProduct> sp = supplierProductRepository.findAllByIngredientMappingKeyIn(keys);
    prices = sp.stream()
        .collect(Collectors.toMap(SupplierProduct::getIngredientMappingKey, supplierProductMapper::toDto,
            (a, b) -> a.lastCheckedAt().isAfter(b.lastCheckedAt()) ? a : b));
    coverageBps = (prices.size() * 10000) / keys.size();
  }

  boolean inRampUp = householdQueryService.getMembershipForUser(userId)
      .map(HouseholdMemberDto::createdAt)
      .map(t -> Duration.between(t, Instant.now()).toDays() < 56)
      .orElse(false);

  return new ProvisionForPlannerBundleDto(
      userId,
      inventoryItemMapper.toDtos(activeRows),
      inventoryItemMapper.toDtos(staples),
      equipmentMapper.toDtos(equipment),
      budget,
      prices,
      new BundleStaleness(coverageBps, inRampUp, Instant.now()));
}
```

### Controller skeleton

```java
@RestController
@RequestMapping("/api/v1/provisions/planner-bundle")
@Tag(name = "Provisions")
public class PlannerBundleController {

  private final ProvisionForPlannerService plannerService;
  private final CurrentUserResolver currentUser;

  @GetMapping
  public ResponseEntity<ProvisionForPlannerBundleDto> get() {
    return ResponseEntity.ok(plannerService.getBundle(currentUser.requireUserId()));
  }
}
```

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/provisions/api/controller/PlannerBundleController.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/ProvisionForPlannerBundleDto.java
NEW   src/main/java/com/example/mealprep/provisions/api/dto/BundleStaleness.java
NEW   src/main/java/com/example/mealprep/provisions/domain/service/ProvisionForPlannerService.java

MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java     (implements ProvisionForPlannerService; constructor adds HouseholdQueryService dependency for membership lookup)
MOD   src/main/java/com/example/mealprep/provisions/ProvisionsModule.java                                  (optional — re-export PlannerService facade if pattern; skip otherwise)

MOD   src/main/resources/openapi/paths/provisions.yaml      (append 1 new path-item; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/provisions.yaml    (append `ProvisionForPlannerBundleDto`)
MOD   src/main/resources/openapi/openapi.yaml               (1 line under paths in `# provisions` block; 1 line under components.schemas in `# provisions` block)

NEW   src/test/java/com/example/mealprep/provisions/ProvisionForPlannerServiceTest.java     (mocked repos; empty user → empty bundle; full user → populated; budget null; coverageBps math; inRampUpWindow math)
NEW   src/test/java/com/example/mealprep/provisions/PlannerBundleFlowIT.java                (HTTP: authenticated → 200; anonymous → 401; full snapshot assertion; Hibernate stats ≤ 6 SQL)
MOD   src/test/java/com/example/mealprep/provisions/testdata/ProvisionsTestData.java        (append builders for ProvisionForPlannerBundleDto, BundleStaleness)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-6 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — no new exceptions.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — no new sub-packages.
- `src/main/java/com/example/mealprep/provisions/api/ProvisionsExceptionHandler.java` — no new `@ExceptionHandler` methods.
- The household module (any file) — **explicitly not modified**. 01f reads via the existing public `HouseholdQueryService.getMembershipForUser`.
- The nutrition, recipe, auth modules — not touched.
- 01a/01b/01c/01d/01e's existing tests — none modified; only `ProvisionsTestData.java` gets new builder methods.
- `ProvisionsBoundaryTest` — no new sub-packages required (DTOs in `api/dto/`, controller in `api/controller/`, service interface in `domain/service/`, impl method on existing `ProvisionServiceImpl`).

## Edge-case checklist

- [ ] `GET /planner-bundle` by authenticated user with full state → 200; populated `activeInventory`, `staplesAtLowOrOut`, `equipment`, `budget`, `supplierPricesByMappingKey`
- [ ] `GET /planner-bundle` by user with NO state → 200; all lists empty; `budget = null`; `supplierPricesByMappingKey = {}`; `staleness.supplierCacheCoverageBps = 0`; `staleness.inRampUpWindow = false`
- [ ] `GET /planner-bundle` by anonymous → 401
- [ ] User with active inventory but no staples → staples list empty; supplier-price keys derived from inventory only
- [ ] User with budget but spend-tracking disabled → `budget.spendTrackingEnabled = null` (or false); other budget fields populated
- [ ] Equipment `available = false` items NOT included
- [ ] `staplesAtLowOrOut` filter — only `StapleStatus.LOW` and `OUT` (not `OK`)
- [ ] `supplierCacheCoverageBps` math — 2 keys requested, 1 has a supplier product → `coverageBps = 5000` (50.00%)
- [ ] `supplierCacheCoverageBps = 0` when no keys requested (empty inventory + empty staples)
- [ ] `supplierPricesByMappingKey` keys are derived from inventory + staples union — no duplicates across the two sources
- [ ] Top-N cap = 50 — when user has 60+ distinct mapping keys, only 50 are queried (alphabetical determinism)
- [ ] `lastCheckedAt DESC` tie-break — two supplier products for the same key → newer wins
- [ ] `inRampUpWindow = true` when caller's household membership is < 56 days old
- [ ] `inRampUpWindow = false` when caller has no household membership
- [ ] `inRampUpWindow = false` when caller's household membership is > 56 days old
- [ ] `generatedAt` is recent (within 1s of `Instant.now()` in the IT)
- [ ] Determinism — same user state, two consecutive calls → byte-identical fields (modulo `generatedAt`)
- [ ] No N+1 — Hibernate stats assert ≤ 6 SQL statements per request
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in the IT)
- [ ] `ProvisionsBoundaryTest` (from 01a) still passes — no new sub-packages
- [ ] `ProvisionsExceptionHandler` unchanged — no new exceptions
- [ ] No new migrations
- [ ] No event published — pure read
- [ ] No regression on 01a/01b/01c/01d/01e tests
- [ ] No `pom.xml` dependency adds
- [ ] No nutrition / recipe / auth / preference / household-repo file touched (`HouseholdQueryService` is the public boundary — repo is package-private and untouched)

## Dependencies

- **Hard dependency**: `provisions-01a` (merged) — `InventoryItem`, `InventoryItemRepository`, `InventoryItemMapper`, `ItemLifecycleStatus` enum, `StapleStatus` enum (verify), `is_staple` column populated.
- **Hard dependency**: `provisions-01b` (merged) — `EquipmentItem`, `EquipmentRepository`, `EquipmentMapper`, `available` flag populated.
- **Hard dependency**: `provisions-01c` (merged) — `Budget`, `BudgetRepository`, `BudgetMapper`, `BudgetDto`. Spend-tracking field may or may not be shipped — 01f tolerates both.
- **Hard dependency**: `provisions-01d` (merged) — `SupplierProduct`, `SupplierProductRepository.findAllByIngredientMappingKeyIn`, `SupplierProductMapper`.
- **Hard dependency**: `provisions-01e` (merged) — waste log not used, pattern reuse only.
- **Hard dependency**: `household-01a` (merged) — `HouseholdQueryService.getMembershipForUser` returning `Optional<HouseholdMemberDto>` with `createdAt`.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **No cross-module SPI coupling** — 01f reads only via existing public service interfaces. **The recipe-01f ↔ nutrition-01f cross-SPI coupling does NOT involve provisions.**
- **Sibling tickets running in parallel** (Wave 2 round 6): `household-01f`, `nutrition-01f`, `recipe-01f`. None should touch any provisions file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# provisions` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` on `budget`, `spendTrackingEnabled` (NOT `$ref + nullable: true`)
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] No regression on 01a/01b/01c/01d/01e tests
- [ ] Exactly 6 queries per request (1 inventory, 1 staples, 1 equipment, 1 budget, 1 supplier-products, 1 household-membership)
- [ ] No pom.xml dependency adds
- [ ] No nutrition / recipe / auth / preference module file touched; no household-module file modified (household read goes through existing public `HouseholdQueryService`)

## What's NOT in scope

- The planner module — consumes 01f's bundle; not built yet.
- Multi-user bundle (`getBundlesByUserIds`) — LLD line 474 lists; deferred until planner needs it.
- Caching (`@Cacheable` with TTL) — operational concern; defer until profiling shows hotspot.
- Real per-user supplier-product preferences — requires grocery-import history; defer to **provisions-01h**.
- Auth-01a exposing `User.createdAt` — out of scope; 01f uses household-membership `createdAt` as proxy.
- Cook events, grocery import, staples upsert, batch-cook, expiry, feedback, pantry gate — **provisions-01g/h/i/j/k/l/m**.
- Schema-version on the bundle DTO — not persisted, rule doesn't apply.

Squash-merge with: `feat(provisions): 01f — ProvisionForPlannerService + ProvisionForPlannerBundleDto + GET /planner-bundle (planner-facing read)`
