# Ticket: grocery — cost-variance band on `ShoppingListDto` (the HLD "£47 ± £8" is undeliverable) (P2)

## Summary

The HLD mandates cost display as *"£47 ± £8 (17% uncertainty)"*; the contract carries
`(estimatedTotalPence, costConfidence)` and per-key min/max only — **no list-level range**, so the
± band cannot be rendered anywhere. Flagged by
[`design/frontend/pages/groceries.md` §6d + §8 Q1](../../design/frontend/pages/groceries.md), and
the same finding strips the band from the plan review card
([`plan.md` §3b/§4c](../../design/frontend/pages/plan.md): "the '±' band is not in the contract —
omit").

**Fix:** compose the range server-side in the cost-projection step (the per-line aggregates
already carry `minPence`/`maxPence`) and expose it on `ShoppingListDto`:

```yaml
# schemas/grocery.yaml — ShoppingListDto (all nullable, null when no price data)
estimatedTotalMinPence: { type: integer, nullable: true, minimum: 0 }
estimatedTotalMaxPence: { type: integer, nullable: true, minimum: 0 }
```

UI renders `estimate ± (max−min)/2` or the explicit range — frontend's call; the contract ships
the bounds, not a pre-baked ±.

## Behavioural spec

- Step-6 cost projection (grocery-01b/01c): per line, `lineMin = aggregate.minPence-derived unit ×
  packCount`, `lineMax` likewise; totals are the sums. Lines without an aggregate contribute their
  point estimate (or nothing if none — consistent with how `estimatedTotalPence` treats them;
  mirror exactly and document).
- All-null when no aggregates at all (cold start — band omitted, same as today's null total).
- Invariant: `min ≤ estimatedTotal ≤ max` (clamp the point estimate into the band if reference
  prices vs observations disagree; assert in tests).
- **Plan-side note:** the planner's `WeeklyRollupDocument.costEstimateGbp` has the same gap. This
  ticket ships the grocery list bounds only; if product wants the band on the plan card too,
  that's a small planner follow-up reading the same aggregates — flag it in the PR, don't build it
  speculatively.

## Edge-case checklist

- [ ] All lines priced → tight band; mixed → band from priced lines + point estimates for the rest (documented rule)
- [ ] No price data → all three totals null; list renders (cold-start unchanged)
- [ ] `min ≤ estimate ≤ max` always
- [ ] Stale aggregates still count into the band (staleness is already separately surfaced via `staleIngredientCount`)
- [ ] Recalculate refreshes the band with the list

## Files this ticket touches

```
MOD   src/main/resources/openapi/schemas/grocery.yaml                                          (two fields)
MOD   src/main/java/com/example/mealprep/grocery/api/dto/... ShoppingListDto + mapper
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/ShoppingListCalculator.java  (step 6 totals)
MOD   src/main/resources/db/migration/...                                                      (two columns on shopping_lists — the list is persisted)
MOD   src/test/java/com/example/mealprep/grocery/ShoppingListCalculatorTest.java               (band maths matrix)
```

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green
- [ ] Groceries stat strip can render "£52.40 (£48–£57) · 83% confidence"

Squash-merge with: `feat(grocery): estimatedTotalMin/MaxPence cost band on ShoppingListDto`

**Not in scope:** per-line min/max exposure (popover already reads the aggregate endpoint); the
planner rollup band (flagged follow-up); confidence-model changes.
