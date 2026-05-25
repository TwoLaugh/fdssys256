# Ticket: planner — 02a Migrate `CostSubScore` / `WeeklyCostConfidence` onto Grocery Tier-4 (STUB — follow-up, NOT grocery v1)

> **STATUS: STUB / FOLLOW-UP.** This ticket is scoped here so the roadmap captures it, but it is
> **explicitly NOT part of grocery v1** and **NOT a blocker** for the grocery ticket set
> (`tickets/grocery/01a..01g`). It lands AFTER the grocery module ships its Tier-4
> `PriceHistoryService`. Build it when the team wants the planner's cost intelligence to read learned
> prices instead of (only) provisions supplier-product rows.

## Summary

Re-point the planner's **`CostSubScore` + `WeeklyCostConfidence`** computation from the provisions
`SupplierProduct` cache onto the grocery module's **Tier-4 `PriceHistoryService`** aggregates. Per
[`lld/planner.md` §`CostSubScore` lines 739-748](../../lld/planner.md) (**formula LOCKED 2026-05-07**)
and [`lld/grocery.md` §Tier 4 lines 619-643](../../lld/grocery.md). The CostSubScore formula itself
does **not** change — only its **data source** does.

The shipped `CostSubScore` (planner-01e) reads `ProvisionForPlannerBundleDto.supplierPricesByMappingKey`
([`lld/planner.md` line 748](../../lld/planner.md)). The grocery Tier-4 view
(`PriceHistoryService.getAggregatesByKeys(householdId, keys) → Map<String, PriceAggregateDto>`,
each carrying `pointEstimatePence` + `confidence`) is the richer, learned source the formula's
`mean_confidence` term was designed for ([`lld/planner.md` line 1180](../../lld/planner.md) lists
`ingredientPriceConfidenceByMappingKey` via `GroceryQueryService.getPriceConfidence` as **"(planned)"**
— this ticket realises that planned dependency).

## Decision baked in — owner approved this as its OWN follow-up ticket (NOT grocery v1)

**Product-owner decision (settled):** the planner already computes cost from provisions
`SupplierProduct` rows; re-pointing it onto grocery Tier-4 is approved as a **separate planner
follow-up**, NOT grocery v1. The grocery module ships Tier-4 (grocery-01c) and the planner keeps
reading supplier-products until this ticket migrates it. **Why separate:** changing the planner's
cost data source mid-grocery-build would couple two large changes; isolating it lets the grocery
Tier-4 numbers be validated first, then the planner switch over with a controlled cold-start seed.

## Scope (when built)

1. **Migrate the data source.** `CostSubScore` / `WeeklyCostConfidence` read
   `PriceHistoryService.getAggregatesByKeys(householdId, planIngredientKeys)` for
   `(pointEstimatePence, confidence)` per ingredient instead of (or in addition to)
   `ProvisionForPlannerBundleDto.supplierPricesByMappingKey`. The LOCKED formula is unchanged:
   ```
   raw_cost_fit    = clamp(1 - (estimated_weekly_cost / weekly_budget), 0, 1)
   mean_confidence = confidence_weighted_mean(price_history.confidence for ingredient in plan)
   CostSubScore    = 0.5 + (raw_cost_fit - 0.5) · mean_confidence
   ```
   `mean_confidence` now comes from the grocery aggregate's `confidence` field — exactly the
   confidence-weighted regression-to-0.5 behaviour the formula intends (low confidence → neutral 0.5).
2. **Cold-start seed from supplier-products (so numbers don't JUMP).** **Worth user review** — when
   the planner switches sources, plans for households with no/sparse grocery Tier-4 history would
   suddenly see lower confidence (Tier-4 cold start) than they did from the always-present
   supplier-product cache. To avoid a discontinuity in plan scores: **seed grocery Tier-4 from the
   provisions supplier-product rows for cold-start** — either (a) a one-time backfill writing
   supplier-product prices as low-confidence `QUOTE`/reference observations into
   `grocery_price_history`, or (b) the `CostSubScore` falls back to `supplierPricesByMappingKey` when
   the grocery aggregate is `Optional.empty()` (a blended source during the transition). **Recommendation:
   (b) — a graceful blended fallback** keyed on aggregate-present, with a feature flag to fully cut
   over once Tier-4 coverage is mature. This keeps the cutover smooth and reversible.
3. **Bundle dependency.** Add `GroceryQueryService.getPriceConfidence` (or read
   `PriceHistoryService` directly via its facade) into the planner's `PlanCompositionContext` bundle
   load ([`lld/planner.md` §Read pattern line 1165](../../lld/planner.md)) — one batched
   `getAggregatesByKeys` call per generation, alongside the existing provisions bundle.

## What's settled vs open

- **Settled:** the formula (LOCKED 2026-05-07 — do NOT touch); the source becomes grocery Tier-4;
  this is a planner follow-up, not grocery v1.
- **Open (worth user review):**
  - Cold-start strategy: blended fallback (b) vs one-time backfill (a). Recommendation: (b) + flag.
  - Whether to read grocery via a new `GroceryQueryService.getPriceConfidence` thin method or the
    existing `PriceHistoryService.getAggregatesByKeys` directly (the latter avoids a new interface;
    the former matches the planner-LLD's "(planned)" naming).
  - Full cutover timing (when Tier-4 coverage is "mature enough" to drop the supplier-product fallback).

## Dependencies

- **Hard (must ship first):** the grocery module's Tier-4 — `tickets/grocery/01c`
  (`PriceHistoryService.getAggregatesByKeys` + `PriceAggregateDto`). And `tickets/core/03`
  (normalised keys, so the planner's plan-ingredient keys match grocery's stored keys).
- **Hard:** planner-01e (the shipped `CostSubScore`), planner's `PlanCompositionContext` bundle load.
- **NOT a dependency of grocery v1** — grocery ships independently; the planner keeps its current
  supplier-product source until this lands.

## Acceptance / DoD (when built)

- [ ] `CostSubScore` reads grocery Tier-4 `(estimate, confidence)`; the LOCKED formula is byte-unchanged
- [ ] Cold-start: no discontinuous score jump (blended fallback or backfill, per the chosen strategy)
- [ ] `CostSubScoreTest` (planner-01e) still passes — confidence-0 → 0.5, confidence-1 → raw score
- [ ] One batched `getAggregatesByKeys` per generation (no N+1 in the bundle load)
- [ ] `verify` + `spotless` clean; CI green; planner boundary tests pass

Squash-merge with: `feat(planner): 02a — CostSubScore reads grocery Tier-4 price history (with supplier-product cold-start fallback)`

## What's NOT in scope

- Any change to the LOCKED CostSubScore / WeeklyCostConfidence **formula** — source only.
- The grocery Tier-4 implementation itself → `tickets/grocery/01c`.
- Re-pointing other planner sub-scores — only the cost pair.
- Removing the provisions supplier-product cache — it stays as the cold-start fallback (and for the
  provisions module's own uses) until full cutover.
