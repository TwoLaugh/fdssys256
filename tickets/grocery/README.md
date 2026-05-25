# Grocery Module — Ticket Set (v1-scoped)

This is the build-order index for the grocery module. The module is the bridge
between the planner's output and a real-world shop — four cooperating tiers
(Shopping List, Manual Fulfilment, Grocery Order via provider, Price History).
Full spec: [`lld/grocery.md`](../../lld/grocery.md) (≈1089 lines). User-pathway
catalogue: [`e2e/pathways/grocery.md`](../../e2e/pathways/grocery.md). E2E feature:
[`src/e2e/resources/features/grocery/grocery.feature`](../../src/e2e/resources/features/grocery/grocery.feature).

**Grocery executes LAST in the roadmap.** Every module it reads (planner, provisions,
preference, recipe, nutrition, core, ai) is already built. These tickets are
human-reviewed before any build begins.

## Build order

| # | Ticket | Tier | Unblocks (E2E `@pending`) |
|---|---|---|---|
| 1 | [`01a-entities-migrations-repositories-and-boundary.md`](01a-entities-migrations-repositories-and-boundary.md) | all | foundation for GROC-01/03/15/16/17/18/19/30, XJ-05 |
| 2 | [`01b-shopping-list-from-plan.md`](01b-shopping-list-from-plan.md) | 1 | GROC-01 |
| 3 | [`01c-cost-projection-and-reference-price-source.md`](01c-cost-projection-and-reference-price-source.md) | 4 (v1-SIMPLE) | GROC-03, GROC-30 |
| 4 | [`01d-manual-fulfilment-and-price-observations.md`](01d-manual-fulfilment-and-price-observations.md) | 2 + 4 | (GROC-08 already green via provisions) |
| 5 | [`01e-provider-spi-and-order-lifecycle.md`](01e-provider-spi-and-order-lifecycle.md) | 3 | GROC-15, GROC-16, GROC-17, GROC-18, XJ-05 |
| 6 | [`01f-substitution-and-reconciliation.md`](01f-substitution-and-reconciliation.md) | 3 | GROC-19 |
| 7 | [`01g-scheduled-refresh-and-archival.md`](01g-scheduled-refresh-and-archival.md) **(OPTIONAL — lowest priority for v1)** | 3 + 4 | (none `@pending`; GROC-31/35 not in feature) |

Cross-cutting (NOT under `tickets/grocery/`):

- [`tickets/core/03-ingredient-mapping-key-normalisation.md`](../core/03-ingredient-mapping-key-normalisation.md) — shared `IngredientMappingKeys.normalise()` (Decision 5). **Prerequisite** for correct cross-module key matching that grocery depends on. Same bug-family as the discovery→recipe import mapping-key fix.
- [`tickets/planner/02a-cost-from-price-history.md`](../planner/02a-cost-from-price-history.md) — re-point the planner's `CostSubScore` onto Tier-4 (Decision 1 follow-up STUB). **NOT part of grocery v1.**

Build 01a first (everything depends on the entities + migrations + boundary).
Then 01b/01c/01d are largely independent (1c is consumed by 1b's cost step, so
land 1c before 1b's cost-projection leg or stub the aggregate). 01e then 01f are
sequential (substitutions live on the order lifecycle). 01g is optional.

`tickets/core/03` should land **before** 01a's repositories rely on normalised
keys for cross-module matching — but 01a can ship with a `// TODO(core-03)` at the
write boundaries and 01b/01c/01d adopt `IngredientMappingKeys.normalise()` once core-03 merges.

## The v1 scope line (pricing especially)

**Tier 4 is in v1, but V1-SIMPLE** (Decision 1). The product owner: *"for a v1 just
having SOME price optimisation at all is good."*

| In v1 (01c) | Deferred to a noted "v2" section (NOT v1-blocking) |
|---|---|
| `ReferencePriceSource` SPI + a bundled **Open Food Facts "Open Prices"** SNAPSHOT seed (deterministic, no live API in tests/e2e) | Full time-decay (`0.5^(age/halfLife)`) |
| product→ingredient-category **aggregation/mapping layer** (real work — flagged) | Bayesian `priorStrength` confidence shaping |
| **basic** per-mapping-key aggregate: weighted mean + sample count | `InflationIndexer` (ONS food CPI index as the aging input) |
| a simple **confidence + stale** signal (enough for GROC-03 + GROC-30) | half-life / monthly-factor tuning |
| `FakeGroceryProvider` quote prices DERIVE from `ReferencePriceSource` | full decay/Bayesian/inflation sophistication |

The other tiers' v1 cut:

- **Tier 1 (01b):** full deterministic six-step calculation — in v1.
- **Tier 2 (01d):** mark-bought / bulk / undo — in v1. (The inventory-write leg already
  ships via provisions `applyGroceryOrder`; the E2E `@smoke` scenario is already green.)
- **Tier 3 (01e/01f):** `GroceryProvider` SPI + deterministic `FakeGroceryProvider` +
  full order state machine + substitution review/reconcile — in v1. **Real Tesco
  browser automation is a DEFERRED post-v1 ticket** (noted in 01e, not written).
- **Tier 3/4 scheduled (01g):** weekly refresh + hourly status poll + 12-month archival —
  **OPTIONAL, lowest priority for v1.**

## The five product-owner decisions (baked into the tickets as settled)

1. **Pricing = Tier-4, V1-SIMPLE** (01c). `ReferencePriceSource` from a bundled Open
   Prices snapshot + basic aggregate + confidence/stale. Defer decay/Bayesian/inflation
   to a "v2" section. Open Prices is per-barcoded-product → a category-mapping layer is
   real work. **ODbL licence (attribution/share-alike) needs a one-time check before
   bundling.** ONS per-item average price is discontinued for food; ONS food CPI index
   is the deferred-v2 inflation input only.
2. **Shopping list: grocery owns the CALCULATION, planner owns the DATA** (01b — resolves
   GG1). Grocery's calculator reads `PlanQueryService`; a new `getPlanForGrocery` bundle
   is added on the planner **only if** the per-recipe walk over `getPlanById` is too chatty.
3. **Provider = `GroceryProvider` SPI + deterministic `FakeGroceryProvider`** (01e). Real
   Tesco automation deferred post-v1. Fake quote prices derive from `ReferencePriceSource`.
4. **Substitution = provider proposes → user approves each → reconcile blocked while any
   proposal pending** (01f). Grocery owns the user-decision event `SubstitutionAcceptedEvent`;
   provisions republishes its own inventory state-change. Settles the contract with the
   dormant `GroceryOrderConfirmedListener` already shipped in provisions-01h. Auto-accept
   forbidden.
5. **Mapping-key normalisation is a SEPARATE standalone ticket** (`tickets/core/03`).
   Shared `core` `IngredientMappingKeys.normalise()` applied at every write boundary
   (provisions grocery-import — currently keys RAW; recipe ingredient persistence — also
   RAW; future grocery writes). Same family as the spawned discovery→recipe mapping-key bug.
   Unblocks correct cross-module key matching grocery depends on.

## Cross-cutting conventions (every ticket honours)

- Match the project ticket depth/tone (see `tickets/provisions/01h`, `tickets/discovery/01c`,
  `tickets/household/01e`). Cite `lld/grocery.md` line ranges. Every open decision flagged
  **"Worth user review."**
- One concern per migration, append-only. Migration timestamps `V20260601120000..120500`
  + `R__grocery_seed_*` per LLD lines 75-82.
- Module exceptions in a single `GroceryExceptionHandler` `@RestControllerAdvice`
  (`@Order(Ordered.HIGHEST_PRECEDENCE)`); never modify `config/GlobalExceptionHandler.java`.
- OpenAPI: INLINE `nullable: true` (never `$ref + nullable` sibling); single-quote YAML
  description strings containing `,` `:` `'`; `Page<T>` schemas use `additionalProperties: true`.
- HTTP-client / Spring-Web adapters live in `..api..` or `..config..`, never
  `domain.service.internal` (project `springWebStaysInApi` ArchUnit rule).
- `internal/` plumbing package-private; `GroceryProvider` impls live only in
  `grocery.domain.service.internal.providers` (ArchUnit rule, mirrors discovery's
  "impls in the source pocket" pattern).
