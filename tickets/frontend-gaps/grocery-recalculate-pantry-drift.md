# Ticket: grocery — recalculate cannot pick up pantry drift within a plan generation (P2)

## Summary

`POST /shopping-lists/recalculate` is **idempotent on `(planId, planGeneration)`** — by design
(grocery-01b). Consequence: after pantry drift within the same generation (items spoiled, manual
inventory edits), the Recalculate button returns the cached list unchanged. The HLD says lists
"regenerate when the underlying plan **or provisions** change"; the provisions listener debounces
recalculate calls, but those also hit the same idempotency wall within a generation. Flagged by
[`design/frontend/pages/groceries.md` §3c + §8 Q2](../../design/frontend/pages/groceries.md).

**Fix:** add `force: boolean (default false)` to `RecalculateShoppingListRequest`. `force=true`
**rebuilds the list's lines in place** for the same `(planId, planGeneration)` — preserving the
row identity (and the `UNIQUE (plan_id, plan_generation)` constraint) rather than superseding into
a new row.

## Behavioural spec — the bought-mark preservation rule (the subtlety)

Re-running the six-step calculator yields fresh demand lines; the existing lines may carry
Tier-2/Tier-3 fulfilment state that must survive:

- Match old → new lines by normalised `ingredientMappingKey` + `lineType`.
- Matched line, old `fulfilmentStatus ∈ {BOUGHT, SUBSTITUTED, DROPPED}` → carry the fulfilment
  block (status, boughtQuantity/Unit/PricePence/At, boughtVia, groceryOrderId) onto the new line;
  quantities/pack suggestion/estimates refresh from the new calculation.
- Matched line, UNFILLED → fully refreshed.
- Old decided line with **no** new demand (drift removed the need) → keep the line with the new
  requested quantity 0 semantics? **No** — keep the decided line as-is (it records a real
  purchase); only UNFILLED lines without new demand are dropped.
- New demand with no old line → new UNFILLED line.
- `generatedAt` updates; `supersededAt` untouched; `version` bumps (optimistic lock).
- **Provisions listener:** the `onProvisionChanged` recalc (spoil/ran-out events) passes
  `force=true` — that listener exists precisely to react to drift; today its call is a no-op
  within a generation. The 5-second debounce stays.
- Plain `recalculate` (no force) unchanged — idempotent cache semantics preserved for the
  plan-generated path.

### OpenAPI excerpt

```yaml
# schemas/grocery.yaml — RecalculateShoppingListRequest
force:
  type: boolean
  default: false
  description: 'Rebuild lines for the existing (planId, planGeneration) list, picking up pantry/provisions drift. Decided lines are preserved by mapping key.'
```

## Edge-case checklist

- [ ] Spoil an inventory item → `force=true` recalc → its demand line quantity rises; bought lines untouched
- [ ] `force=false` (default) → cached list returned (behaviour today — no regression)
- [ ] Bought line whose ingredient left the plan → retained (purchase record), not resurrected as demand
- [ ] Pack-size/estimates refreshed on UNFILLED lines only where matched
- [ ] Concurrent force-recalc vs mark-bought → optimistic-lock 409 on one side, no lost fulfilment
- [ ] Listener path: ItemSpoiledEvent → debounced force recalc → list reflects drift without user action
- [ ] Export/history unaffected (same row, new content; `generatedAt` shows the refresh)

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java        (recalculate force path + line-carry merge)
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/ShoppingListRecalcListener.java (listener passes force)
MOD   src/main/resources/openapi/schemas/grocery.yaml                                                    (field)
MOD   src/test/java/com/example/mealprep/grocery/...                                                     (carry-rule matrix + listener IT)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; carry-rule matrix fully asserted
- [ ] Groceries "Recalculate" button (sending `force=true` per the page's confirm copy) actually re-derives

Squash-merge with: `feat(grocery): force recalculate rebuilds shopping-list lines within a generation (pantry drift)`

**Not in scope:** new-generation supersede flow (unchanged); cost band
([`grocery-cost-variance.md`](grocery-cost-variance.md)).
