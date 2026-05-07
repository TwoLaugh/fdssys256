# Grocery Module — Design

*The bridge between the planner's output and a real-world shop. Owns the shopping list, optional provider automation, manual fulfilment, and the price-history learning loop.*

## What It Is

The grocery module turns a plan into food in the user's house. It does this through **four cooperating tiers**, each independently useful:

| Tier | Concern | Status |
|---|---|---|
| 1 | **Shopping List** — deterministic list of what to buy | Always available |
| 2 | **Manual Fulfilment** — user shops anywhere, marks items bought | Always available |
| 3 | **Grocery Order via provider** — optional Tesco/etc automation | Opt-in |
| 4 | **Price History** — learned ingredient prices feeding planner cost optimisation | Always running, fed by tiers 2 + 3 |

A user with no provider configured gets full value from tiers 1, 2, 4 — they just shop manually and the system learns their prices over time. A user with Tesco connected gets all four. The module never hard-requires a provider; the provider is convenience, not a dependency.

This is **not**:
- The Meal Planner (consumes plans; doesn't produce them)
- The Provisions module (writes to it via events; doesn't own inventory)
- The Recipe System (reads ingredient lists; doesn't author recipes)
- An AI service consumer for *what to buy* — that's deterministic. AI is used for *navigation* (driving a browser at a supermarket) when a provider is configured.

The module is **read-only against most data models** — Plan, Recipes, Provisions, Preference. It writes to its own tables (`grocery_orders`, `grocery_price_history`, `grocery_substitution_proposals`, `shopping_lists`) and emits events that update Provisions inventory.

---

## Architectural Position

```
                   ┌─────────────────────────────────────────────┐
                   │       PLAN, RECIPES, PROVISIONS,            │
                   │       PREFERENCE (lifestyle config)         │
                   └─────────────────────────────────────────────┘
                                       │
                                       ▼
         ┌──────────────────────────────────────────────────────────────┐
         │                    GROCERY MODULE                            │
         │                                                              │
         │  Tier 1 ─ Shopping List ◄──────────────┐                    │
         │           (deterministic, always)       │                    │
         │                  │                      │                    │
         │     ┌────────────┴────────────┐         │                    │
         │     ▼                         ▼         │                    │
         │  Tier 2                    Tier 3       │                    │
         │  Manual                    Grocery      │                    │
         │  Fulfilment                Order        │                    │
         │  (user shops               (provider    │                    │
         │   anywhere)                 automation, │                    │
         │                             opt-in)     │                    │
         │     │                         │         │                    │
         │     │   prices observed       │         │                    │
         │     └────────────┬────────────┘         │                    │
         │                  ▼                      │                    │
         │            Tier 4 ─ Price History ──────┘                    │
         │                  (learned, confidence-weighted)              │
         │                  │                                           │
         │                  ▼                                           │
         │          aggregates feed planner cost sub-score              │
         │                                                              │
         └──────────────────┬───────────────────────────────────────────┘
                            ▼
                       PROVISIONS
                       (inventory updated
                        from fulfilment)
```

---

## Tier 1: Shopping List

A first-class output of the planner, exposed by the grocery module. Always available regardless of provider state.

### What it contains

For each unmet ingredient demand from the current plan, one line: `(ingredient_mapping_key, total_quantity, unit, suggested_pack_size, notes)`. Plus separately, a section for staples flagged as `low` or `out` from Provisions.

### Calculation

Deterministic, no AI. Six steps:

1. **Aggregate planned demand.** Walk every scheduled recipe in the active plan, multiply each ingredient quantity by cooked-servings, sum by `ingredient_mapping_key`.
2. **Subtract current inventory.** When pantry tracking is enabled (per the user's lifestyle config), subtract pantry/freezer stock. When disabled, treat inventory as empty.
3. **Apply pack-size heuristics.** A static reference table maps ingredient categories to typical pack sizes (flour: 500g/1kg/1.5kg; eggs: 6/12; milk: 1pt/2pt/4pt). Pick the smallest combination that meets demand. The heuristic table is provider-agnostic v1; provider-specific pack sizes are an enrichment when Tier 3 is connected.
4. **Add staples that hit `low` or `out`.** Provisions flags these; they're appended to the list.
5. **Apply quality preferences.** Lifestyle config carries preferences like "organic where available," "free-range eggs," "own-brand for staples." These render as `notes` on each line — informational without provider integration; route to specific products with provider integration.
6. **Cost projection (when price history exists).** Multiply each line by the confidence-weighted aggregate from Tier 4. Surface `(estimated_total, confidence)` and a per-line breakdown. With no price history, the projection is "unknown" — list still renders.

### Output

The shopping list is rendered to the user via:
- **In-app view** — the primary surface
- **Print / PDF** — for the fridge
- **Copy to clipboard** — paste into Tesco or any other site
- **Email / share** — to a partner doing the shop
- **Optional: drive into Tier 3 automation** — the user opts in per shop

### Lifecycle

The shopping list is **derived state**: regenerated when the underlying plan or provisions change. Persisted as `shopping_lists` rows for history (so the user can see "what was the list last week?" — useful for retroactively marking bought) but never edited as the source of truth — edits flow through plan/provisions edits and the list re-derives.

### Events

- `ShoppingListGeneratedEvent` — published when a new list is rendered for a plan generation or mid-week re-opt.

---

## Tier 2: Manual Fulfilment

The user shops wherever they want — physical store, another supermarket's website, a butcher, the corner shop. The system supports them via a simple mark-bought workflow.

### Mark-bought UX

For each line on the shopping list, the user can:

- **Tap to mark bought** at the suggested pack size and last-known price (one-tap path — fast for the routine "I just did the shop" case)
- **Adjust quantity** when the actual purchase differs from the suggestion
- **Enter actual paid price** (optional but encouraged — this is what feeds Tier 4)
- **Set the store** (optional — enables Tier 4's cross-store comparison)

Plus a **bulk affordance**: "mark all bought" + "set total spend" for the user who just did the whole shop at once and doesn't want to itemise. Per-item prices in this case are estimated by distributing the total proportionally to last-known item costs.

### What this writes

For each marked-bought item:
- One row in `grocery_price_history` with `source = 'manual'` and the price/store/timestamp (when supplied)
- One inventory update via `ProvisionUpdateService.addToInventory` — the item enters Provisions same as if it came from a Tesco delivery
- One `ShoppingListItemMarkedBoughtEvent` for downstream listeners

### Why this matters

Manual fulfilment is the **default path** for users without a provider configured, and it remains useful even for provider users (the corner-shop top-up shop, the butcher, the holiday-in-Italy weeks). Without it, the system would be useless to anyone outside the Tesco-automation cohort.

### Events

- `ShoppingListItemMarkedBoughtEvent` (per item)
- `ShoppingListBulkMarkedBoughtEvent` (when bulk action used)

---

## Tier 3: Grocery Order via Provider

Optional automation layer that takes a Shopping List and drives a `GroceryProvider` to place the order. Same data outcome as Tier 2 (items end up in Provisions) but with automation instead of manual marks.

### The `GroceryProvider` abstraction

```java
public interface GroceryProvider {
    String providerKey();                          // "tesco", "sainsburys", ...

    QuoteResult quote(BasketDraft draft);          // refresh prices; primary cache-feed
    PlaceOrderResult placeOrder(BasketDraft draft);// drive to checkout (NOT confirm)
    OrderStatus checkStatus(String providerOrderId);
    void cancel(String providerOrderId);
}
```

Three structural rules:

1. **Providers never auto-confirm purchases.** `placeOrder` drives a basket up to checkout and stops. The user confirms in the provider's UI. Avoids unauthorised purchases on a flaky run; keeps the legal/payment surface entirely with the provider.
2. **Persistent state lives in this module's tables**, never in provider memory alone. Provider sessions can carry transient state but the source of truth is `grocery_orders` and `grocery_provider_state`.
3. **Providers fail gracefully.** Every method may throw `ProviderUnavailableException` or `ProviderPartialFailureException`. The module surfaces failures to the user and degrades — never retries blindly.

### Tesco — first implementation

Tesco does not expose a public ordering API; the v1 implementation uses **browser automation via Claude computer use / Chrome connector**:

- Long-lived browser session per user; cookies persisted in `grocery_provider_state`
- Item search → "add to basket" actions driven by the AI navigator
- Substitution detection at checkout — captured as proposals, never auto-accepted
- Tesco-side checkout link surfaces to the user when ready to confirm

Login flows, DOM selector stability, retry policy specifics → LLD.

### Future providers

Sainsbury's, Ocado, etc. plug in by implementing `GroceryProvider`. Some may offer real APIs (preferable when available); the abstraction tolerates either. Households can mix providers; Provisions is provider-agnostic.

### Order lifecycle

```
   draft ──► quoted ──► placed ──► awaiting_user_confirmation ──► confirmed
     │         │          │                  │                       │
     │         │          │                  │                       ▼
     │         │          │                  │                  delivered ──► reconciled
     │         │          │                  │                       │
     │         ▼          ▼                  ▼                       ▼
     └──► cancelled (any state, until reconciled)                 archived
```

| State | Meaning |
|---|---|
| `draft` | Shopping list calculated; nothing sent to provider |
| `quoted` | Provider returned availability + price (also writes to price history) |
| `placed` | Basket built on provider, automation stopped at checkout |
| `awaiting_user_confirmation` | User must confirm in provider UI |
| `confirmed` | User confirmed, payment captured, delivery scheduled |
| `delivered` | Items received; substitution review pending if any |
| `reconciled` | Inventory updated; substitutions resolved; price history updated with paid prices |
| `cancelled` | User-cancelled or provider terminal failure |
| `archived` | 12 months past `reconciled`; excluded from default queries |

Each transition emits a domain event for Notification and any other listener.

### Quote step is independent

Quotes can run **without intent to place**. The user can hit "refresh prices" on a draft basket; the provider runs a quote pass; the cache (Tier 4) updates; the user decides whether to place. This is also how price history stays fresh for items the user isn't buying right now (the scheduled refresh below).

### Substitution flow

Tesco substitutes unavailable items with alternatives. The module captures these as **proposals** — never auto-accepts.

When `delivered` arrives:
1. Provider reports each `SubstitutionProposal { originalItem, substituteItem, reason }`.
2. Each persisted in `grocery_substitution_proposals` with state `pending_user_review`.
3. User chooses **accept** (substitute enters inventory) or **reject** (logged as wasted-on-arrival; planner notified original is unmet).
4. On accept → `SubstitutionAcceptedEvent` → Provisions adds the substitute.
5. On reject → `SubstitutionRejectedEvent` → Provisions skips; planner may re-optimise.

The user must resolve all proposals before the order moves to `reconciled`. Auto-accept is **never** the default — substitutions can change a meal materially.

### Partial-failure handling

Browser automation is fragile. The module fails forward, not silently.

| Failure | Module behaviour |
|---|---|
| Login expired | Order stays `draft`; user re-authenticates and re-runs |
| 3 of 5 items added before automation breaks | Marked `placed_partial`; added items persisted; user gets checkout link to add the rest manually |
| Substitution detected but DOM differs from expected | Proposal captured as `unparsed`; user resolves manually |
| Delivery slot selection fails | Order pauses at `placed`; user picks slot manually |
| Provider down | `ProviderUnavailableException`; order marked `provider_unavailable`; retry scheduled |
| AI navigator cost-cap exceeded (`AiUnavailable`) | Falls back to manual basket: shopping list rendered as printable/copyable list; user enters on Tesco themselves |

The graceful-degrade contract: **the user can always complete the order manually**. Browser automation is convenience; never a hard dependency.

### Events

- `GroceryOrderQuotedEvent` (publishes price history rows; UI shows quote)
- `GroceryOrderPlacedEvent` ("basket ready to confirm")
- `GroceryOrderConfirmedEvent`
- `GroceryOrderDeliveredEvent`
- `SubstitutionProposedEvent`, `SubstitutionAcceptedEvent`, `SubstitutionRejectedEvent`
- `GroceryOrderReconciledEvent`
- `GroceryOrderCancelledEvent`
- `GroceryProviderUnavailableEvent`

---

## Tier 4: Price History

The learning loop. Every price encounter — quoted, paid, manually entered — is captured. The aggregate becomes the planner's cost intelligence.

### Capture sources

| Source | When | Confidence weight |
|---|---|---|
| `paid` | Order reconciled | Highest |
| `quote` | Provider quote pass | High |
| `manual` | User mark-bought with price | Medium-high |
| `manual_estimated` | Bulk mark-bought, prices distributed proportionally | Medium |
| `inflation_indexed` | Synthesised when no recent data exists | Lowest (fallback) |

Every observation writes one row to `grocery_price_history`:

```
(ingredient_mapping_key, store, paid_unit_price, quantity, total_price,
 source, observed_at, confidence_weight, order_id_or_null)
```

### Aggregation

Per `(ingredient_mapping_key, store)` — and a cross-store aggregate per ingredient — produces:

- **Point estimate** — confidence-weighted mean of recent observations, exponential decay with ~3-month half-life
- **Confidence** — Bayesian-shaped: rises with sample count, falls with age, source-weighted
- **Range** — min / max seen with timestamps
- **Last-seen recency** — how stale the freshest observation is

The planner reads `(estimate, confidence)` pairs, not raw prices.

### Freshness mechanisms — the four-pronged anti-staleness model

1. **Confidence-weighted aggregation** (above). Old or low-sample prices have less score-pulling power; the planner is naturally more conservative when data is thin.

2. **Inflation-indexed fallback.** For ingredients with stale or no observations, apply a configurable monthly factor to the last known price (default ~0.5%/month, configurable via `mealprep.grocery.inflation_factor_monthly`, intended to roughly track UK food CPI). Crude but corrects in the right direction. Indexed prices carry `source = 'inflation_indexed'` with low confidence — the planner knows it's a guess.

3. **On-demand refresh quotes** (provider users). The shopping list and the planner output both expose a "refresh prices" affordance. Triggering it runs a quote pass over the current draft — every observed price feeds history. Cheap (no commitment to place an order), explicit (user-initiated), and tracked against the AI cost cap.

4. **Scheduled background refresh** (provider users, opt-in). A weekly `@Scheduled` automation run quotes a curated list — typically the user's top-50 most-used ingredients across recent recipes. Bounded, scoped, AI-cost-cap-aware. Keeps the catalogue's frequently-used ingredients fresh even for items the user isn't actively shopping that week.

### Stale-data visibility

When a Plan is generated, the rollup includes a freshness summary:

```
Cost projection: £47 ± £8 (17% uncertainty)
Stale data: 12 of 47 ingredients last priced >3 months ago
Refresh prices? [yes / continue]
```

The planner can also penalise plans with high stale-data ratios — mildly, so genuinely-new recipes aren't punished out of the candidate set.

### Cost-cap awareness

Quote passes consume AI navigator tokens. The Tier 4 freshness mechanisms are budget-aware:

- Manual-refresh quotes count against the user's AI cost cap normally
- Scheduled background refresh is the **first** thing the system skips when the daily cap is approached — non-essential
- If the monthly cap fires (`AiUnavailable`), no automation runs of any kind; the system relies on `inflation_indexed` and `manual` sources for that period

### Cold start

The first 4-8 weeks of a user's price history are sparse. During this period:
- Cost projection has wide confidence intervals (the planner surfaces "low confidence — costs may differ")
- Inflation indexing carries less weight (no anchor)
- The cost sub-score in the planner is mildly penalised in score-pulling power
- Manual fulfilment with price entry is especially valuable during cold start; the UI nudges price entry

After ~2 months of steady shopping, confidence is good enough that cost is a meaningful planner input. This is honest about the cold-start experience.

### Multi-user households

Same shopper, same prices — the household shares price history. Aggregates are per-household, not per-user. (One person's Tesco run benefits the household's planner cost intelligence.)

### Events

- `PriceObservedEvent` — published per observation, carrying source and confidence. Useful for audit/debug; consumed by no critical-path listener in v1.

---

## Failure Modes (consolidated)

| Failure | Tier | Response |
|---|---|---|
| No provider configured, user wants automation | 3 | Surface "configure Tesco in Settings" |
| AI navigator cost-cap exceeded | 3, 4 | Fall back to manual basket entry; surface to user; scheduled refresh skipped |
| Provider down during quote | 3, 4 | Surface; manual entry still works; price history goes stale faster |
| Provider partial failure mid-basket | 3 | Persist partial; user completes manually |
| Substitution unparseable | 3 | User resolves manually |
| Plan total exceeds budget | 1 (cost projection) | Over-budget warning + swap suggestions before placing |
| User over-marks bought (more than shopping list) | 2 | Add as ad-hoc inventory entries; warn it wasn't on the list |
| User under-marks bought (didn't get everything) | 2 | Unmarked items remain on shopping list; planner may re-optimise affected slots if material |
| Inflation indexing produces obviously wrong price | 4 | Confidence flag on indexed prices is low; user can override on next mark-bought |

---

## Data Volumes — Back of Envelope

For a household of 2:

- ~1 plan/week × 30-50 ingredients = ~30-50 shopping-list rows/week
- ~1 grocery order/week (provider users) = ~50 order line items + price observations
- ~30-50 manual mark-bought writes/week (manual users)
- ~50 background refresh quotes/week (opt-in, provider users)
- Total price observations: ~80-150/week per provider household; ~30-50/week per manual-only household
- Storage per observation: ~200 bytes
- 5-year accumulation: ~10-20MB per household (negligible)
- Retention: indefinite for paid + quote; manual rolls into aggregates and old raw rows can compact after 12 months

---

## What This Doc Doesn't Cover (Deferred to LLD)

- DB schema specifics — `shopping_lists`, `grocery_orders`, `grocery_provider_state`, `grocery_substitution_proposals`, `grocery_price_history`
- Pack-size heuristic table contents
- Tesco DOM selectors, login flow, retry policy
- AI navigator prompts (live in the AI service prompt registry)
- Cost-projection currency handling, rounding, precision
- Concurrency between user manual edits to a draft basket and automation runs
- Per-provider rate-limiting, robots.txt politeness
- Substitution-review UI flow (Figma phase)
- Confidence-formula constants (decay half-life, inflation factor) — initial defaults defined here, tuned in implementation

---

## Out of Scope (System-Wide)

- **Multi-supplier basket splitting** in one order. Each order is one provider; users can shop multiple providers across the week, but not mix in a single basket.
- **Recurring / subscription orders.** v1 is one-shot.
- **Loyalty schemes** (Clubcard credits applied automatically) — future.
- **Click-and-collect** — v1 is delivery-only.
- **Receipt scanning** for bulk mark-bought — v1 is per-item / total-spend manual entry.
- **Barcode lookup** for adding items via camera — future.
- **Provider-side payment management** — payment lives with the provider; this module never sees card details.
- **Cross-household coordination of shared orders** — one household, one order at a time.
- **Real-time deal scraping** — the system surfaces "below your usual price" from the user's own history; it does not try to scrape supermarket deal feeds (the original ambition, which hits the API gap that motivates this entire module).
