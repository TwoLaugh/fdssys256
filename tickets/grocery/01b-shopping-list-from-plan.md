# Ticket: grocery — 01b Shopping List Calculation (Tier 1) + Read/Export Endpoints

## Summary

Implement **Tier 1**: the deterministic six-step shopping-list calculator, the
`ShoppingListService` bodies, the `ShoppingListController`, and the `PlanGeneratedEvent` /
`ProvisionChangedEvent` listeners that trigger recalculation. Per
[LLD §`ShoppingListService` lines 553-572](../../lld/grocery.md),
[LLD §Flow 1 lines 858-873](../../lld/grocery.md),
[LLD §Flow 7 lines 955-957](../../lld/grocery.md),
[LLD §Events §Consumed lines 841-852](../../lld/grocery.md),
[LLD §Shopping list REST lines 689-697](../../lld/grocery.md). Ships:

- **`ShoppingListCalculator`** (LLD line 44) — the six-step pipeline (`calculate`): aggregate
  planned demand → subtract inventory (gated on `pantryTrackingEnabled`) → pack-size heuristics →
  add staples → quality notes → cost projection.
- **`PackSizeHeuristic` lookup + `PackSizeOptimiser`** (LLD lines 46-47, 864) — smallest-pack
  combination meeting demand; key-match wins over category-match; perishables prefer smaller-up.
- **`ShoppingListService` impl** — `recalculate` (idempotent on `(planId, planGeneration)`),
  `getCurrentByPlanId`, `getById`, `getByIds`, `getHistory`, `export`.
- **`ShoppingListController`** + the five endpoints (LLD lines 691-697).
- **`ShoppingListMapper`** (+ `ShoppingListLineMapper`).
- **`onPlanGenerated` / `onProvisionChanged` listeners** (LLD lines 843-852) with the **5-second
  `(userId, planId)` debounce** on inventory drift.
- **`ShoppingListGeneratedEvent`** published after commit (LLD line 873).
- **`ShoppingListExporter`** — `PRINTABLE_HTML | PLAIN_TEXT | MARKDOWN | CSV` (LLD lines 408-409).

**Unblocks (E2E):** **GROC-01** ("A user views the shopping list for the active plan" —
`@pending` in `grocery.feature` lines 58-67). Also the read leg of **XJ-05** (plan → shopping
list). The cost-projection step (step 6) consumes `PriceHistoryService.getAggregate` from 01c —
land 01c first, or ship step 6 to tolerate a not-yet-implemented aggregate (return null totals).

## Decision baked in — grocery owns the CALCULATION, planner owns the DATA (resolves GG1)

[`e2e/pathways/grocery.md` GG1 line 547](../../e2e/pathways/grocery.md) and XJ-05 X12 flag the
**shopping-list ownership contradiction** — `grocery.md` says grocery "exposes" the list while
`provision-model.md` says the planner "owns the calculation." **Product-owner decision (settled):
grocery owns the calculation logic; the planner owns the plan data.** The calculator reads plan
data through `PlanQueryService` and computes the list itself. This is the LLD's stance
([LLD line 18](../../lld/grocery.md): "read-only against Plan via planner") made explicit, and it
matches the shipped reality (the planner ships NO shopping-list endpoint;
[`grocery.feature` lines 60-63](../../src/e2e/resources/features/grocery/grocery.feature) record
that neither module exposes a "GET shopping list" today). **This ticket lands that endpoint on the
grocery side.**

## LLD-divergence notes

### `getPlanForGrocery` does NOT exist on the planner — use `getPlanById` + the existing read API

LLD line 862 has the calculator walk recipes via `PlannerQueryService.getPlanForGrocery(planId,
planRevision)` — "a bundled DTO defined in the planner LLD." **No such method exists.** The shipped
planner surface (planner-01a/01c) is `PlanQueryService` with `getPlanById(UUID)`, `getActivePlan`,
`getPlanHistory`, `getPlansByIds`, etc., returning `PlanDto` (nested days → slots →
scheduled-recipes). **Also note** [`lld/planner.md` line 1180](../../lld/planner.md) lists
`GroceryQueryService.getPriceConfidence` as "(planned)" — the dependency is the OTHER direction
(planner reads grocery), confirming no grocery-facing planner bundle ships today.

**01b's decision:** the calculator walks `PlanQueryService.getPlanById(planId)` →
`PlanDto.days[].slots[].scheduledRecipe`, then for each scheduled recipe reads its ingredient list
via `RecipeQueryService` (the recipe ingredients are NOT inlined in `PlanDto` — verify the
`ScheduledRecipeDto` shape; if it carries only `recipeId` + `versionId`, the calculator batch-loads
recipe versions via `RecipeQueryService.getByIds`/equivalent). **Worth user review — the
`getPlanForGrocery` bundle dependency:** if the per-recipe ingredient walk over `getPlanById` proves
too chatty (one plan = up to 21 slots, each a recipe-version fetch), add a **new
`PlanQueryService.getPlanForGrocery(planId)` bundle method on the planner** that returns
`(planId, generation, List<{mappingKey, quantity, unit, cookedServings, qualityHints}>)` in one
round trip. **This is a planner-side follow-up** — do NOT write it speculatively; 01b ships the
`getPlanById` walk first and flags the bundle as the optimisation if Hibernate-stats show > 5 SQL
statements. The LLD's "≤ 5 SQL statements end-to-end" target (line 871) is the trip-wire.

### `plan_revision` → `plan_generation`

Per 01a's divergence note. `recalculate` keys on `(planId, planGeneration)`; `planGeneration` ==
`PlanDto.generation`. `RecalculateShoppingListRequest.planGeneration` (nullable → latest active).

### `cooked-servings per slot` source

LLD step 1 (line 862) multiplies ingredient quantity by "cooked-servings per slot." Verify the
`PlanDto` slot shape exposes a servings/headcount field; if it's on the plan rollup or derived from
household headcount, read it from there. **Worth user review** — the exact servings field is a
`PlanDto`-shape dependency to confirm at build time; XJ-04 X10 already flags "headcount semantics
for portion scaling … unspecified."

### `normaliseKey()` is `IngredientMappingKeys.normalise()` (from core-03), not nutrition

LLD line 862 sums demand "by `ingredient_mapping_key` using the nutrition module's `normaliseKey()`
utility." The nutrition util is the **package-private** `IntakeKeyNormaliser.normalise` in
`nutrition.domain.service.internal` — not cross-module accessible. **01b uses the shared
`core.IngredientMappingKeys.normalise()`** shipped by [`tickets/core/03`](../core/03-ingredient-mapping-key-normalisation.md).
Hard dependency: core-03 lands first. Every key the calculator reads (plan ingredient keys, inventory
keys, staple keys) is normalised before aggregation so they bucket correctly.

## Behavioural spec

### `ShoppingListCalculator.calculate` — the six steps (LLD lines 860-867)

`@Transactional` (invoked from `recalculate` and from the listeners' own transaction).

1. **Aggregate planned demand.** Walk the plan (see divergence note), multiply ingredient qty by
   cooked-servings per slot, sum by `IngredientMappingKeys.normalise(key)` → `Map<String,
   IngredientDemand>`.
2. **Subtract inventory.** Read `pantry_tracking_enabled` from
   `PreferenceQueryService.getLifestyleConfig(userId).document().pantryTracking().enabled()` (verify
   the exact accessor against the shipped preference surface). `false` → skip (inventory = empty,
   fuller list — GROC-01 variation). `true` → `ProvisionQueryService.getActiveInventory(userId)`,
   group by normalised key, subtract. **Underflow → zero, never negative.**
3. **Pack-size heuristics.** Per non-zero remaining: `PackSizeHeuristic` lookup by mapping key, then
   category fallback. `PackSizeOptimiser.choose(remaining, availablePacks)` → `(pack_size_g,
   pack_count)`. Greedy largest-down for non-perishables; smallest-up for perishables.
4. **Add staples.** `ProvisionQueryService.getStaplesNeedingReplenishment(userId)` (LOW/OUT) →
   `STAPLE_REPLENISHMENT` lines with default heuristic pack. (Verify the exact staple query method
   name on the shipped `ProvisionQueryService`.)
5. **Quality preferences.** Read lifestyle config `groceryQualityPreferences` → each line's
   `quality_notes` (informational hint; provider-side SKU matching reads them in Tier 3).
6. **Cost projection.** `PriceHistoryService.getAggregatesByKeys(householdId, allKeys)` (ONE batched
   query — LLD line 871). Per line: `estimated_unit_pence × pack_count = estimated_line_pence`; total
   = sum; `cost_confidence` = cost-weighted mean of line confidences. Lines without an aggregate →
   null estimate + `stale_ingredient_count++`. **With no aggregates at all → null totals; the list
   still renders** (the GROC-01 cold-start variation + GROC-02 edge).

### `recalculate` (LLD lines 562-565, 869)

`@Transactional`. **Idempotent on `(planId, planGeneration)`** — same generation returns the existing
row; a new generation creates a new list and supersedes the prior (`superseded_at = now()`).
Concurrent same-generation calls are serialised by the DB `UNIQUE (plan_id, plan_generation)` — the
second insert fails (`DataIntegrityViolationException`), caught and re-fetched. After commit publishes
`ShoppingListGeneratedEvent` (LLD line 827 shape). **`householdId`** resolved via the household
module (or null in single-user mode per LLD line 94).

### Read methods + export

- `getCurrentByPlanId(planId)` → `findFirstByPlanIdAndSupersededAtIsNullOrderByPlanGenerationDesc`.
- `getById` / `getByIds` (batch) / `getHistory(userId, pageable)` → standard reads, `@EntityGraph`
  parent+lines (no N+1).
- `export(shoppingListId, format)` → `ShoppingListExporter` renders `PRINTABLE_HTML | PLAIN_TEXT |
  MARKDOWN | CSV`. Pure transformation, no side effects. **PDF is NOT server-rendered** (LLD line 572
  / 1079) — `PRINTABLE_HTML` is the print-to-PDF surface (frontend concern). **Worth user review** —
  server-side PDF is a future enhancement; email/share is the same text handed to a `mailto:`/share
  sheet (frontend). Exporting an empty/staples-only list renders an empty/staples-only document
  (GG3 / GROC-04 edge — 01b renders it, doesn't error).

### Listeners (LLD lines 843-852)

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
@Transactional(propagation = REQUIRES_NEW)
public void onPlanGenerated(PlanGeneratedEvent event) { recalculate(event.userId/householdId, ...); }

@TransactionalEventListener(phase = AFTER_COMMIT)
@Transactional(propagation = REQUIRES_NEW)
public void onProvisionChanged(ProvisionChangedEvent event) { /* 5s debounce per (userId, planId) */ }
```

`PlanGeneratedEvent` → recalculate for the new plan + generation (verify the event's exact payload
fields against planner-01k's published shape — `planId`, `householdId`, `generation`).
`ProvisionChangedEvent` is **sealed** (provisions); pattern-match sub-kinds: `ItemAddedFromGroceryEvent`
+ `SubstitutionAcceptedEvent` need no list change (Tier 3 already wrote them); `ItemSpoiledEvent` +
`ItemRanOutEvent` may invalidate the active list → recalculate. **5-second debounce per `(userId,
planId)`** (LLD line 852) collapses a single delivery's batched item updates into one recalculate.
**Worth user review — debounce mechanism:** the LLD says "5-second debounce" but not how; 01b
implements it via a `@Scheduled`-drained `ConcurrentHashMap<DebounceKey, Instant>` coalescing
window, OR reuses an existing project debounce helper if one exists (verify — the provisions module's
`ProvisionEventBatcher` is a per-transaction batcher, NOT a cross-event time-window debounce, so a new
mechanism is likely needed). The `REQUIRES_NEW` propagation alongside `AFTER_COMMIT` is the round-7
listener rule (the listener body does JPA work).

### `ShoppingListController` (LLD lines 691-697)

The five endpoints under `/api/v1/grocery/shopping-lists`. `userId` resolved server-side.
`GET /current?planId=` → 200/404. `POST /recalculate` → 200/400/404. `GET /{id}/export?format=` →
200/404. OpenAPI `@Tag(name = "Grocery — Shopping List")`.

## Verbatim shape snippets

### `PackSizeOptimiser` (LLD line 864 + test line 1015)

```java
@Component
class PackSizeOptimiser {
  /** Smallest combination of packs that meets {@code remaining}. Key-match packs win over
   *  category packs (caller pre-sorts). Non-perishable: greedy largest-down; perishable: smallest-up. */
  PackChoice choose(IngredientDemand remaining, List<PackSizeHeuristic> packs, boolean perishable) { ... }
}
```

Test fixtures (LLD line 1015): 750g flour with 500g/1kg packs → 1×1kg; 1.5kg flour → 1×1.5kg over
3×500g; perishables prefer smaller-up; ingredient-key match beats category match.

### `recalculate` idempotency (LLD line 869)

```java
@Override @Transactional
public ShoppingListDto recalculate(UUID userId, RecalculateShoppingListRequest request) {
  int generation = request.planGeneration() != null ? request.planGeneration()
      : planQueryService.getPlanById(request.planId()).orElseThrow(...).generation();
  Optional<ShoppingList> existing = repo.findByPlanIdAndPlanGeneration(request.planId(), generation);
  if (existing.isPresent()) return mapper.toDto(existing.get());   // idempotent
  try {
    ShoppingList list = calculator.calculate(userId, request.planId(), generation);
    supersedePrior(request.planId(), generation);                  // superseded_at = now() on the prior active
    ShoppingList saved = repo.saveAndFlush(list);
    publishAfterCommit(new ShoppingListGeneratedEvent(...));
    return mapper.toDto(saved);
  } catch (DataIntegrityViolationException race) {                 // UNIQUE(plan_id, plan_generation) lost the race
    return mapper.toDto(repo.findByPlanIdAndPlanGeneration(request.planId(), generation).orElseThrow());
  }
}
```

## Edge-case checklist

- [ ] Demand aggregated by NORMALISED key — "Chicken Breast" and "chicken breast" bucket together
- [ ] `pantryTrackingEnabled = false` → inventory skipped, fuller list (GROC-01 variation)
- [ ] `pantryTrackingEnabled = true` → inventory subtracted; underflow clamps to zero (never negative)
- [ ] Plan with all demand met by inventory → staples-only or empty list (GROC-01 variation; renders, no error)
- [ ] No plan / no active generation → `RecalculateShoppingListRequest` 404 (GROC-02; the GG2 staples-only edge is **worth user review** — 01b's stance: a recalculate REQUIRES a plan, so no-plan → 404; the standalone staples list is a v2 consideration)
- [ ] Pack-size: key-match wins over category-match; perishable smaller-up; non-perishable largest-down
- [ ] Staples LOW/OUT appended as `STAPLE_REPLENISHMENT`
- [ ] Quality notes propagated to `quality_notes` (free-range/organic/own-brand)
- [ ] Cost projection: all aggregates present → totals + confidence; mixed → partial + `stale_ingredient_count`; none → null totals, list renders
- [ ] Idempotent: same `(planId, planGeneration)` returns existing row, no new row
- [ ] New generation → new list + prior `superseded_at` set
- [ ] Concurrent same-generation recalculate → second catches `DataIntegrityViolationException`, re-fetches
- [ ] `ShoppingListGeneratedEvent` published exactly once after commit
- [ ] `onPlanGenerated` triggers recalculate; `onProvisionChanged` debounces 15 item-updates → 1 recalculate
- [ ] `ItemAddedFromGroceryEvent` / `SubstitutionAcceptedEvent` do NOT trigger a recalculate (Tier 3 already wrote them)
- [ ] Export: each format renders; empty list renders empty doc; PDF NOT server-rendered (PRINTABLE_HTML only)
- [ ] `ShoppingListCalculatorIT`: ≤ 5 SQL statements per recalculate (Hibernate stats) — else flag the `getPlanForGrocery` bundle
- [ ] Controller: 200/400/404; `userId` server-side; OpenAPI shapes match (swagger-request-validator)

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/ShoppingListCalculator.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/PackSizeHeuristicLookup.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/PackSizeOptimiser.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/ShoppingListExporter.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/ShoppingListRecalcListener.java   (onPlanGenerated + onProvisionChanged + debounce)
NEW   src/main/java/com/example/mealprep/grocery/api/controller/ShoppingListController.java
NEW   src/main/java/com/example/mealprep/grocery/api/mapper/ShoppingListMapper.java + ShoppingListLineMapper.java
NEW   src/main/java/com/example/mealprep/grocery/event/ShoppingListGeneratedEvent.java
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java   (fill the four ShoppingListService bodies)
MOD   src/main/resources/openapi/paths/grocery.yaml + schemas/grocery.yaml + openapi.yaml          (shopping-list endpoints + DTOs)
NEW   src/test/java/com/example/mealprep/grocery/ShoppingListCalculatorTest.java
NEW   src/test/java/com/example/mealprep/grocery/PackSizeOptimiserTest.java
NEW   src/test/java/com/example/mealprep/grocery/ShoppingListServiceImplTest.java
NEW   src/test/java/com/example/mealprep/grocery/ShoppingListControllerIT.java
NEW   src/test/java/com/example/mealprep/grocery/ShoppingListCalculatorIT.java                      (real DB; ≤5 SQL stmts)
MOD   src/test/java/com/example/mealprep/grocery/testdata/GroceryTestData.java
```

**Does NOT touch:** planner / provisions / preference / recipe production code (reads via their
public query services only). If the `getPlanForGrocery` bundle is needed, it's a **separate
planner-side ticket**, not part of 01b.

## Dependencies

- **Hard:** grocery-01a (entities, repos, interfaces, config); `tickets/core/03`
  (`IngredientMappingKeys.normalise()`).
- **Hard (read APIs):** `PlanQueryService.getPlanById` (planner-01a/01c), `ProvisionQueryService`
  active-inventory + staples reads (provisions), `PreferenceQueryService` lifestyle config
  (preference), `RecipeQueryService` ingredient reads (recipe).
- **Soft:** grocery-01c — `PriceHistoryService.getAggregatesByKeys` for step 6. Land 01c first, or
  ship step 6 tolerating the `UnsupportedOperationException` skeleton (catch → null totals) and
  remove the catch once 01c lands.
- **Event publishers:** `PlanGeneratedEvent` (planner-01k), `ProvisionChangedEvent` sealed (provisions).

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] `ShoppingListCalculatorIT` confirms ≤ 5 SQL statements (else report the bundle recommendation)
- [ ] GROC-01 un-pendable: a `GET /current?planId=` returns the unmet-ingredient lines for the user
- [ ] Listeners use `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`
- [ ] No N+1; `GroceryBoundaryTest` passes; no other-module production file touched

Squash-merge with: `feat(grocery): 01b — Tier 1 shopping-list calculation + read/export endpoints + recalc listeners`

## What's NOT in scope

- Tier 4 aggregate maths → grocery-01c (01b only CONSUMES `getAggregatesByKeys`).
- The `PlanQueryService.getPlanForGrocery` bundle → separate planner ticket if the chatty walk fails the ≤5-SQL gate.
- Server-side PDF rendering → future enhancement (PRINTABLE_HTML is the v1 surface).
- Cross-recipe pack-size consolidation (LLD line 1084) → v2 / a planner objective.
- Surfacing the recipe context that demanded each line (LLD line 1089) → v2 UX enrichment.
- Over-budget warning + swap suggestions (GROC-07) → the planner owns swaps; 01b carries
  `cost_confidence`/`estimated_total_pence` in the DTO for the planner to read (LLD line 1060).
