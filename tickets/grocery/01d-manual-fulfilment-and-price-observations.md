# Ticket: grocery — 01d Manual Fulfilment (Tier 2) + Inventory Write Bridge + Custom Validators

## Summary

Implement **Tier 2**: mark-bought (single + bulk-with-total-spend distribution + undo), the
inventory write through the canonical provisions path, the per-line price observations, and the
Tier-2 events. Per [LLD §`ManualFulfilmentService` lines 576-587](../../lld/grocery.md),
[LLD §Flow 2 lines 875-885](../../lld/grocery.md),
[LLD §Flow 3 (bulk) lines 887-899](../../lld/grocery.md),
[LLD §Manual-fulfilment REST lines 701-705](../../lld/grocery.md),
[LLD §Validation lines 772-778](../../lld/grocery.md). Ships:

- **`ManualFulfilmentService` impl** — `markBought`, `bulkMarkBought` (proportional total-spend
  distribution), `undoMarkBought`.
- **`ManualFulfilmentController`** + the three endpoints (LLD lines 703-705).
- **Inventory write via `ProvisionUpdateService.applyGroceryOrder`** — the canonical, idempotent
  inventory-add path (built in provisions-01h).
- **Per-line `PriceObservation`s** via 01c's `PriceObservationWriter` (`MANUAL` weight 0.7,
  `MANUAL_ESTIMATED` weight 0.4 for distributed prices).
- **Tier-2 events** — `ShoppingListItemMarkedBoughtEvent`, `ShoppingListBulkMarkedBoughtEvent`,
  `PriceObservedEvent` (per observation).
- **The three custom validators** — `@ValidQuantityUnit`, `@ValidObservedPrice`,
  `@ValidOrderStatusTransition` (LLD lines 776-778) — 01a shipped permissive stubs; 01d ships the
  real `ConstraintValidator` bodies.

**Unblocks (E2E):** the manual-fulfilment leg is **already green** via provisions' direct
`POST /api/v1/provisions/grocery-import` (the `@smoke` scenario in
[`grocery.feature` lines 27-54](../../src/e2e/resources/features/grocery/grocery.feature) — GROC-08
/ the manual-only variant of GROC-36). **01d adds the grocery-side mark-bought surface** that drives
the same `applyGroceryOrder` write, so the shopping-list → mark-bought → inventory loop is callable
end-to-end through the grocery module (not just the provisions side-door). It does NOT flip a new
`@pending` tag — GROC-08's E2E already passes — but it completes the Tier-2 surface the pathway
catalogue describes (GROC-08/09/10/11/12) and is the manual arm of XJ-05.

## LLD-divergence notes

### `undoMarkBought` is HLD-silent but shipped (LLD line 587)

LLD line 587 adds `undoMarkBought` (not in the HLD) for the mistake-recovery / "user under-marks
then corrects" flow. **01d ships it** — it reverses the inventory add and the price observation.
**Worth user review** — the reversal semantics are the open question:
- **Inventory reversal:** `ProvisionUpdateService.applyGroceryOrder` is idempotent on `(userId,
  source, sourceRef)` (provisions-01h, keyed on the shopping-list-line-id). There is **no
  `reverseGroceryOrder` / un-apply** on the shipped `ProvisionUpdateService` — verify. If absent,
  undo cannot cleanly reverse the inventory row. **01d's stance:** undo reverses the **grocery-side**
  state (line back to `UNFILLED`, observation row marked superseded by a compensating note) and
  records a provisions-side adjustment **only if** a reverse API exists; if not, undo flips the line
  status + adds a warning that inventory must be corrected manually, and flags the missing
  `ProvisionUpdateService` reverse method as a **provisions follow-up**. Do NOT invent a provisions
  API in this ticket.
- **Price observation reversal:** observations are append-only (no delete) — undo writes a
  compensating note or a superseding observation, never deletes the row (mirrors the immutable
  price-history rule).

### `bought_quantity > requested_quantity` is allowed (over-mark, GROC-11)

LLD line 1061: over-marking is allowed — ad-hoc inventory entries are created and a warning is
surfaced via the result's `note`. 01d's `markBought` does NOT reject `boughtQuantity >
requestedQuantity`; it sets the `note` on `MarkBoughtResultDto` and proceeds (the inventory add
handles the ad-hoc quantity). GROC-11's off-list item is a provisions ad-hoc add — outside the
shopping-list-line path; 01d's mark-bought operates on existing lines, so the pure off-list case is
a provisions concern (noted, not owned here).

### Idempotency / double-mark (LLD line 879)

`markBought` rejects (or no-ops) a line already `BOUGHT`. **01d's chosen rule: 409
`IllegalStateException`-mapped** (the LLD says "no-op or 409 per chosen rule", line 1023) — a 409 is
the cleaner contract (the frontend learns the line was already bought and refreshes). Concurrent
mark-bought on the same line → `OptimisticLockException` on the parent `ShoppingList` `@Version` →
409 (LLD line 885) — the frontend retries with fresh state.

## Behavioural spec

### `markBought` (LLD lines 877-885) — `@Transactional`

1. Load the line (`ShoppingListLineNotFoundException` 404 if missing). Reject if `fulfilment_status
   == BOUGHT` → 409.
2. Update the line: `fulfilment_status = BOUGHT`, `bought_*` fields, `bought_via = MANUAL`.
   `boughtQuantity > requestedQuantity` allowed → set `note` on the result.
3. If `boughtPricePence` supplied → `PriceObservationWriter` writes a `MANUAL` row (weight from
   `GroceryConfig.confidenceWeights.manual`, default 0.7); store defaults to `"manual"`.
4. Inventory via `ProvisionUpdateService.applyGroceryOrder(userId, command)` — a single-line
   `GroceryOrderImportCommand` (provisions-01h shape: `supplier`, `orderRef`, `deliveredOn`, `lines`,
   `substitutions`, `traceId`). **`orderRef` = the shopping-list-line-id** so the idempotency key
   `(userId, source, sourceRef)` prevents double-adds on retry. `supplier` = the store (or
   `"manual"`). **Verify the `GroceryOrderImportCommand` field names against provisions-01h** (they
   are: `productId`, `name`, `ingredientMappingKey`, `quantity`, `unit`, `pricePaid`, `category`,
   `packSizeG`).
5. Publish `ShoppingListItemMarkedBoughtEvent`; if a price was captured, `PriceObservedEvent` (one
   per observation, LLD line 883).

### `bulkMarkBought` (LLD lines 889-899) — `@Transactional`

When `totalSpendPence` supplied:

1. Load all selected lines + their `estimated_unit_pence` aggregates (from 01c).
2. Distribution weight per line: `weight_i = estimated_line_pence_i / sum(estimated_line_pence)`.
   Lines with no estimate get a **uniform fallback share of the residual** (the GG7 / GROC-10
   no-anchor case — LLD line 892 specifies the uniform-fallback rule; **worth user review** as the
   pathway flags it as an HLD-gap, but the LLD resolves it).
3. `bought_price_pence_i = round(totalSpendPence × weight_i)`; reconcile rounding by dunning the
   last (largest-total) line for the residual (so the parts sum exactly to the total).
4. One `PriceObservation` per line, `source = MANUAL_ESTIMATED` (weight 0.4 — the HLD's discount on
   distributed prices, LLD line 894).
5. **Single** `applyGroceryOrder` call with all lines (one provisions event, one grocery event).

When `totalSpendPence` is null: each line uses its own `estimated_unit_pence` if known, else writes
no observation (same as a per-line mark-bought without a price).

After commit: **one** `ShoppingListBulkMarkedBoughtEvent` (NOT per-line `MarkedBoughtEvent`s — LLD
line 899, "one event per user-initiated operation").

### `undoMarkBought(shoppingListLineId, actorUserId)` — `@Transactional`

Per the divergence note: reverse the grocery-side line state to `UNFILLED`, write a compensating
price note (never delete the observation), and reverse inventory **only if** a provisions reverse API
exists (else warn + flag). 404 if the line is missing; 409 if the line is not currently `BOUGHT`.

### Custom validators (LLD lines 776-778)

- `@ValidQuantityUnit` — non-negative, scale ≤ 3, magnitude ≤ 1,000,000; unit ∈ `{g, kg, ml, l,
  items, pt, tsp, tbsp, cup}`.
- `@ValidObservedPrice` — non-negative integer pence, ≤ 1,000,000 (catches a £/p mix-up).
- `@ValidOrderStatusTransition` — class-level on internal command records; asserts `from → to` is a
  legal edge per `OrderStateMachine` (the state machine itself ships in 01e — **01d ships the
  annotation + a validator that delegates to `OrderStateMachine` once 01e lands; until then the
  validator is permissive**, OR sequence 01e before 01d so the validator is complete. **Worth user
  review / sequencing note** — `@ValidOrderStatusTransition` logically belongs with 01e's state
  machine; 01d ships the two Tier-2 validators fully and either stubs the transition one or defers it
  to 01e).

### `ManualFulfilmentController` (LLD lines 703-705)

Under `/api/v1/grocery/shopping-lists/{listId}/lines`: `POST …/{lineId}/mark-bought`
(200/400/404/409), `POST …/bulk-mark-bought` (200/400/404), `POST …/{lineId}/undo-mark-bought`
(204/404/409). `@Tag(name = "Grocery — Manual Fulfilment")`.

## Verbatim shape snippets

### `applyGroceryOrder` command assembly (single-line mark-bought)

```java
var command = new GroceryOrderImportCommand(
    store != null ? store : "manual",                 // supplier
    line.getId().toString(),                           // orderRef == line id → idempotency key
    LocalDate.now(clock),                              // deliveredOn
    List.of(new GroceryOrderLine(
        /*productId*/ "manual:" + line.getIngredientMappingKey(),
        /*name*/ line.getDisplayName(),
        /*ingredientMappingKey*/ line.getIngredientMappingKey(),  // already normalised by core-03
        /*quantity*/ request.boughtQuantity(),
        /*unit*/ request.boughtUnit(),
        /*pricePaid*/ pricePounds(request.boughtPricePence()),
        /*category*/ null,
        /*packSizeG*/ line.getSuggestedPackSizeG())),
    /*substitutions*/ List.of(),
    /*traceId*/ traceId);
provisionUpdateService.applyGroceryOrder(userId, command);
```

(Verify the `pricePaid` type — provisions-01h uses `BigDecimal` pounds; grocery stores integer
pence. Convert at the boundary and document the rounding.)

## Edge-case checklist

- [ ] Single mark-bought, price supplied → line `BOUGHT`, one `MANUAL` observation, one inventory add, two events
- [ ] Single mark-bought, no price → line `BOUGHT`, NO observation, inventory add, `MarkedBoughtEvent` only
- [ ] First-ever purchase (no last-known price) → no default price; user can still mark bought (GROC-08 variation)
- [ ] `boughtQuantity > requestedQuantity` (over-mark) → allowed, `note` set on result (GROC-11)
- [ ] Re-mark an already-`BOUGHT` line → 409
- [ ] Concurrent mark-bought same line → `OptimisticLockException` → 409 (parent `@Version`)
- [ ] Inventory idempotency: retry with same line-id `orderRef` → provisions no-ops (no double add)
- [ ] Bulk with total spend, all lines have estimates → proportional split, parts sum to total (residual dunned to last line)
- [ ] Bulk with total spend, some lines no anchor → uniform fallback share of residual (GG7)
- [ ] Bulk without total → per-line estimate or no observation
- [ ] Bulk → ONE `ShoppingListBulkMarkedBoughtEvent`, NOT per-line events
- [ ] Bulk → single `applyGroceryOrder` call (one provisions event)
- [ ] All distributed observations are `MANUAL_ESTIMATED` (0.4), single marks are `MANUAL` (0.7)
- [ ] `undoMarkBought`: line → `UNFILLED`; observation compensated (not deleted); inventory reverse only if provisions API exists (else warn + flag follow-up)
- [ ] `undoMarkBought` on a non-`BOUGHT` line → 409; missing line → 404
- [ ] `@ValidQuantityUnit` rejects negative / scale>3 / bad unit; `@ValidObservedPrice` rejects negative / >1,000,000
- [ ] Controller: 200/204/400/404/409; `userId` server-side; OpenAPI shapes match

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/grocery/api/controller/ManualFulfilmentController.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/MarkBoughtInventoryBridge.java   (assembles GroceryOrderImportCommand)
NEW   src/main/java/com/example/mealprep/grocery/event/ShoppingListItemMarkedBoughtEvent.java
NEW   src/main/java/com/example/mealprep/grocery/event/ShoppingListBulkMarkedBoughtEvent.java
MOD   src/main/java/com/example/mealprep/grocery/validation/ValidQuantityUnit*.java                       (real validator body)
MOD   src/main/java/com/example/mealprep/grocery/validation/ValidObservedPrice*.java                      (real validator body)
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java          (fill ManualFulfilmentService bodies)
MOD   src/main/resources/openapi/paths/grocery.yaml + schemas/grocery.yaml + openapi.yaml
NEW   src/test/java/com/example/mealprep/grocery/ManualFulfilmentServiceTest.java
NEW   src/test/java/com/example/mealprep/grocery/ManualFulfilmentControllerIT.java
NEW   src/test/java/com/example/mealprep/grocery/ProvisionsIntegrationIT.java                             (applyGroceryOrder command shape + idempotent re-call)
NEW   src/test/java/com/example/mealprep/grocery/validation/QuantityUnitValidatorTest.java + ObservedPriceValidatorTest.java
MOD   src/test/java/com/example/mealprep/grocery/testdata/GroceryTestData.java
```

**Does NOT touch:** provisions production code (calls `ProvisionUpdateService.applyGroceryOrder`
only); `config/GlobalExceptionHandler.java`. If `undoMarkBought` needs a provisions reverse API, that
is a **separate provisions follow-up ticket**, not written here.

## Dependencies

- **Hard:** grocery-01a (entities, interfaces, validator annotation stubs); grocery-01b (shopping
  lists + lines exist to mark); grocery-01c (`PriceObservationWriter`, aggregates for bulk
  distribution); `tickets/core/03` (normalised keys).
- **Hard (cross-module):** provisions-01h `ProvisionUpdateService.applyGroceryOrder` +
  `GroceryOrderImportCommand` (the canonical inventory write — already built).
- **Soft:** grocery-01e — `OrderStateMachine` for `@ValidOrderStatusTransition` (sequence 01e first
  or stub the transition validator).

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] `ProvisionsIntegrationIT` confirms the `applyGroceryOrder` command shape + idempotent re-call no-op
- [ ] Bulk distribution parts sum exactly to `totalSpendPence`; single events per operation
- [ ] `undoMarkBought` does not delete append-only observations; flags any missing provisions reverse API
- [ ] `GroceryBoundaryTest` passes; no provisions production file touched

Squash-merge with: `feat(grocery): 01d — Tier 2 manual fulfilment (mark-bought / bulk / undo) + inventory write bridge + validators`

## What's NOT in scope

- The provisions inventory-reverse API for `undoMarkBought` → **provisions follow-up** if absent.
- Pure off-list ad-hoc adds (GROC-11 off-list item) → provisions ad-hoc add path (mark-bought
  operates on existing lines only).
- `@ValidOrderStatusTransition` full body → **grocery-01e** (ships with `OrderStateMachine`).
- The `recordManualPrice` REST entry → shipped in **grocery-01c** (Tier 4's controller).
