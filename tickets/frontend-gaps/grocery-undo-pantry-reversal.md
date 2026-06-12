# Ticket: grocery — undo-mark-bought should reverse the pantry add (P2)

## Summary

`undoMarkBought` reverses the grocery-side state (line back to UNFILLED, compensating price note)
but **leaves the inventory add in place** — the service logs it explicitly
([`GroceryServiceImpl.java` ~line 511](../../src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java):
"reversed grocery-side state … but the inventory add via [provisions] is corrected manually").
The UI must carry an awkward caveat ("the pantry item is not removed automatically — correct it
in /pantry"), and the natural user expectation is a compensating removal. Flagged by
[`design/frontend/pages/groceries.md` §4c + §8 Q4](../../design/frontend/pages/groceries.md).

**Fix:** best-effort compensating reversal. Mark-bought already records the created
`inventoryItemId` in its result; persist that link on the line (if not already) and on undo:

- Item exists, quantity ≥ the added amount → decrement by the added amount (or soft-delete the
  item if the add created it and it is otherwise untouched).
- Item partially consumed since → decrement what remains, floor at zero (never negative —
  provisions' standing guardrail).
- Item gone (spoiled/exhausted/deleted) → no-op.
- Every branch writes the provisions audit trail (actor `GROCERY_IMPORT`-family — verify the
  right actor enum; the reversal must be visible in the item history).

The undo response (currently 204) gains nothing — keep 204; the reversal outcome is observable in
the pantry audit log. Provisions exposes the reversal via its existing public service surface
(new method `reverseGroceryLineAdd(inventoryItemId, quantity, unit, actorUserId)` or equivalent) —
grocery never touches provisions internals.

## Edge-case checklist

- [ ] One-tap mark-bought (pantry add) → undo → item quantity back exactly (or item removed if creation-only)
- [ ] Item partially consumed by a cook-event between mark and undo → floored at zero, audit row says so
- [ ] Item spoiled/removed since → undo still 204; grocery side reverses; no error
- [ ] Undo on a line whose mark-bought wrote **no** inventory (pantry tracking off) → unchanged behaviour
- [ ] Order-fulfilled lines remain non-undoable (unchanged)
- [ ] Module boundary: grocery → provisions public API only (boundary tests pass)

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java   (undo path calls the reversal; drop the log-only caveat)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/...                             (public reversal method + impl + audit)
MOD   src/main/resources/openapi/paths/grocery.yaml                                                (undo description: compensating reversal semantics)
MOD   src/test/java/com/example/mealprep/grocery/... + provisions/...                              (branch matrix above)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all branches asserted
- [ ] Groceries §4c confirm copy can drop the "correct it in /pantry" caveat

Squash-merge with: `feat(grocery): undo-mark-bought reverses the pantry add (best-effort, floored, audited)`

**Not in scope:** undoing order-fulfilled lines; reversing price observations beyond the existing
compensating note; provisions inventory UI.
