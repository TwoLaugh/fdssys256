# Grocery Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented.
>
> **Build-state caveat:** Much of this domain is **designed-but-not-yet-built** (Tier 3 provider automation via Tesco browser-driving, scheduled refresh, the full order lifecycle). The catalogue captures the full HLD surface regardless; Stage 2 will tag unbuildable scenarios `@pending`. Tier 1 (shopping list) and Tier 2 (manual fulfilment) are the always-available core; Tiers 3 (provider order) and 4's automated refresh legs are the opt-in, largely-unbuilt surface.

---

## 1. Domain Summary

The Grocery domain is the **bridge between the planner's output and a real-world shop** — it turns a plan into food in the user's house. It is not a constraint loop itself; it is the *output* that acts on the Provisions data model and the *learning source* that feeds the planner's cost sub-score. It is built as **four cooperating tiers**, each independently useful:

| Tier | Concern | Availability |
|---|---|---|
| 1 | **Shopping List** — deterministic list of what to buy | Always |
| 2 | **Manual Fulfilment** — user shops anywhere, marks items bought | Always |
| 3 | **Grocery Order via provider** — optional Tesco/etc automation through the `GroceryProvider` abstraction | Opt-in |
| 4 | **Price History** — learned, confidence-weighted ingredient prices feeding the planner's cost optimisation | Always running, fed by tiers 2 + 3 |

It is **read-only against most data models** (Plan, Recipes, Provisions, Preference): it reads the shopping needs and quality preferences, writes to its own order/price/list/substitution state, and emits events that update **Provisions inventory**. The shopping list calculation is deterministic (no AI). AI is used only for **navigation** (driving a browser at a supermarket) when a provider is configured. A user with no provider gets full value from tiers 1, 2, 4 (manual shopping + learned prices); the provider is convenience, never a hard dependency — the graceful-degrade contract is that **the user can always complete the order manually**.

In the three-loop architecture it serves the **Provisions loop** as the action arm: Provisions → Planner (shopping list) → Grocery (order) → Provisions (inventory updated).

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Primary user** | Views the shopping list; shops manually and marks items bought (per-item or bulk); opts into a provider per shop; reviews/refreshes quotes; confirms/cancels orders in the provider UI; resolves substitution proposals; corrects prices; configures the provider in Settings. |
| **Household member** | Shares Provisions and price history (aggregates are per-household). Whether a household member can mark-bought / place / confirm an order vs only the primary user is unspecified `[HLD-GAP]` — Provisions edit concurrency is deferred to Household Model. |
| **`GroceryProvider` (system actor, e.g. Tesco)** | Quotes prices, drives a basket to checkout (never confirms), reports order status, reports substitution proposals at delivery, can be cancelled. Fails gracefully via `ProviderUnavailable` / `ProviderPartialFailure`. |
| **AI navigator (system actor)** | Drives the browser (Claude computer use / Chrome connector) for Tesco: item search, add-to-basket, substitution detection. Cost-cap-aware; falls back to manual basket when capped. |
| **Meal Planner (upstream caller)** | Produces the plan whose unmet demand the shopping list derives from; the recipient of the rendered list; re-optimises when substitutions are rejected / items under-marked. Owns the shopping-list *calculation logic* per provision-model.md `[HLD-GAP — ownership contradiction]`. |
| **Provisions (downstream / inventory writer-via-events)** | Receives `addToInventory` writes from every fulfilment path (manual mark, bulk mark, order reconciliation, accepted substitution); supplies current inventory + staples for the list; supplies supplier-cache prices. |
| **Scheduler / system clock (system actor)** | Drives the weekly background refresh-quote run (top-50 ingredients), order archival 12 months past `reconciled`, inflation indexing of stale prices. |
| **Notification System (downstream listener)** | Consumes every order-lifecycle and substitution event ("basket ready to confirm", etc.). |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the HLD permits. Each: verb-phrase + one-line description + HLD ref. Downstream pathways draw from this.

### Shopping List (Tier 1)
1. **View the shopping list** — open the in-app derived list for the current plan: per unmet-ingredient line `(ingredient_mapping_key, total_quantity, unit, suggested_pack_size, notes)` plus a low/out staples section. §Tier 1 (output, in-app view).
2. **View the cost projection** — see `(estimated_total, confidence)` + per-line breakdown when price history exists, or "unknown" when none. §Tier 1 (cost projection); §Tier 4 (stale-data visibility).
3. **View prior/historic shopping lists** — "what was the list last week?" (persisted for history). §Tier 1 (lifecycle).
4. **Print / export the list to PDF** — for the fridge. §Tier 1 (output).
5. **Copy the list to clipboard** — paste into Tesco or any site. §Tier 1 (output).
6. **Email / share the list** — to a partner doing the shop. §Tier 1 (output).
7. **Trigger / drive the list into provider automation** — opt into Tier 3 for this shop. §Tier 1 (output, opt-in per shop).
8. **Acknowledge an over-budget warning + swap suggestions** — surfaced when projected total exceeds budget before placing. §Failure Modes (plan total exceeds budget).

### Manual Fulfilment (Tier 2)
9. **Mark an item bought (one-tap)** — at suggested pack size + last-known price. §Tier 2 (mark-bought UX).
10. **Mark bought with adjusted quantity** — actual purchase differs from suggestion. §Tier 2.
11. **Enter actual paid price for a marked item** — optional, feeds Tier 4. §Tier 2.
12. **Set the store for a marked item** — optional, enables cross-store comparison. §Tier 2.
13. **Bulk "mark all bought" + set total spend** — whole-shop-at-once; per-item prices distributed proportionally to last-known costs. §Tier 2 (bulk affordance).
14. **Over-mark (buy more than the list)** — record an item / quantity not on the list. §Failure Modes (over-marks → ad-hoc inventory + warn).
15. **Under-mark (leave items unbought)** — finish without marking everything. §Failure Modes (under-marks → items remain on list).

### Provider Order (Tier 3)
16. **Configure / connect a provider** — set up Tesco (or other) in Settings; establish the long-lived session. §Tier 3 (Tesco), §Failure Modes (no provider → "configure Tesco in Settings").
17. **Quote / refresh prices on a draft** — run a provider quote pass without intent to place; updates price cache. §Quote step is independent; §Tier 4 (on-demand refresh).
18. **Place an order** — drive the basket to checkout (provider stops; never confirms). §Tier 3 (`placeOrder`), §Order lifecycle.
19. **Confirm an order (in the provider UI)** — user completes payment/delivery scheduling on the provider side. §Tier 3 (rule 1: never auto-confirm); §Order lifecycle.
20. **Check order status** — poll the provider for current state. §`GroceryProvider.checkStatus`; §Order lifecycle.
21. **Pick a delivery slot manually** — when automated slot selection fails. §Partial-failure (delivery slot fails).
22. **Re-authenticate the provider** — when login expired; re-run the order. §Partial-failure (login expired).
23. **Complete a partial basket manually** — follow the checkout link to add items automation couldn't. §Partial-failure (`placed_partial`).
24. **Fall back to manual basket entry** — when AI navigator hits cost cap; list rendered printable/copyable for the user to enter on Tesco. §Partial-failure (`AiUnavailable`), §Cost-cap awareness.
25. **Cancel an order** — user-cancel at any state until reconciled. §`GroceryProvider.cancel`; §Order lifecycle.
26. **Reconcile a delivered order** — inventory updated, substitutions resolved, paid prices written; moves order to `reconciled`. §Order lifecycle (`reconciled`), §Substitution flow.

### Substitution flow (Tier 3)
27. **View substitution proposals** — at delivery, each `{originalItem, substituteItem, reason}` in `pending_user_review`. §Substitution flow.
28. **Accept a substitution** — substitute enters inventory. §Substitution flow (accept → Provisions adds).
29. **Reject a substitution** — logged wasted-on-arrival; planner notified original is unmet. §Substitution flow (reject → planner may re-optimise).
30. **Resolve an unparsed substitution manually** — when DOM differs from expected, the proposal is captured `unparsed`; user resolves by hand. §Partial-failure (substitution unparseable).

### Price History (Tier 4)
31. **View learned price for an ingredient** — point estimate + confidence + range + last-seen recency, per `(ingredient, store)` and cross-store. §Tier 4 (aggregation). *(Surface/affordance for direct viewing is implied via cost projection — see G-note.)*
32. **Override a price on next mark-bought** — correct an obviously-wrong learned/indexed price. §Failure Modes (inflation indexing wrong → user overrides). (Supplier-price correction also documented in provision-model.md §User Overrides.)
33. **Trigger scheduled background refresh (system)** — weekly automation quotes the top-50 most-used ingredients (opt-in, provider users). §Tier 4 (freshness mechanism 4).
34. **Apply inflation indexing (system)** — synthesise a price for stale/absent observations at a configurable monthly factor; lowest confidence. §Tier 4 (freshness mechanism 2).

## 4. State Models

### 4.1 Shopping list lifecycle
```
(no list)
   │  plan generated, or mid-week re-opt, or plan/provisions change
   ▼
DERIVED  ── always re-derived from (plan ingredients − inventory) + low/out staples
   │  ├─ rendered to user (in-app / PDF / clipboard / email / provider-drive)
   │  └─ persisted as a historic row (read-only snapshot for "last week's list")
   ▼
SUPERSEDED  (a newer derivation replaces the active list; old snapshot retained)
```
The shopping list is **derived state**, never edited as a source of truth — edits flow through plan/provisions edits and the list re-derives.

**Illegal / disallowed transitions (→ error pathways):**
- The list cannot be directly edited and persisted as authoritative (no "edit the list itself"; edits route to plan/provisions).
- A list line cannot exist for an ingredient the plan doesn't need *and* isn't a low/out staple.

### 4.2 Grocery order lifecycle
```
   draft ──► quoted ──► placed ──► awaiting_user_confirmation ──► confirmed
     │         │          │                  │                       │
     │         │          │                  │                       ▼
     │         │          │                  │                  delivered ──► reconciled
     │         │          │                  │                       │
     │         ▼          ▼                  ▼                       ▼
     └──► cancelled (any state, until reconciled)                 archived
```
Plus partial / failure sub-states branching off the happy path: `placed_partial` (basket partially built), `provider_unavailable` (provider down, retry scheduled). Each transition emits a domain event.

| State | Meaning |
|---|---|
| `draft` | List calculated; nothing sent to provider |
| `quoted` | Provider returned availability + price (also writes price history) |
| `placed` | Basket built on provider, automation stopped at checkout |
| `awaiting_user_confirmation` | User must confirm in provider UI |
| `confirmed` | User confirmed, payment captured, delivery scheduled |
| `delivered` | Items received; substitution review pending if any |
| `reconciled` | Inventory updated; substitutions resolved; paid prices written |
| `cancelled` | User-cancelled or provider terminal failure |
| `archived` | 12 months past `reconciled`; excluded from default queries |

**Illegal / disallowed transitions (→ error pathways):**
- Provider auto-confirming a purchase (`placeOrder` must stop at checkout — never reaches `confirmed` without the user). Auto-confirm is structurally forbidden.
- Cancelling after `reconciled` (cancellable "any state, until reconciled" — so reconciled/archived are terminal-to-cancel).
- Moving to `reconciled` while any substitution proposal is still `pending_user_review` (all proposals must be resolved first).
- Acting on / transitioning an `archived` order.
- Retrying a failed automation run "blindly" (the module degrades / surfaces, never retries blindly).

### 4.3 Substitution proposal lifecycle
```
pending_user_review (provider reported at delivery)
   ├─ user accepts → ACCEPTED  → SubstitutionAcceptedEvent → Provisions adds substitute
   ├─ user rejects → REJECTED  → SubstitutionRejectedEvent → Provisions skips; planner may re-opt
   └─ DOM unparseable → captured `unparsed` → user resolves manually → (accept|reject)
```
**Illegal transitions:** auto-accepting a proposal (auto-accept is *never* the default — substitutions can change a meal materially); reconciling the order while a proposal is unresolved (see 4.2).

### 4.4 Price observation lifecycle (append-only, surfaced via aggregates)
```
OBSERVED (one immutable row per price encounter)
   source ∈ { paid (highest) | quote (high) | manual (medium-high)
            | manual_estimated (medium) | inflation_indexed (lowest, synthesised) }
   │  feeds → confidence-weighted aggregation (3-month half-life decay)
   ▼
AGGREGATE  (point estimate + confidence + range + last-seen, per (ingredient, store) and cross-store)
   │  raw rows older than 12 months may compact into aggregates (manual source)
   ▼
COMPACTED  (paid + quote retained indefinitely; old manual rows rolled up)
```
**Illegal / disallowed transitions:** editing a logged observation (corrections are new observations / overrides on next mark-bought, not edits — mirrors Provisions' immutable-waste-entry rule).

---

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/unauthorized/conflict/illegal-transition), **Edge** (empty/huge/boundary/duplicate/concurrent). Cross-module touchpoints (Provisions inventory, Planner shopping needs / re-opt, Tesco provider, AI navigator) are noted; they are fully detailed in their own domain files + the cross-journey file.

### Tier 1 — Shopping List

#### GROC-01 — View the shopping list for the current plan
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; an active plan exists with unmet ingredient demand.
- **Action:** Open the in-app shopping list.
- **Expected outcome:** A deterministic list rendered: one line per unmet ingredient `(ingredient_mapping_key, total_quantity, unit, suggested_pack_size, notes)`, computed as aggregated planned demand minus inventory; a separate section lists staples flagged `low`/`out` from Provisions; quality-preference notes (organic / free-range / own-brand) render on lines; cost projection appears if price history exists, else "unknown" with the list still rendering.
- **Variations:** pantry tracking enabled (inventory subtracted) vs disabled (inventory treated as empty → fuller list); with vs without low/out staples; with vs without quality-preference notes; with price history (projection shown) vs cold start (projection "unknown"); plan with all demand already in inventory (list is staples-only or empty).
- **HLD ref:** grocery.md §Tier 1 (what it contains, calculation 6 steps, output); provision-model.md §Shopping List Calculation (formula).
- **Notes:** Cross-module: Planner (demand), Provisions (inventory + staples), Preference (quality notes), Tier 4 (cost). Deterministic — no AI. Self-scoped: assert *this plan's* list lines, not global counts.

#### GROC-02 — Shopping list with no active plan
- **Category:** Error / Edge
- **Actor:** Primary user
- **Preconditions:** Authenticated; no active plan (cold start, or before first plan generation).
- **Action:** Request the shopping list.
- **Expected outcome:** No plan-derived demand to compute. `[HLD-GAP]` — the HLD never states what the list shows with no plan: an empty list, a staples-only list (low/out staples are plan-independent), or an error. The staples section is logically plan-independent and could still render.
- **Variations:** no plan + no staples low/out (fully empty); no plan + staples low/out (staples-only list?); plan exists but every line met by inventory (empty plan-demand section).
- **HLD ref:** grocery.md §Tier 1; provision-model.md §Bootstrapping (cold start, empty inventory).
- **Notes:** Boundary of "derived state" when the upstream derivation source is absent. Flagged.

#### GROC-03 — View the cost projection with stale-data summary
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Plan exists; some price history exists (post cold start).
- **Action:** Open the list / plan rollup and read the cost projection.
- **Expected outcome:** `(estimated_total, confidence)` with a per-line breakdown; a freshness summary ("Cost projection: £47 ± £8 (17% uncertainty) / Stale data: 12 of 47 ingredients last priced >3 months ago / Refresh prices? [yes / continue]"). Confidence is confidence-weighted (rises with samples, falls with age).
- **Variations:** rich history (tight interval) vs sparse / cold start (wide interval + "low confidence — costs may differ"); some lines priced from `inflation_indexed` (flagged low-confidence guess); zero stale lines (no stale-data nag); high stale-data ratio (planner mildly penalises the plan).
- **HLD ref:** grocery.md §Tier 4 (aggregation, stale-data visibility, cold start); provision-model.md §Accuracy Expectations (cost as ranges).
- **Notes:** Cross-module: Planner (consumes `(estimate, confidence)`, penalises stale plans). Assert presence/shape of projection + confidence, not exact numbers.

#### GROC-04 — Export the list (PDF / clipboard / email-share)
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A rendered shopping list.
- **Action:** Print/export to PDF, copy to clipboard, or email/share to a partner.
- **Expected outcome:** The list is delivered in the chosen surface with the same content as the in-app view; no order placed, no inventory change.
- **Variations:** PDF for the fridge; clipboard for pasting into Tesco/any site; email/share to a partner doing the shop; export an empty/staples-only list `[HLD-GAP]` (behaviour of exporting an empty list unspecified).
- **HLD ref:** grocery.md §Tier 1 (output surfaces).
- **Notes:** These are the *manual* escape hatches — they work with no provider configured. No external deps beyond share transport.

#### GROC-05 — Shopping list re-derives when plan or provisions change
- **Category:** Happy / Edge
- **Actor:** System (on plan generation / mid-week re-opt / provisions change)
- **Preconditions:** An existing derived list; an upstream change occurs.
- **Action:** Plan regenerates, mid-week re-opt runs, or inventory/staples change.
- **Expected outcome:** A fresh list is derived; the prior list is retained as a historic snapshot; `ShoppingListGeneratedEvent` published; the active list reflects the new derivation (never a manual edit of the old one).
- **Variations:** plan regeneration; mid-week re-opt (scoped to remaining days); a cook-event deducts inventory → list shrinks; a staple flips to `low` → appears on the list; rapid successive changes (only the latest derivation is active, prior snapshots retained — supersession edge).
- **HLD ref:** grocery.md §Tier 1 (lifecycle, derived state, events); system-overview.md §Mid-week re-optimisation; provision-model.md §Skipped meals.
- **Notes:** Cross-module: Planner, Provisions. Asserts derived-state invariant: no authoritative direct edit.

#### GROC-06 — Attempt to directly edit the shopping list
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** A rendered list.
- **Action:** Try to add/remove/change a line directly and have it persist as the source of truth.
- **Expected outcome:** Not supported — the list is derived; edits must flow through plan or provisions edits and the list re-derives. `[HLD-GAP]` — the HLD asserts the list is "never edited as the source of truth" but doesn't say whether the UI offers an edit affordance that redirects to plan/provisions, or simply has none.
- **Variations:** add a line; remove a line; change a quantity; change a suggested pack size.
- **HLD ref:** grocery.md §Tier 1 (lifecycle — never edited as source of truth).
- **Notes:** Illegal-transition / derived-state guard. The affordance question is a finding.

#### GROC-07 — Over-budget warning before placing
- **Category:** Alternate / Error
- **Actor:** Primary user / system
- **Preconditions:** Budget set in Provisions; projected plan total exceeds budget (+ tolerance).
- **Action:** Open the list / attempt to drive into an order.
- **Expected outcome:** An over-budget warning with swap suggestions is surfaced *before* placing; the user decides (proceed or adjust). The system never silently degrades.
- **Variations:** within tolerance (no warning); over target but within tolerance_over (informational); over target+tolerance (warning + swaps); no budget set (no constraint, no warning — order still works); projection partial/low-confidence (warning hedged).
- **HLD ref:** grocery.md §Failure Modes (plan total exceeds budget); provision-model.md §Budget (tolerance_over, partial estimate).
- **Notes:** Cross-module: Provisions (budget), Planner (swap suggestions). The swap-suggestion mechanism lives in Planner/Optimiser — referenced, not owned here.

### Tier 2 — Manual Fulfilment

#### GROC-08 — Mark an item bought (one-tap)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A shopping list with at least one line.
- **Action:** Tap to mark a line bought at the suggested pack size and last-known price.
- **Expected outcome:** One `grocery_price_history` row written with `source = 'manual'` (price/store/timestamp when supplied); one inventory `addToInventory` write (item enters Provisions exactly as a Tesco delivery would); one `ShoppingListItemMarkedBoughtEvent` emitted.
- **Variations:** with last-known price vs no known price (price omitted); first-ever purchase of an item (no last-known price to default); marking the last line on the list (list now fully fulfilled).
- **HLD ref:** grocery.md §Tier 2 (mark-bought UX, what this writes, events); provision-model.md §Inventory updates (manual add / grocery order).
- **Notes:** Cross-module: Provisions (inventory add). Default path for no-provider users. Self-scoped: assert *this item* in *this user's* inventory + one price row.

#### GROC-09 — Mark bought with adjusted quantity, paid price and store
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A shopping list line.
- **Action:** Mark bought but adjust quantity, enter the actual paid price, and set the store.
- **Expected outcome:** Inventory reflects the actual quantity; a `manual` price row carries the real paid price + store (feeds Tier 4 cross-store comparison); confidence weight is medium-high.
- **Variations:** quantity higher than suggested; lower than suggested; price entered vs omitted; store set (cross-store comparison enabled) vs unset; ingredient_mapping_key set automatically vs inferred/confirmed manually (provision-model.md).
- **HLD ref:** grocery.md §Tier 2; §Tier 4 (capture sources — `manual`).
- **Notes:** Cross-module: Provisions, Tier 4. Cold-start UI nudges price entry (especially valuable early).

#### GROC-10 — Bulk "mark all bought" + set total spend
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A shopping list; user did the whole shop at once.
- **Action:** Use the bulk affordance: mark everything bought and enter one total spend.
- **Expected outcome:** All lines enter inventory; per-item prices are estimated by distributing the total proportionally to last-known item costs; price rows written with `source = 'manual_estimated'` (medium confidence); a `ShoppingListBulkMarkedBoughtEvent` emitted.
- **Variations:** all items have last-known costs (clean proportional split); some items have no last-known cost `[HLD-GAP]` (how the proportional distribution handles items with no anchor price is unspecified); total spend of 0 or implausibly low/high (no validation rule stated — see edge).
- **HLD ref:** grocery.md §Tier 2 (bulk affordance), §Tier 4 (`manual_estimated`).
- **Notes:** Cross-module: Provisions, Tier 4. The no-anchor distribution case is a finding.

#### GROC-11 — Over-mark: buy more than was on the list
- **Category:** Edge
- **Actor:** Primary user
- **Preconditions:** Shopping in progress.
- **Action:** Mark bought an item (or a quantity) that wasn't on the shopping list.
- **Expected outcome:** Added as an ad-hoc inventory entry; the user is warned it wasn't on the list. Inventory stays consistent; no error.
- **Variations:** an entirely off-list item; a larger quantity of an on-list item; an impulse buy with vs without a price.
- **HLD ref:** grocery.md §Failure Modes (user over-marks bought).
- **Notes:** Cross-module: Provisions (ad-hoc add). Assert the warning + the ad-hoc inventory entry.

#### GROC-12 — Under-mark: didn't buy everything
- **Category:** Edge
- **Actor:** Primary user
- **Preconditions:** A list with multiple lines; user shops but doesn't get all of it.
- **Action:** Finish the shop with some lines left unmarked.
- **Expected outcome:** Unmarked items remain on the shopping list; if the gap is material, the planner may re-optimise the affected meal slots.
- **Variations:** one trivial item missed (no re-opt); a key ingredient missed (planner re-optimises remaining days); user later marks the missed item bought on a top-up shop.
- **HLD ref:** grocery.md §Failure Modes (user under-marks bought); system-overview.md §Mid-week re-optimisation (Provisions change).
- **Notes:** Cross-module: Planner re-opt. `[HLD-GAP]` — "if material" threshold for triggering re-opt is not defined.

### Tier 3 — Provider Order

#### GROC-13 — Configure a provider (connect Tesco)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; no provider yet configured.
- **Action:** Connect Tesco in Settings (establish the long-lived browser session; cookies persist).
- **Expected outcome:** Provider available; session/cookies persisted in provider state; subsequent shops can opt into automation. Persistent state lives in the module's tables, not provider memory.
- **Variations:** first provider connected; a household connecting a provider others share; `[HLD-GAP]` — the login flow, credential storage, and whether a household member vs only the primary user may connect are deferred to LLD / unspecified.
- **HLD ref:** grocery.md §Tier 3 (Tesco — long-lived session), §Failure Modes ("configure Tesco in Settings"); system-overview.md (Tesco via Claude computer use / Chrome connector).
- **Notes:** Cross-module: AI navigator session. Designed-but-largely-unbuilt (Tesco automation). Login specifics → LLD.

#### GROC-14 — Attempt automation with no provider configured
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** No provider configured.
- **Action:** Try to place/quote an automated order.
- **Expected outcome:** Surface "configure Tesco in Settings"; tiers 1, 2, 4 still fully usable (manual path intact). No order created.
- **Variations:** quote attempt; place attempt; refresh-prices attempt — all gated on a configured provider.
- **HLD ref:** grocery.md §Failure Modes (no provider configured).
- **Notes:** The module never hard-requires a provider; assert the manual path remains available.

#### GROC-15 — Quote / refresh prices on a draft (no intent to place)
- **Category:** Happy / Alternate
- **Actor:** Primary user → `GroceryProvider`
- **Preconditions:** Provider configured; a draft basket / current list.
- **Action:** Hit "refresh prices"; the provider runs a quote pass.
- **Expected outcome:** Order/basket reaches `quoted`; provider returns availability + price; every observed price writes a Tier 4 `quote` row (high confidence); `GroceryOrderQuotedEvent` published; UI shows the quote; the user decides whether to place. No commitment to buy.
- **Variations:** quote then place; quote then walk away (prices still captured); quote that the AI navigator runs against the AI cost cap (counts normally); quote returning some items unavailable (availability surfaced).
- **HLD ref:** grocery.md §Quote step is independent; §Tier 4 (on-demand refresh, capture source `quote`); §Events.
- **Notes:** Cross-module: AI navigator (cost-cap), Tier 4 (price rows). Quote is the primary cache-feed for provider users.

#### GROC-16 — Place an order (drive to checkout, stop)
- **Category:** Happy
- **Actor:** Primary user → `GroceryProvider` (AI navigator)
- **Preconditions:** Provider configured; a quoted/draft basket.
- **Action:** Place the order; automation drives item search + add-to-basket up to checkout.
- **Expected outcome:** Order → `placed` → `awaiting_user_confirmation`; basket built on the provider; automation **stops at checkout** (never confirms); a Tesco-side checkout link surfaces; `GroceryOrderPlacedEvent` ("basket ready to confirm") published.
- **Variations:** all items added cleanly; the recipe needs a new product (provider searches + caches it, provision-model.md §Supplier data); pack-size rounding leaves surplus (smallest sufficient pack selected; surplus stays in inventory after delivery).
- **HLD ref:** grocery.md §Tier 3 (`placeOrder` — drive to checkout, NOT confirm; rule 1), §Order lifecycle; provision-model.md §Pack size awareness.
- **Notes:** Cross-module: AI navigator, Provisions (supplier cache). Designed-but-unbuilt. Structural rule: never auto-confirm.

#### GROC-17 — Confirm an order in the provider UI
- **Category:** Happy
- **Actor:** Primary user (in provider's own UI)
- **Preconditions:** Order in `awaiting_user_confirmation`; checkout link surfaced.
- **Action:** User confirms payment + delivery scheduling on the provider side.
- **Expected outcome:** Order → `confirmed`; `GroceryOrderConfirmedEvent` published; payment captured by the provider (this module never sees card details); delivery scheduled.
- **Variations:** confirm promptly; confirm much later (order sits in `awaiting_user_confirmation` — no stated expiry `[HLD-GAP]`); abandon without confirming (order stays awaiting; cancel path GROC-23).
- **HLD ref:** grocery.md §Tier 3 (user confirms in provider UI), §Order lifecycle, §Events; §Out of Scope (payment lives with provider).
- **Notes:** The legal/payment surface stays entirely with the provider. No confirmation-window timeout is specified — flagged.

#### GROC-18 — Delivered order → reconciliation
- **Category:** Happy
- **Actor:** Primary user / system
- **Preconditions:** Order `confirmed`; delivery arrives; no unresolved substitutions (or all resolved — GROC-19/20).
- **Action:** Mark/observe delivery; reconcile.
- **Expected outcome:** Order → `delivered` → `reconciled`; items added to Provisions inventory (expiry populated from category defaults, provision-model.md); paid prices written to Tier 4 as `paid` (highest confidence); `GroceryOrderDeliveredEvent` + `GroceryOrderReconciledEvent` published.
- **Variations:** delivery with no substitutions (straight to reconcile after delivered); delivery with substitutions (must resolve all first — GROC-19); expiry dates absent in delivery data → category defaults applied (provision-model.md §Expiry tracking).
- **HLD ref:** grocery.md §Order lifecycle (`delivered`, `reconciled`), §Tier 4 (`paid`), §Events; provision-model.md §Inventory updates (grocery order), §Expiry tracking.
- **Notes:** Cross-module: Provisions (inventory + expiry defaults), Tier 4 (paid prices). Reconciliation is the integration backbone for provider users.

#### GROC-19 — Resolve substitution proposals at delivery (accept / reject)
- **Category:** Happy / Alternate
- **Actor:** Primary user
- **Preconditions:** Order `delivered`; provider reported ≥1 `SubstitutionProposal` in `pending_user_review`.
- **Action:** For each proposal, accept or reject.
- **Expected outcome:** Accept → `SubstitutionAcceptedEvent` → Provisions adds the substitute item; substitution history recorded. Reject → `SubstitutionRejectedEvent` → Provisions skips; the original is logged wasted-on-arrival and flagged unmet; the planner may re-optimise. The order cannot reach `reconciled` until **all** proposals are resolved.
- **Variations:** all accepted; all rejected; mixed accept/reject across several proposals; accept a substitute that itself maps to a new ingredient (Provisions maps it); reject triggers planner re-opt of the affected slot.
- **HLD ref:** grocery.md §Substitution flow (steps 1-5), §Order lifecycle; provision-model.md §Substitution tracking.
- **Notes:** Cross-module: Provisions (add/skip), Planner (re-opt on reject). Auto-accept is structurally forbidden — assert no proposal resolves without a user choice.

#### GROC-20 — Substitution unparseable (DOM differs)
- **Category:** Edge / Error
- **Actor:** AI navigator / Primary user
- **Preconditions:** A substitution detected but the page DOM differs from expected.
- **Action:** Automation captures the proposal as `unparsed`.
- **Expected outcome:** Proposal persisted with state `unparsed`; the user resolves it manually (then accept/reject as normal); order still cannot reconcile until resolved.
- **Variations:** one unparsed among several parsed; all unparsed; user resolves an unparsed proposal then accepts vs rejects.
- **HLD ref:** grocery.md §Substitution flow, §Partial-failure (substitution unparseable), §Failure Modes.
- **Notes:** Fails-forward, not silently. Designed-but-unbuilt. Assert manual-resolution path exists.

#### GROC-21 — Reconcile blocked by unresolved substitution
- **Category:** Error
- **Actor:** Primary user / system
- **Preconditions:** Order `delivered`; ≥1 proposal still `pending_user_review` or `unparsed`.
- **Action:** Attempt to move the order to `reconciled`.
- **Expected outcome:** Rejected — illegal transition; the user must resolve all proposals first.
- **Variations:** one unresolved proposal; several; an unparsed one blocking.
- **HLD ref:** grocery.md §Substitution flow ("must resolve all proposals before reconciled"), §Order lifecycle.
- **Notes:** Illegal-transition pathway derived from the state model.

#### GROC-22 — Partial basket failure mid-place
- **Category:** Error / Edge
- **Actor:** AI navigator
- **Preconditions:** Placing an order; automation breaks after adding some items (e.g. 3 of 5).
- **Action:** Automation fails partway.
- **Expected outcome:** Order marked `placed_partial`; the items already added are persisted; the user gets a checkout link to add the rest manually. Module fails forward, never retries blindly.
- **Variations:** few items added vs most; user completes the rest manually then confirms; user abandons (cancel path).
- **HLD ref:** grocery.md §Partial-failure handling (3 of 5 items), §Tier 3 (rule 3: fail gracefully).
- **Notes:** Cross-module: AI navigator. Graceful-degrade: the manual completion path must always exist.

#### GROC-23 — Cancel an order
- **Category:** Alternate
- **Actor:** Primary user → `GroceryProvider`
- **Preconditions:** An order in any state up to and including `delivered` (cancellable until `reconciled`).
- **Action:** Cancel the order.
- **Expected outcome:** Order → `cancelled`; `GroceryOrderCancelledEvent` published; `GroceryProvider.cancel` invoked.
- **Variations:** cancel from `draft`; from `quoted`; from `placed`/`placed_partial`; from `awaiting_user_confirmation`; from `confirmed`; provider terminal-failure auto-cancel.
- **HLD ref:** grocery.md §`GroceryProvider.cancel`, §Order lifecycle ("cancelled (any state, until reconciled)").
- **Notes:** `[HLD-GAP]` — cancelling a `confirmed` (payment-captured) order touches the provider's payment/refund surface, which this module "never sees"; how a post-payment cancel reconciles with the provider is unspecified.

#### GROC-24 — Cancel after reconciled (illegal)
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Order already `reconciled` (or `archived`).
- **Action:** Attempt to cancel.
- **Expected outcome:** Rejected — illegal transition; cancellation is only valid "until reconciled".
- **Variations:** cancel a reconciled order; cancel an archived order; act on an archived order generally.
- **HLD ref:** grocery.md §Order lifecycle (cancellable until reconciled; archived excluded from default queries).
- **Notes:** Illegal-transition pathway.

#### GROC-25 — Login expired during a run
- **Category:** Error
- **Actor:** AI navigator / Primary user
- **Preconditions:** A provider order run; the persisted session/login has expired.
- **Action:** Automation attempts to act with a dead session.
- **Expected outcome:** Order stays `draft`; the user re-authenticates and re-runs. No partial order, no blind retry.
- **Variations:** expiry caught at quote; at place; user re-auths and the re-run succeeds; user re-auths and a *different* failure follows (cascades to other partial-failure rows).
- **HLD ref:** grocery.md §Partial-failure handling (login expired); §Tier 3 (rule 3).
- **Notes:** Cross-module: AI navigator session. Re-auth flow specifics → LLD.

#### GROC-26 — Delivery slot selection fails
- **Category:** Error / Edge
- **Actor:** AI navigator / Primary user
- **Preconditions:** Placing an order; automated delivery-slot selection fails.
- **Action:** Automation cannot pick a slot.
- **Expected outcome:** Order pauses at `placed`; the user picks a slot manually.
- **Variations:** no slots available at all `[HLD-GAP]` (distinct from "automation can't select" — unspecified); slot selection succeeds on manual pick; user abandons.
- **HLD ref:** grocery.md §Partial-failure handling (delivery slot selection fails).
- **Notes:** Designed-but-unbuilt. The no-slots-available case isn't separately classified.

#### GROC-27 — Provider down (unavailable)
- **Category:** Error
- **Actor:** `GroceryProvider` / system
- **Preconditions:** A provider operation in flight; the provider is down.
- **Action:** A `GroceryProvider` method throws `ProviderUnavailableException`.
- **Expected outcome:** Order marked `provider_unavailable`; a retry is scheduled; the failure is surfaced to the user; manual entry still works; price history goes stale faster (no quotes). `GroceryProviderUnavailableEvent` published.
- **Variations:** down during quote (Tier 3, 4 affected); down during place; down during status check; transient (retry succeeds) vs persistent (terminal → may end `cancelled`).
- **HLD ref:** grocery.md §Tier 3 (rule 3, `ProviderUnavailableException`), §Partial-failure handling (provider down), §Failure Modes, §Events.
- **Notes:** Cross-module: Notification (event). The module surfaces + degrades; "scheduled retry" vs "never retries blindly" distinction is per-failure-class. `[HLD-GAP]` — retry policy specifics deferred to LLD.

#### GROC-28 — AI navigator cost-cap exceeded mid-order
- **Category:** Error / Alternate
- **Actor:** AI navigator
- **Preconditions:** A provider run; the AI navigator hits the cost cap (`AiUnavailable`).
- **Action:** The cost cap fires during automation.
- **Expected outcome:** Falls back to manual basket entry — the shopping list is rendered printable/copyable and the user enters it on Tesco themselves; no automation runs. Graceful-degrade contract holds (the user can always complete manually).
- **Variations:** daily cap approached (scheduled refresh is the *first* thing skipped — GROC-31); monthly cap fired (no automation of any kind; system relies on `inflation_indexed` + `manual` for the period); cap hit mid-place (partial → manual completion).
- **HLD ref:** grocery.md §Partial-failure handling (`AiUnavailable`), §Cost-cap awareness, §Failure Modes.
- **Notes:** Cross-module: AI Service (cost cap). The fallback is the same surface as Tier 1/2 — assert the manual path renders.

### Tier 4 — Price History

#### GROC-29 — Price observation captured from each source
- **Category:** Happy
- **Actor:** System (per fulfilment / quote)
- **Preconditions:** A price-bearing event occurs.
- **Action:** A price is encountered via paid / quote / manual / manual_estimated / inflation_indexed.
- **Expected outcome:** One immutable observation row written `(ingredient_mapping_key, store, paid_unit_price, quantity, total_price, source, observed_at, confidence_weight, order_id_or_null)`; `PriceObservedEvent` published (audit/debug, no critical-path consumer in v1); the aggregate updates (confidence-weighted, ~3-month half-life decay).
- **Variations:** each of the five sources with its confidence weight (paid highest → inflation_indexed lowest); per-store row vs the cross-store aggregate; observation with `order_id` (paid/quote) vs without (manual).
- **HLD ref:** grocery.md §Tier 4 (capture sources, observation row, aggregation, events).
- **Notes:** Cross-module: every fulfilment path feeds this. Append-only — assert a new row, never a mutated one.

#### GROC-30 — View the aggregate price (estimate + confidence + range)
- **Category:** Happy
- **Actor:** Primary user / Planner (consumer)
- **Preconditions:** ≥1 observation for an ingredient.
- **Action:** Read the learned price for an ingredient (per-store and/or cross-store).
- **Expected outcome:** Point estimate (confidence-weighted decayed mean), confidence (Bayesian-shaped — rises with samples, falls with age), min/max range with timestamps, last-seen recency. The planner reads `(estimate, confidence)` pairs, not raw prices.
- **Variations:** single observation (wide confidence) vs many (tight); all observations old (low confidence, recency stale); one store vs cross-store aggregate; ingredient with only `inflation_indexed` data (lowest confidence).
- **HLD ref:** grocery.md §Tier 4 (aggregation).
- **Notes:** Cross-module: Planner cost sub-score. `[HLD-GAP]` — whether the per-ingredient learned price has a direct user-facing view (vs only appearing inside the cost projection) is not explicitly stated.

#### GROC-31 — Scheduled background refresh (weekly top-50)
- **Category:** Alternate
- **Actor:** Scheduler / AI navigator
- **Preconditions:** Provider configured; opt-in enabled; under the AI cost cap.
- **Action:** Weekly automation quotes the user's top-50 most-used ingredients.
- **Expected outcome:** A bounded, scoped quote pass; each observed price feeds Tier 4 as `quote`; keeps frequently-used ingredients fresh even when not being shopped this week.
- **Variations:** runs normally; daily cap approaching → this is the **first** thing skipped (non-essential); monthly cap fired → does not run at all; fewer than 50 distinct ingredients in recent use (smaller batch).
- **HLD ref:** grocery.md §Tier 4 (freshness mechanism 4: scheduled refresh), §Cost-cap awareness.
- **Notes:** Cross-module: AI navigator (cost cap). Designed-but-unbuilt (depends on provider automation). Opt-in.

#### GROC-32 — Inflation-indexed fallback for stale/absent prices
- **Category:** Alternate / Edge
- **Actor:** System
- **Preconditions:** An ingredient with stale or no observations.
- **Action:** The system synthesises a price by applying the configurable monthly inflation factor (default ~0.5%/month) to the last known price.
- **Expected outcome:** An `inflation_indexed` observation with lowest confidence; the planner knows it's a guess; corrects in roughly the right direction.
- **Variations:** stale-but-known last price (indexed forward) vs no anchor at all `[HLD-GAP]` (how indexing behaves with *zero* prior observations and no anchor is unspecified — cold start says indexing "carries less weight (no anchor)" but not the mechanism); indexed value obviously wrong → user overrides on next mark-bought.
- **HLD ref:** grocery.md §Tier 4 (freshness mechanism 2, cold start), §Failure Modes (inflation indexing wrong).
- **Notes:** The no-anchor mechanism is a finding. Configurable via `inflation_factor_monthly`.

#### GROC-33 — Cold-start price history (first 4-8 weeks)
- **Category:** Edge
- **Actor:** Primary user / system
- **Preconditions:** New user/household; sparse price history.
- **Action:** Generate plans / view cost projection during cold start.
- **Expected outcome:** Wide confidence intervals; "low confidence — costs may differ" surfaced; inflation indexing carries less weight (no anchor); the cost sub-score is mildly penalised in score-pulling power; UI nudges manual price entry. After ~2 months of steady shopping, cost becomes a meaningful planner input.
- **Variations:** week 1 (no data, projection "unknown"); week 4 (sparse, wide intervals); week 8+ (confidence good enough); manual-only household (relies entirely on `manual`/`manual_estimated`) vs provider household (quotes accelerate coverage).
- **HLD ref:** grocery.md §Tier 4 (cold start); provision-model.md §Budget (ramp-up), §Guardrails (relaxed staleness for first 8 weeks).
- **Notes:** Cross-module: Provisions (budget ramp-up), Planner (cost sub-score). Time/coverage-boundary edge.

#### GROC-34 — Multi-user household shares price history
- **Category:** Edge
- **Actor:** Household members
- **Preconditions:** A household with ≥2 members; one member shops.
- **Action:** One member's purchases/quotes write price observations.
- **Expected outcome:** Aggregates are per-household, not per-user; the whole household's planner cost intelligence benefits from one person's shop.
- **Variations:** two members shopping at different stores (per-store rows, shared cross-store aggregate); concurrent mark-bought by two members `[HLD-GAP]` (shared-inventory concurrent-edit semantics deferred to Household Model — provision-model.md Open Question; price-history concurrency not addressed at all).
- **HLD ref:** grocery.md §Tier 4 (multi-user households); provision-model.md §Open Questions (shared household inventory edits).
- **Notes:** Cross-module: Household. Concurrency is an explicit deferred question.

#### GROC-35 — Order archived 12 months after reconciliation
- **Category:** Edge
- **Actor:** Scheduler / system clock
- **Preconditions:** An order `reconciled` 12 months ago.
- **Action:** Archival sweep runs.
- **Expected outcome:** Order → `archived`; excluded from default queries; retained in storage (paid + quote price rows retained indefinitely; old manual raw rows may compact into aggregates).
- **Variations:** exactly at 12 months (boundary); an order reconciled 11 months ago (not archived); querying with vs without archived included.
- **HLD ref:** grocery.md §Order lifecycle (`archived`), §Data Volumes (retention).
- **Notes:** Time-boundary; assert exclusion-from-default-queries + retention.

### Flagship cross-module journey

#### GROC-36 — Plan generated → shopping list derived → Tesco order placed → user confirms → delivered with a substitution → user resolves it → inventory + price history updated → reconciled
- **Category:** Happy (flagship end-to-end)
- **Actor:** Primary user (+ Meal Planner, GroceryProvider/AI navigator, Provisions, Tier 4 as system actors)
- **Preconditions:** Authenticated; Tesco provider configured; an active plan with unmet ingredient demand; some price history exists.
- **Action (sequence):**
  1. The planner generates a plan; the grocery module exposes the **derived shopping list** (aggregated demand − inventory + low/out staples), with a cost projection from Tier 4 *(cross-module: Planner, Provisions, Tier 4)*.
  2. The user opts this shop into provider automation and runs a **quote** — prices refresh, `quote` rows feed Tier 4; order → `quoted`.
  3. The user **places** the order — the AI navigator drives the basket to checkout and **stops**; order → `placed` → `awaiting_user_confirmation`; checkout link surfaces *(cross-module: AI navigator; never auto-confirm)*.
  4. The user **confirms** in the Tesco UI — payment captured by the provider, delivery scheduled; order → `confirmed`.
  5. Delivery arrives with **one substitution** (Tesco swapped an unavailable item); order → `delivered`; the substitute is captured as a `pending_user_review` proposal — **never auto-accepted**.
  6. The user **resolves** the proposal (accept → substitute enters Provisions; or reject → original logged unmet, planner may re-optimise) *(cross-module: Provisions, Planner)*.
  7. With all proposals resolved, the order **reconciles**: delivered items added to Provisions inventory (expiry from category defaults), paid prices written to Tier 4 as `paid` (highest confidence); order → `reconciled`. Lifecycle events fire for Notification throughout.
- **Expected outcome:** A reconciled order; Provisions inventory reflects what actually arrived (substitute included if accepted); Tier 4 carries fresh `quote` + `paid` observations that sharpen the next plan's cost projection; an auditable event trail (`Quoted`/`Placed`/`Confirmed`/`Delivered`/`Substitution*`/`Reconciled`).
- **Variations:** no-substitution delivery (skip steps 5-6, straight to reconcile); substitution rejected (planner re-opt of the affected slot); partial-basket failure at step 3 (`placed_partial` → manual completion); AI cost-cap at step 3 (fall back to manual basket — the journey degrades to the Tier 1/2 manual path); provider down at step 2 (`provider_unavailable`, retry scheduled, manual entry still works); manual-only variant of the *whole* journey (no provider: list → manual mark-bought → inventory + `manual` price rows — Tiers 1, 2, 4 only).
- **HLD ref:** grocery.md §Tier 1, §Tier 3 (`GroceryProvider`, order lifecycle), §Substitution flow, §Tier 4 (`paid`/`quote`), §Events; provision-model.md §Inventory updates, §Expiry tracking, §Substitution tracking; system-overview.md §Provisions, §Mid-week re-optimisation.
- **Notes:** CROSS-MODULE backbone — touches **Planner** (demand + re-opt on rejected sub), **Provisions** (inventory + expiry defaults + supplier cache), **AI navigator** (Tesco automation), **Notification** (lifecycle events), and the internal **Tier 4** learning loop. The substitution leg and inventory-reconciliation leg are the integration-critical points; assertions span order state + Provisions inventory + Tier 4 observations + the event trail. Largely **designed-but-unbuilt** (provider automation) — Stage 2 will likely tag the provider legs `@pending` while the manual-only variant is buildable today.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

| # | Gap | Pathway |
|---|---|---|
| GG1 | **Shopping-list ownership contradiction.** grocery.md says the list is "a first-class output of the planner, *exposed by* the grocery module" and details its 6-step calculation; provision-model.md says the list "is calculated by deterministic code... **owned by the Planner module**" and "the implementation lives in the Planner." Which module owns the calculation logic vs merely exposes the result is contradictory across docs. | GROC-01 |
| GG2 | What the shopping list shows with **no active plan** (empty vs staples-only vs error) is unspecified. | GROC-02 |
| GG3 | Behaviour of **exporting an empty / staples-only list** (PDF/clipboard/email) unspecified. | GROC-04 |
| GG4 | Whether the UI exposes a **direct list-edit affordance** that redirects to plan/provisions, or none at all (list is derived, "never edited as source of truth"). | GROC-06 |
| GG5 | The **"if material"** threshold that decides whether under-marking triggers a planner re-optimisation is undefined. | GROC-12 |
| GG6 | Provider **login flow, credential storage**, and **whether a household member vs only the primary user** may connect/operate a provider are deferred/unspecified (Provisions concurrency also deferred to Household Model). | GROC-13, GROC-34 |
| GG7 | Bulk mark-bought **proportional price distribution with no anchor price** (items lacking a last-known cost) is unspecified; no validation on an implausible total spend. | GROC-10 |
| GG8 | No stated **confirmation-window timeout** for an order left in `awaiting_user_confirmation`. | GROC-17 |
| GG9 | **Cancelling a `confirmed` (payment-captured) order** touches the provider's payment/refund surface the module "never sees" — how a post-payment cancel reconciles with the provider is unspecified. | GROC-23 |
| GG10 | **"No delivery slots available"** is not classified separately from "automation can't select a slot." | GROC-26 |
| GG11 | Provider **retry-policy specifics** (cadence, max attempts, backoff) for `provider_unavailable` are deferred to LLD; "scheduled retry" vs "never retries blindly" is per-failure-class and not fully reconciled. | GROC-27 |
| GG12 | Whether the per-ingredient **learned price has a direct user-facing view** (vs only appearing inside the cost projection). | GROC-30 |
| GG13 | **Inflation indexing with zero prior observations / no anchor** — cold start says it "carries less weight (no anchor)" but the mechanism for a no-anchor price is unspecified. | GROC-32 |
| GG14 | **Price-history concurrency** for concurrent multi-user writes is not addressed (shared-inventory concurrent edits are an explicit Provisions Open Question; price history isn't covered at all). | GROC-34 |
| GG15 | **Concurrency between user manual edits to a draft basket and automation runs** is explicitly deferred to LLD (grocery.md §What This Doc Doesn't Cover) — no behavioural contract for the frontend yet. | GROC-15, GROC-16 |
| GG16 | **Currency / rounding / precision** of cost projections is deferred to LLD — affects every assertion that reads a money value. | GROC-03 |
