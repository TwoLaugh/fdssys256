# Provisions Domain — User-Pathway Catalogue

> Code-agnostic behavioural catalogue derived purely from the HLD design docs. Double duty: (a) source for E2E test scenarios; (b) behavioural spec for the frontend. No endpoints, HTTP verbs, class names, or DB tables — pure user/behaviour language. Where the HLD is silent on something a user would obviously need, it is flagged `[HLD-GAP]` rather than invented.

---

## 1. Domain Summary

Provisions is the **physical-world state model** — the third of the three data models the planner optimises against (alongside Preference = what the user *wants*, Nutrition = what the user's body *needs*). It holds *what the user has to work with*: ingredients in the house (inventory), kitchen tools (equipment), grocery spend constraints (budget), and a supplier price/pack-size cache. It is **passive state, not logic** — it stores and exposes data; the Meal Planner, Recipe Optimiser, Grocery module, Notification System, and Feedback System read and write it, but none of the optimisation logic lives here. In the three-loop architecture it is the **Provisions loop**: a set of constraints the week-scale planner and recipe-scale optimiser read as inputs. Its governing principle is **Everything Is Optional** — every feature (inventory, expiry, budget, equipment, waste, staples, supplier data) is additive; the system must handle every mixed on/off combination gracefully and never fail because a feature is disabled. Provisions models exactly **one location's** state; multi-location is a Household concern with one Provision instance per location.

## 2. Actors

| Actor | Role in this domain (per HLD) |
|---|---|
| **Primary user** | Owns and manages Provisions. Adds/removes/corrects inventory, logs waste, sets equipment, sets/adjusts budget, maintains staple statuses, corrects expiry dates and supplier prices, confirms cook-event and consumption deductions, picks batch-cook storage splits. Authority on their own physical reality — overrides need no approval. |
| **Household member** | Shares the same household Provisions. May give feedback on their own meals; may update shared inventory (concurrent-edit semantics deferred — see Open Questions). Write authority beyond own-meal feedback is largely unspecified `[HLD-GAP]`. |
| **Grocery module (writer/reader)** | Primary writer: confirmed-order items → inventory; actual prices → supplier cache; substitutions → substitution history. Reads the shopping list (from planner) and the supplier cache. The *action* arm; Provisions is the *state*. |
| **Meal Planner (reader)** | Primary consumer. Reads inventory (use-it-up, expiry scheduling), staples (flag out-of-stock), equipment (hard feasibility filter), budget (plan cost constraint), supplier data (pack sizes/prices). Owns the shopping-list calculation (reads Provisions read-only). Re-optimises remaining days on Provisions change. |
| **Recipe Optimiser (reader)** | Reads mainly equipment (propose equipment substitutions) and budget (propose cheaper cuts), plus inventory (use leftovers). |
| **Notification System (reader)** | Reads inventory for expiry alerts, defrost reminders, staple-replenishment notices. |
| **Feedback System (writer via AI)** | Routes cost/availability/equipment/waste feedback to Provisions (the only AI-mediated writer). Cost complaints → budget/sensitivity; availability → supplier-cache stock; equipment feedback → equipment list; waste observations → waste log. |
| **Nutrition Model logger (cross-writer)** | Standalone food logging of items that exist in inventory prompts a Provisions deduction ("Remove 1 banana from pantry?") — the canonical unplanned-consumption flow. |
| **System / scheduler (system actor)** | Category-based expiry defaulting, expiry/defrost alert windows, supplier-price staleness flagging/exclusion, 3-month raw-waste-entry rollup, staleness flag for items unupdated 3+ weeks, ramp-up relaxations. |

## 3. Action Space (frontend-spec backbone)

Flat, exhaustive list of every distinct user (or user-facing system) action the HLD permits. Each: verb-phrase + one-line description + HLD ref. Downstream pathways draw from this.

### Inventory — create / add
1. **Manually add an inventory item** — user adds an item bought elsewhere (market, gift) with quantity/unit/location/expiry; mapping inferred or user-confirmed. §Inventory updates (manual add); §Item structure (`source: manual_add | gift`).
2. **Auto-add from a confirmed grocery order** — grocery module writes ordered items (quantities, actual prices, substitutions, mapping key) into inventory. §Inventory updates (grocery order); §How It Gets Used (grocery writer).
3. **Add batch-cooked portions from a cook event** — a batch cook deducts raw ingredients and *adds* prepared portions, split fridge/freezer per a user-chosen storage split. §Inventory updates (batch cook detail).
4. **Add a staple via onboarding quick-start** — tick common cupboard basics; they seed the staples list (all `stocked`). §Bootstrapping (Inventory quick-start); §Spice rack and staples.

### Inventory — read / query
5. **View inventory (by location / whole pantry)** — list fridge/freezer/cupboard/spice-rack contents with quantities (approximate display), expiry, source. §Inventory; §Accuracy (approximate display).
6. **View a single inventory item** — open one item's full detail (quantity, expiry, source/provenance, mapping key, freezer metadata). §Item structure.
7. **View staple statuses** — see which staples are `stocked` / `low` / `out`. §Spice rack and staples.
8. **View expiry / freshness state** — see which items are within the expiry alert window. §Expiry tracking; §How It Gets Used (notifications).

### Inventory — update / correct (manual overrides)
9. **Correct an item quantity** — "actually I have 400g not 600g"; subsequent deductions adjust from corrected value. §User Overrides (inventory corrections).
10. **Correct / set an item expiry date** — override a category-default expiry; user value takes precedence. §Expiry tracking (user correction); §User Overrides.
11. **Change an item's storage location** — e.g. move chicken fridge→freezer (gains freezer metadata). §User Overrides (storage location).
12. **Manually remove an inventory item** — eaten as snack, gone off, given away. §Inventory updates (manual remove).
13. **Confirm a meal consumption deduction** — single-tap "I ate a pre-made/frozen portion" → deduct one portion. §Inventory updates (meal consumption).
14. **Confirm/correct a cook-event deduction** — review "Removed from pantry: …", correct quantities, confirm; partial-cook proportional adjust. §Inventory updates (cook-event deduction detail).
15. **Confirm a logger-prompted deduction** — accept/decline "Remove 1 banana from pantry?" prompted by standalone nutrition logging. §Inventory updates (snack/standalone consumption).
16. **Confirm a grocery substitution into inventory** — when a delivery substitutes an item, confirm so inventory reflects what actually arrived. §Substitution tracking.

### Inventory — staples
17. **Update a staple status** — single-tap `stocked`→`low`→`out` (and back). §Spice rack and staples; §User Overrides (staple status).
18. **Mark an item as a staple / non-staple** — set the `is_staple` flag (always-have vs one-off like saffron). §Spice rack and staples (`is_staple`).

### Waste
19. **Log a waste entry** — log a wasted item with quantity, reason, optional cost estimate/notes; deducts from inventory and records waste data. §Food Waste Tracking; §Inventory updates (waste log).
20. **View waste summaries** — weekly/monthly aggregates: total cost wasted, most-wasted items, common reasons. §Food Waste Tracking (aggregation).
21. **Correct a waste entry** — entries are immutable; a correction creates a *new* entry, never an edit. §Guardrails (waste immutable).

### Equipment
22. **Set / edit the equipment list** — tick available tools (with optional details); mostly static. §Equipment; §Bootstrapping (equipment checklist).
23. **View equipment** — see which tools are available/unavailable. §Equipment.
24. **Update equipment from feedback** — "I don't have a food processor" routes to update the equipment list. §How It Gets Used (Feedback System).

### Budget
25. **Set a budget during onboarding** — pick weekly target (£30/£50/£75/£100/custom) + price sensitivity (low/moderate/high); or skip entirely. §Bootstrapping (budget).
26. **Adjust budget target / tolerance / sensitivity** — change any time; effective next plan generation. §Budget (shape); §User Overrides (budget adjustments).
27. **View budget & spend tracking** — target, actual-so-far, remaining, this-week orders, rolling-4-week average; cost-estimate coverage % during ramp-up. §Budget tracking; §Budget (progressive enhancement).
28. **Adjust budget from a cost feedback signal** — cost complaint routed via Feedback may adjust target/sensitivity. §How It Gets Used (Feedback System, cost complaints).

### Supplier data
29. **View the supplier product cache** — cached products: price, price-per-unit, pack size, clubcard price, last-checked, substitution history. §Supplier Data (product cache).
30. **Auto-populate / refresh the supplier cache from an order** — first order caches products; subsequent orders reuse & refresh prices, add new products. §How supplier data builds up.
31. **Correct a supplier price** — "that chicken is £5 now"; user correction takes precedence until next order refresh. §User Overrides (supplier price corrections).
32. **Record a substitution into history** — a delivery substitution (accepted/declined) is appended to the product's substitution history. §Substitution tracking; §Supplier Data.
33. **Update supplier stock availability from feedback** — "couldn't find X at Tesco" updates stock availability in the cache. §How It Gets Used (Feedback System, availability).

### Cross-module consumers (Provisions as read source / data contract)
34. **Supply data to the shopping-list calculation** — Provisions provides inventory + staples (read-only) to the planner-owned formula `(plan ingredients − inventory) + staples at low/out`. §Shopping List Calculation.
35. **Supply constraints to the planner** — inventory/staples/equipment/budget/supplier data read for composition, expiry-driven & pantry-first scheduling, cost estimation. §How It Gets Used (Meal Planner).
36. **Supply constraints to the recipe optimiser** — equipment & budget (and inventory leftovers) read to drive equipment/cost substitution proposals. §How It Gets Used (Recipe Optimiser).
37. **Supply defrost lead-time to scheduling** — `defrost_lead_time_hours` drives the planner's pre-cook action + the "move to fridge" notification. §Freezer management.

## 4. State Models

### 4.1 Inventory item lifecycle (quantity-tracked: fridge / freezer / cupboard)
```
ADDED (grocery order | manual_add | batch_cook | gift)
   │  ingredient_mapping_key set (auto from order, inferred/confirmed on manual add)
   │  expiry_date set (category default | user correction | frozen_at + max_freeze_weeks)
   ▼
PRESENT  ── quantity decreases via: cook-event deduction · meal-consumption · manual remove · waste log · logger-prompt
   │  user overrides: correct quantity / expiry / storage location (move fridge↔freezer)
   │  ├─ quantity reaches 0 ───────────────► DEPLETED (removed from active inventory)
   │  ├─ past expiry / spoiled ───────────► (user logs waste or removes) → DEPLETED
   │  └─ no update 3+ weeks + planner depends on it → flagged STALE (still PRESENT, prompts confirmation)
   ▼
DEPLETED (no longer counted; waste/removal recorded where applicable)
```
Cross-cutting attributes (not states): `storage_location` ∈ {fridge, freezer, cupboard, spice_rack}; `source` ∈ {tesco_order, other_shop, manual_add, batch_cook, gift}; freezer items carry frozen_at / max_freeze_weeks / defrost_method / defrost_lead_time_hours.

**Illegal / disallowed transitions (→ error pathways):**
- Quantity may NEVER go below zero — a cook-event deduction that would underflow is floored at zero + the user is alerted (NOT a silent negative). §Guardrails (Inventory).
- Waste quantity may NOT exceed current inventory for that item *when inventory tracking is active* (unconstrained when tracking is off). §Guardrails (Waste).
- A logged waste entry may NOT be edited in place — corrections are new entries only. §Guardrails (Waste immutable).

### 4.2 Staple status lifecycle (status-tracked: spices / shelf-stable basics)
```
stocked  ──(use up / single-tap)──►  low  ──(use up / single-tap)──►  out
   ▲                                   │                               │
   └──────────── replenished (shop / single-tap) ◄─────────────────────┘
            low or out → auto-added to next shopping list (if is_staple)
            out → also flag recipes that need it
```
**Illegal transitions:** none explicitly defined `[HLD-GAP]` — whether status must step through `low` or can jump `stocked`→`out` directly, and whether non-staple items participate in auto-replenish (they don't, per §Spice rack and staples), are only partially specified.

### 4.3 Budget lifecycle
```
UNSET (user skipped onboarding budget) ── system plans with NO cost constraint, grocery still works
   │  user sets a budget
   ▼
ACTIVE (weekly_target > 0, tolerance_over, price_sensitivity)
   │  user adjusts target/tolerance/sensitivity (effective next plan generation)
   │  actual spend tracked vs target; rolling-4-week average maintained
   └─ actual > target+tolerance for 3+ consecutive weeks → system prompts "adjust your target?"
```
**Illegal transitions:** weekly target must be positive (zero/negative rejected); no upper limit enforced (system doesn't judge spending). §Guardrails (Budget).

### 4.4 Supplier-cache product price lifecycle (staleness)
```
FRESH (last_checked ≤ 2 weeks)            → used directly in cost estimates
   │ age > 2 weeks
   ▼
STALE-FLAGGED (2–4 weeks)                 → still used but flagged "estimated"
   │ age > 4 weeks
   ▼
EXCLUDED (> 4 weeks)                       → dropped from cost estimates; ingredient treated as unpriced
```
**Ramp-up exception:** for the first 8 weeks of use, staleness thresholds are relaxed — NO price is excluded (all still flagged "estimated" after 2 weeks). §Guardrails (Supplier cache). A user price correction supersedes the cache until the next order refresh.

---

## 5. Pathways

> Categories: **Happy** (default success), **Alternate** (valid non-default), **Error** (validation/not-found/illegal-transition/guardrail), **Edge** (empty/huge/boundary/duplicate/concurrent/feature-off). Cross-module touchpoints (Grocery, Planner, Optimiser, Feedback, Nutrition logger, Notifications) are noted; they will be fully detailed in their own domain files + the cross-journey file. Self-contained data + self-scoped assertions per the README rules — each scenario seeds its own household/inventory and asserts on *its own* items, never global counts.

### Inventory — add

#### PROV-01 — Manually add an inventory item
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated; a household/location exists; inventory tracking on.
- **Action:** Add an item bought elsewhere — name, category, storage location, quantity + unit, optional expiry, optional notes.
- **Expected outcome:** Item stored in inventory with `source: manual_add` (or `gift`); `added_at` set; `ingredient_mapping_key` inferred by the system or confirmed by the user; item now PRESENT and visible in the location's list (displayed approximately, e.g. "~400g").
- **Variations:** fridge item (with expiry) vs cupboard (long expiry, informational) vs freezer (gains freezer metadata) vs spice-rack (status-tracked, see PROV-12); mapping inferred cleanly vs ambiguous (user confirms); `source: gift`; with vs without notes; metric vs other units.
- **HLD ref:** provision-model.md §Inventory updates (manual add), §Item structure, §Accuracy (approximate display).
- **Notes:** Cross-module: Nutrition Model mapping cache (mapping-key inference). `[HLD-GAP]` — required vs optional fields for a manual add, and the exact mapping-confirmation UX, are not enumerated.

#### PROV-02 — Manual add with invalid data
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Authenticated; inventory tracking on.
- **Action:** Submit a manual add with bad data (negative/zero quantity, quantity without unit, missing name, missing storage location).
- **Expected outcome:** Validation rejects; nothing stored; field-level error.
- **Variations:** negative quantity; zero quantity; quantity but no unit; no name; no storage location; an unknown storage location value. `[HLD-GAP]` — the HLD never enumerates manual-add validation rules (only the zero-floor guardrail on deductions); the precise mandatory-field/validation set is a finding.
- **HLD ref:** provision-model.md §Item structure (implied required fields); §Guardrails.
- **Notes:** No external deps. Asserts "rejected"; the exact rule list is a GAP.

#### PROV-03 — Auto-add from a confirmed grocery order
- **Category:** Happy
- **Actor:** Grocery module (writer)
- **Preconditions:** A confirmed grocery (e.g. Tesco) order with structured line items.
- **Action:** Grocery module writes the order's items into inventory.
- **Expected outcome:** Each ordered item added with `source: tesco_order`, `source_ref` = order ref, `quantity`/`unit`, `cost_paid` (actual price), and `ingredient_mapping_key` set by the grocery→Nutrition-cache mapping; expiry populated from category-based defaults (orders carry no expiry); supplier cache updated (prices, pack sizes); substitutions reflected (see PROV-21). Reliability: High.
- **Variations:** all items map cleanly; an item with no category default (expiry left unset / best-effort) `[HLD-GAP]`; order with a substituted line (PROV-21); `source: other_shop` order.
- **HLD ref:** provision-model.md §Inventory updates (grocery order), §Expiry tracking (category defaults), §How supplier data builds up, §How It Gets Used (grocery writer).
- **Notes:** Cross-module: Grocery (order reconciliation), Nutrition cache (mapping), supplier cache. Order → inventory + supplier cache is one of the flagship's legs.

#### PROV-04 — Batch cook adds prepared portions (fridge/freezer split)
- **Category:** Happy
- **Actor:** Primary user (confirming a batch cook event)
- **Preconditions:** A recipe with servings > 1 marked cooked as a batch; inventory tracking on.
- **Action:** Confirm the cook; choose the storage split ("5 portions of curry — how many fridge vs freezer?").
- **Expected outcome:** Raw ingredients deducted (PROV-08); TWO inventory entries created from one event — fridge portions (`source: batch_cook`, expiry = today + recipe `max_fridge_days`) and freezer portions (full freezer metadata: frozen_at, max_freeze_weeks, defrost_method, defrost_lead_time_hours, source_recipe_id). Default split: enough fridge portions for the next 2–3 days within fridge shelf life, remainder to freezer; user can adjust.
- **Variations:** accept default split; user-adjusted split; all-to-freezer; all-to-fridge; servings=1 (degenerate — no batch split prompt) `[HLD-GAP]`; recipe missing `max_fridge_days`/`max_freeze_weeks` (falls to category defaults).
- **HLD ref:** provision-model.md §Inventory updates (batch cook detail), §Freezer management.
- **Notes:** Cross-module: Recipe (servings, max_fridge_days/max_freeze_weeks metadata), cook-event deduction. Two new inventory ids to assert.

#### PROV-05 — Onboarding staples quick-start
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** Onboarding; inventory empty.
- **Action:** Tick common cupboard basics (rice, pasta, oil, salt, pepper, soy sauce, flour, tinned tomatoes…).
- **Expected outcome:** Ticked items seed the staples list, all marked `stocked` and `is_staple: true`, so the first plan doesn't redundantly order them.
- **Variations:** tick all; tick none (empty staples, first plan orders basics); tick a subset; skip the step entirely.
- **HLD ref:** provision-model.md §Bootstrapping (Inventory quick-start), §Spice rack and staples.
- **Notes:** Cross-module: Planner (first shopping list). Self-scoped on this user's staple set.

### Inventory — read

#### PROV-06 — View inventory by location / whole pantry
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Inventory has items.
- **Action:** Open inventory (filtered by location or all).
- **Expected outcome:** Items listed with name, approximate quantity ("~400g"), unit, expiry state, source; spice-rack items show status not quantity; near-expiry items flagged.
- **Variations:** empty inventory (cold start — nothing to show, not an error); single location; all locations; huge pantry (boundary) `[HLD-GAP]` (no data-volume figures or pagination given for Provisions); items with no expiry (expiry tracking off).
- **HLD ref:** provision-model.md §Inventory, §Accuracy (approximate display), §Storage locations.
- **Notes:** Pure read. `[HLD-GAP]` — filterable fields/pagination unspecified.

#### PROV-07 — View a non-existent inventory item
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** No item with the given id (never existed or already DEPLETED).
- **Action:** Request that item id.
- **Expected outcome:** Not-found (or "depleted/removed") — nothing returned.
- **Variations:** never-existed id; a depleted id (distinguish "removed" from "never existed") `[HLD-GAP]` (whether depleted items remain readable in history is unstated); an item belonging to another household/location (not visible — Provisions is per-location).
- **HLD ref:** provision-model.md §Inventory; §Boundaries (multi-location separation).
- **Notes:** Self-scoped.

### Inventory — deductions (cook / consume / logger)

#### PROV-08 — Cook-event deduction with confirmation
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A planned/known recipe; its ingredients (mostly) present in inventory; tracking on.
- **Action:** Mark a meal cooked ("I cooked Chicken Stir Fry").
- **Expected outcome:** System computes the recipe ingredient list as a deduction and shows a confirmation ("Removed from pantry: 400g chicken thighs, 1 pepper, 200g rice, 2 tbsp soy sauce. Anything different?"); on confirm, quantities are deducted from inventory; deduction adjusts from any user-corrected quantity, not the original. Reliability: Medium (±20%, assumes recipe followed exactly).
- **Variations:** confirm as-is; correct quantities before confirming; partial cook ("I halved it" → proportional deduction); a batch cook (also adds portions, PROV-04); an ingredient that is a `stocked` staple (status-tracked — see PROV-11 interaction); ingredient not in inventory at all (deducts nothing / flags).
- **HLD ref:** provision-model.md §Inventory updates (cook-event deduction detail), §User Overrides (deduct from corrected value), §Accuracy.
- **Notes:** Cross-module: Recipe (ingredient list). Confirmation+correction is the key behaviour. Core leg of the flagship (PROV-40).

#### PROV-09 — Cook-event deduction would go below zero
- **Category:** Error / Edge
- **Actor:** Primary user / system (guardrail)
- **Preconditions:** Inventory shows less of an ingredient than the recipe consumes (tracking drift).
- **Action:** Confirm the cook.
- **Expected outcome:** Quantity floored at zero (NEVER negative) AND the user is alerted ("Inventory shows 0g chicken thighs after cooking — you may have had more than the system tracked"). Item becomes DEPLETED.
- **Variations:** exactly to zero (no alert needed — clean depletion); below zero (floor + alert); multiple ingredients underflow in one cook; underflow on a partial cook.
- **HLD ref:** provision-model.md §Guardrails (Inventory — quantity never below zero).
- **Notes:** Critical guardrail; assert floored-at-zero + alert, not a negative value.

#### PROV-10 — Confirm a meal consumption (pre-made / frozen portion)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A batch-cooked or frozen prepared-meal portion in inventory.
- **Action:** Single-tap confirm "I ate a portion."
- **Expected outcome:** One portion deducted (nothing cooked — pure consumption, distinct from a cook event); when the last portion is consumed the item becomes DEPLETED. Reliability: High (single-tap).
- **Variations:** fridge portion vs freezer portion; consume the last portion (DEPLETED); consume more portions than exist (zero-floor guardrail, PROV-09 analogue); a frozen portion consumed without the defrost reminder having fired `[HLD-GAP]` (no stated coupling).
- **HLD ref:** provision-model.md §Inventory updates (meal consumption).
- **Notes:** Distinct flow from cook-event; assert single-portion decrement.

#### PROV-11 — Logger-prompted inventory deduction (standalone consumption)
- **Category:** Alternate
- **Actor:** Nutrition Model logger → Primary user (confirming) → Provisions
- **Preconditions:** User logs a standalone food (snack/drink) via the nutrition logger; that item exists in inventory.
- **Action:** Logger detects the item is in inventory and prompts "Remove 1 banana from pantry?"
- **Expected outcome:** On accept, the item is deducted from inventory (keeps inventory roughly accurate without manual management); on decline, inventory is unchanged.
- **Variations:** accept the prompt; decline; logged item NOT in inventory (no prompt, nothing deducted); logged quantity > inventory (zero-floor); logged item is a status-tracked staple (PROV-17 interaction). 
- **HLD ref:** provision-model.md §Inventory updates (snack/standalone consumption — canonical cross-model flow).
- **Notes:** Cross-module: Nutrition logger → Provisions. The HLD explicitly says the Nutrition Model doc should cross-reference this — cross-journey candidate.

### Inventory — corrections / overrides

#### PROV-12 — Correct an inventory quantity
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A PRESENT inventory item.
- **Action:** Adjust the quantity ("actually I have 400g, not 600g").
- **Expected outcome:** Quantity updated; override logged with a timestamp (auditable) but no approval flow; subsequent cook-event deductions adjust from the corrected value, not the original.
- **Variations:** correct down; correct up; correct to zero (→ DEPLETED?) `[HLD-GAP]` (whether a zero correction depletes vs needs explicit removal is unstated); correct then cook (deduction from corrected base — assert this).
- **HLD ref:** provision-model.md §User Overrides (inventory corrections), §Inventory updates (deduct from corrected value).
- **Notes:** The corrected-base-deduction precedence is a key assertion. Self-scoped.

#### PROV-13 — Correct / set an item expiry date
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An item with a category-default (or absent) expiry.
- **Action:** Override the expiry ("this chicken says use by Friday").
- **Expected outcome:** Expiry updated to the user value; user corrections take precedence over category defaults; planner/notifications use the corrected date.
- **Variations:** tighten expiry (sooner); loosen (later); set an expiry on an item that had none; correct expiry on a freezer item (vs frozen_at + max_freeze_weeks derivation) `[HLD-GAP]` (interaction between a manual freezer expiry and the derived one is unstated).
- **HLD ref:** provision-model.md §Expiry tracking (user correction), §User Overrides.
- **Notes:** Cross-module: Notification (expiry alert window), Planner (expiry scheduling). Self-scoped.

#### PROV-14 — Change an item's storage location
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A PRESENT item.
- **Action:** Move it between locations (e.g. fridge → freezer).
- **Expected outcome:** Location updated; moving INTO the freezer requires/derives freezer metadata (frozen_at, max_freeze_weeks, defrost fields) and shifts expiry to the frozen derivation; moving OUT of the freezer reverts to fridge/cupboard expiry semantics `[HLD-GAP]` (freezer→fridge metadata handling and expiry recomputation not specified).
- **Variations:** fridge→freezer (gain metadata); freezer→fridge (lose/keep metadata?); fridge→cupboard; move a status-tracked spice between locations (degenerate).
- **HLD ref:** provision-model.md §User Overrides (storage location), §Freezer management.
- **Notes:** The metadata gain/loss on a move is under-specified — flagged.

#### PROV-15 — Manually remove an inventory item
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A PRESENT item.
- **Action:** Remove it (eaten as a snack, gone off, given away) — without logging it as waste.
- **Expected outcome:** Item → DEPLETED / removed from active inventory; high reliability (user-entered). No waste record (distinct from PROV-18).
- **Variations:** remove whole item; remove part of a quantity `[HLD-GAP]` (whether manual-remove supports a partial amount vs full removal is unstated); remove an already-depleted item (idempotent/no-op or error).
- **HLD ref:** provision-model.md §Inventory updates (manual remove).
- **Notes:** Contrast with waste log (PROV-18) which *records* the loss.

#### PROV-16 — Confirm a grocery substitution into inventory
- **Category:** Alternate
- **Actor:** Grocery module / Primary user (confirming)
- **Preconditions:** A delivered order substituted an ordered item for another.
- **Action:** Confirm the substitution (user accepted what arrived).
- **Expected outcome:** Inventory reflects the *substituted* item (what actually arrived), not the ordered one; the substitution is appended to the product's substitution history (`accepted: true/false`). Keeps inventory accurate.
- **Variations:** accepted substitution (inventory shows substitute); declined/refused substitution (item not added; history records `accepted: false`); substitution of a near-identical product vs a meaningfully different one.
- **HLD ref:** provision-model.md §Substitution tracking, §Supplier Data (substitution_history), §How It Gets Used (grocery writer).
- **Notes:** Cross-module: Grocery delivery reconciliation. Two writes: inventory + supplier history.

### Inventory — staples

#### PROV-17 — Update a staple status (stocked / low / out)
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A status-tracked staple exists.
- **Action:** Single-tap change status (e.g. `stocked` → `low`, or `low` → `out`).
- **Expected outcome:** Status updated (no friction, no approval); if `is_staple` and status hits `low` or `out`, the item is auto-added to the next shopping list regardless of plan need; `out` additionally flags recipes that need it.
- **Variations:** stocked→low; low→out; out→stocked (replenished); jump stocked→out directly `[HLD-GAP]` (whether direct jumps are allowed); update a NON-staple to low/out (tracked but NOT auto-replenished, e.g. saffron); status update on a quantity-tracked item (degenerate — those use quantity, not status).
- **HLD ref:** provision-model.md §Spice rack and staples, §User Overrides (staple status), §Shopping List Calculation (staples at low/out).
- **Notes:** Cross-module: Planner shopping list (auto-replenish), Recipe (flag recipes needing an `out` staple). Auto-add-to-shop is the key assertion.

#### PROV-18 — Mark an item as staple / non-staple
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** An item exists (or is being added).
- **Action:** Set/clear `is_staple`.
- **Expected outcome:** A staple participates in auto-replenishment when low/out; a non-staple is tracked but never auto-replenished.
- **Variations:** mark a one-off (saffron) non-staple; promote a frequently-bought item to staple; staples that "build naturally from grocery orders" `[HLD-GAP]` (the auto-promotion-from-orders rule is mentioned but its trigger/threshold is unspecified).
- **HLD ref:** provision-model.md §Spice rack and staples (`is_staple`, builds naturally).
- **Notes:** Auto-build-from-orders heuristic is a GAP.

### Waste

#### PROV-19 — Log a waste entry
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Tracking on (or off — see variation); an item was thrown away.
- **Action:** Log waste: item, quantity, reason, optional cost estimate, optional notes.
- **Expected outcome:** Waste entry recorded AND (if the item is in inventory) deducted from inventory; entry is immutable; feeds the waste-reduction loop and (for `didn't_like`) routes to the Feedback System as a preference signal.
- **Variations:** reason = `expired` / `leftover_not_eaten` / `didn't_like` (→ Feedback/preference) / `spoiled_early` (→ tighten that category's expiry default) / `made_too_much`; with vs without cost estimate; inventory tracking off (unconstrained — accepts the estimate without validation, no deduction guard).
- **HLD ref:** provision-model.md §Food Waste Tracking, §Waste reasons, §Inventory updates (waste log), §Expiry tracking (spoiled_early refines defaults).
- **Notes:** Cross-module: Feedback (didn't_like), Planner (waste patterns), expiry-default refinement. Reason routing matters.

#### PROV-20 — Waste quantity exceeds inventory (tracking on)
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Inventory tracking ACTIVE; logged waste quantity > current inventory for that item.
- **Action:** Submit the over-quantity waste log.
- **Expected outcome:** Rejected — waste cannot exceed current inventory when tracking is on (prevents logging more waste than exists).
- **Variations:** exactly equal to inventory (allowed — depletes); just over (rejected); tracking OFF (the same over-quantity is ACCEPTED unconstrained — the toggle flips the rule). 
- **HLD ref:** provision-model.md §Guardrails (Waste logging — quantity ≤ inventory when tracking active).
- **Notes:** The on/off toggle changing the validation rule is the key boundary; pairs with the Everything-Is-Optional principle.

#### PROV-21 — Correct a waste entry (immutability)
- **Category:** Error / Alternate
- **Actor:** Primary user
- **Preconditions:** A waste entry already logged.
- **Action:** Attempt to edit the existing entry.
- **Expected outcome:** In-place edit is NOT permitted; a correction must create a NEW entry (the original is retained). Assert two entries exist after a "correction," not one edited entry.
- **Variations:** correct quantity (new entry); correct reason (new entry); attempt a true in-place edit (rejected). `[HLD-GAP]` — whether the corrective new entry should be a negative/reversal entry or how the pair nets out in summaries is unstated.
- **HLD ref:** provision-model.md §Guardrails (Waste entries immutable).
- **Notes:** Immutability invariant; the netting semantics are a GAP.

#### PROV-22 — View waste summaries
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Some waste history.
- **Action:** Open waste summaries.
- **Expected outcome:** Weekly/monthly aggregates: total cost wasted, most-wasted items, most common reasons; raw entries retained 3 months then rolled into weekly summaries.
- **Variations:** no history (empty — system "cannot provide recommendations until ~4+ weeks"); rich history; entries older than 3 months (raw gone, only weekly summaries remain); a pattern (spinach wasted 3 weeks running) surfacing a planner adjustment.
- **HLD ref:** provision-model.md §Food Waste Tracking (aggregation, retention), §Bootstrapping (waste — 4+ weeks).
- **Notes:** Cross-module: Planner (pattern-driven quantity/scheduling adjustment). Time-boundary on the 3-month rollup.

### Equipment

#### PROV-23 — Set / edit the equipment list
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Authenticated (onboarding or settings).
- **Action:** Tick available tools, optionally with details (e.g. "air_fryer, 4L"; "blender, stick only").
- **Expected outcome:** Equipment list stored; used as a HARD filter — recipes requiring unavailable equipment are excluded from plan composition entirely; mostly static thereafter.
- **Variations:** onboarding checklist; later edit (add/remove a tool); set `available: false` with details; equipment list never set (planner assumes all common equipment available — user may see infeasible recipes).
- **HLD ref:** provision-model.md §Equipment, §Bootstrapping (equipment), §Core Principle (equipment disabled → assume all common available).
- **Notes:** Cross-module: Planner (hard filter), Optimiser (equipment substitution proposals). Hard-filter behaviour is the assertion.

#### PROV-24 — Update equipment from feedback
- **Category:** Alternate
- **Actor:** Feedback System (AI) → Provisions
- **Preconditions:** User gives feedback like "I don't have a food processor."
- **Action:** Feedback classifier routes the equipment signal to Provisions.
- **Expected outcome:** The equipment list updates (that tool → unavailable); future composition excludes recipes needing it.
- **Variations:** mark a tool unavailable; mark one available ("I got an air fryer"); feedback that's ambiguous between equipment and preference (e.g. "I never use the slow cooker" — equipment exists but disliked → Preference, not Provisions) `[HLD-GAP]` (the equipment-exists-vs-preferred boundary is stated in principle but the routing of borderline phrasing is not).
- **HLD ref:** provision-model.md §How It Gets Used (Feedback System — equipment), §Boundaries (Equipment vs cooking preferences).
- **Notes:** Cross-module: Feedback (AI routing). The exists-vs-preferred distinction is the subtle part.

#### PROV-25 — Recipe Optimiser proposes an equipment substitution
- **Category:** Alternate
- **Actor:** Recipe Optimiser (reader) → Primary user (if user recipe)
- **Preconditions:** A recipe requires equipment the user lacks (e.g. food processor), but a held tool (stick blender) could do a step.
- **Action:** Optimiser reads equipment and proposes a substitution ("a stick blender would work for this step").
- **Expected outcome:** A substitution proposal is offered ONLY if it doesn't fundamentally change the recipe; otherwise the recipe stays excluded by the hard filter.
- **Variations:** viable substitution proposed; no viable substitution (recipe excluded); substitution that *would* change the dish character (not offered — defers to Recipe branching rules).
- **HLD ref:** provision-model.md §Equipment (optimiser substitutions), §How It Gets Used (Recipe Optimiser).
- **Notes:** Cross-module: Recipe Optimiser/Engine. Provisions only supplies the equipment read; the proposal lives in the optimiser/recipe domain.

### Budget

#### PROV-26 — Set a budget during onboarding
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** Onboarding.
- **Action:** Pick a weekly target (£30/£50/£75/£100/custom) + price sensitivity (low/moderate/high).
- **Expected outcome:** Budget ACTIVE; planner gains a cost constraint (subject to ramp-up — see PROV-30); spend tracking begins.
- **Variations:** preset target; custom target; each sensitivity level; skip entirely (budget UNSET — system plans with NO cost constraint, grocery still works).
- **HLD ref:** provision-model.md §Bootstrapping (budget), §Budget, §Core Principle (budget disabled).
- **Notes:** Cross-module: Planner (cost sub-score). The skip path (UNSET) is a first-class valid state.

#### PROV-27 — Adjust budget target / tolerance / sensitivity
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A budget exists (or is being set).
- **Action:** Change target, tolerance_over, or price_sensitivity.
- **Expected outcome:** Updated; takes effect on the NEXT plan generation (not retroactively on the current plan).
- **Variations:** raise target; lower target; change tolerance; change sensitivity low→high; change mid-week (effective next generation, not this week) `[HLD-GAP]` (whether a mid-week change can trigger re-optimisation of remaining days is not stated, though Provisions changes generally do).
- **HLD ref:** provision-model.md §User Overrides (budget adjustments — next plan generation), §Budget.
- **Notes:** Timing ("next plan generation") is the assertion; mid-week re-opt interaction is a GAP.

#### PROV-28 — Set an invalid budget target
- **Category:** Error
- **Actor:** Primary user
- **Preconditions:** Setting/adjusting budget.
- **Action:** Submit a non-positive weekly target (zero or negative).
- **Expected outcome:** Rejected — weekly target must be positive. No upper bound is enforced (a huge target is accepted; the system doesn't judge spending).
- **Variations:** zero target (rejected); negative target (rejected); extremely large target (accepted — no upper limit); negative tolerance_over `[HLD-GAP]` (tolerance validation not specified).
- **HLD ref:** provision-model.md §Guardrails (Budget — target must be positive, no upper limit).
- **Notes:** Validation pathway; the no-upper-limit assertion is explicit.

#### PROV-29 — View budget & spend tracking
- **Category:** Happy
- **Actor:** Primary user
- **Preconditions:** A budget set; ≥0 orders this week.
- **Action:** Open budget/spend view.
- **Expected outcome:** Shows target, actual-so-far, remaining, this-week orders, and rolling-4-week average; cost estimates shown as ranges (~£45–55) not point values; during ramp-up shows coverage % ("based on 60% of ingredients with known prices").
- **Variations:** week with no orders yet (actual 0, remaining = target); multiple orders this week; early weeks (low coverage %, "partial estimate"); after 6+ weeks (±10–15%, ranges).
- **HLD ref:** provision-model.md §Budget tracking, §Budget (progressive enhancement), §Accuracy (ranges, coverage %).
- **Notes:** Display honesty (ranges + coverage) is the assertion, not exact numbers.

#### PROV-30 — Budget enforcement disabled during cost-estimate ramp-up
- **Category:** Edge
- **Actor:** System / Planner (reading Provisions)
- **Preconditions:** Budget set, but supplier cache sparse (first several weeks; <~4–6 weeks of ordering).
- **Action:** Planner attempts a cost-constrained plan.
- **Expected outcome:** Because most ingredients have no cached price, cost estimation is effectively disabled / partial; budget enforcement is soft; the system communicates the limitation ("Cost estimate based on 60% of ingredients with known prices — accuracy will improve"). Estimates become accurate after ~4–6 weeks.
- **Variations:** week 1 (near-zero coverage — no enforcement); week 3 (partial); week 6+ (accurate enforcement); supplier-cache staleness ramp-up exception (first 8 weeks — no price excluded, PROV-34).
- **HLD ref:** provision-model.md §Budget (progressive enhancement), §Guardrails (Supplier cache ramp-up exception).
- **Notes:** Cross-module: Planner cost sub-score, Grocery (supplier cache coverage). Time-/coverage-boundary.

#### PROV-31 — Persistent over-budget prompt
- **Category:** Edge
- **Actor:** System / Planner
- **Preconditions:** Actual spend exceeds target + tolerance for 3+ consecutive weeks.
- **Action:** The 3rd consecutive over-budget week completes.
- **Expected outcome:** System prompts "Your spending consistently exceeds your target. Would you like to adjust it [or prioritise cheaper recipes]?" — never silently changes the target.
- **Variations:** exactly 3 weeks (boundary — prompt fires); 2 weeks then under (no prompt); within tolerance (no prompt); rolling-4-week average framing ("£65 vs £50 target") `[HLD-GAP]` (the 3-consecutive-weeks guardrail and the 4-week-average framing are two different triggers in the doc and aren't reconciled).
- **HLD ref:** provision-model.md §Guardrails (Budget — 3+ consecutive weeks), §Budget tracking (rolling average prompt).
- **Notes:** Two over-budget triggers (consecutive-weeks vs rolling-average) are stated separately — a consistency GAP.

#### PROV-32 — Adjust budget from a cost feedback signal
- **Category:** Alternate
- **Actor:** Feedback System (AI) → Provisions
- **Preconditions:** User feedback "this week was too expensive."
- **Action:** Feedback classifier routes the cost complaint to Provisions.
- **Expected outcome:** May adjust the budget target or price sensitivity (then downstream the planner re-optimises). 
- **Variations:** adjusts sensitivity (moderate→high); adjusts target down; the same feedback split with a preference signal ("too expensive AND too bland" → Provisions + Preference). `[HLD-GAP]` — whether an AI-mediated cost complaint *directly* mutates the budget vs only *proposes* a change is ambiguous (User Overrides says budget changes need no approval, but other AI writes to data models elsewhere use propose/accept).
- **HLD ref:** provision-model.md §How It Gets Used (Feedback System — cost complaints); system-overview.md §Feedback System (provisions destination).
- **Notes:** Cross-module: Feedback AI routing. The propose-vs-apply ambiguity is a finding.

### Supplier data

#### PROV-33 — Auto-populate / refresh the supplier cache from orders
- **Category:** Happy
- **Actor:** Grocery module
- **Preconditions:** A grocery order placed.
- **Action:** Grocery module searches products, selects them, caches data (first order) or reuses + refreshes prices and adds new products (subsequent orders).
- **Expected outcome:** Product cache entries created/updated: price, price_per_unit, unit, pack_size, category, last_checked, clubcard_price, ingredient_mapping_key. Over time the cache covers most common ingredients (search rarely needed). Same caching pattern as the Nutrition mapping cache.
- **Variations:** first order (all new); subsequent (reuse + refresh + a new ingredient added); a price that changed since last order (refreshed); pack-size selection = smallest sufficient pack (v1 — no cross-recipe optimisation).
- **HLD ref:** provision-model.md §How supplier data builds up, §Pack size awareness (v1), §Supplier Data.
- **Notes:** Cross-module: Grocery (product search/select). `[HLD-GAP]` — primary-doc says the cache is "scraped"/searched from supplier products, but system-overview.md §Data Model 3 says cost optimisation is "learned from the user's actual paid prices… not scraped from supplier feeds." The two docs describe different price-sourcing models — flagged.

#### PROV-34 — Supplier price staleness flagging / exclusion
- **Category:** Edge
- **Actor:** System (cost estimation)
- **Preconditions:** Cached prices of varying age.
- **Action:** A cost estimate is computed.
- **Expected outcome:** Prices ≤2 weeks → used directly; 2–4 weeks → still used but flagged "estimated"; >4 weeks → EXCLUDED, ingredient treated as unpriced and the gap noted. EXCEPTION: in the first 8 weeks of use, NO price is excluded (all flagged "estimated" after 2 weeks) so staleness doesn't undermine ramp-up.
- **Variations:** exactly 2 weeks (boundary — flagged); exactly 4 weeks (boundary — excluded, or still included if within the 8-week ramp-up); within the 8-week ramp-up window (nothing excluded); a user-corrected price (supersedes the cache until next order refresh).
- **HLD ref:** provision-model.md §Guardrails (Supplier cache — 2wk flag / 4wk exclude / 8wk ramp-up exception).
- **Notes:** Two interacting boundaries (price age + ramp-up week). Time-boundary heavy.

#### PROV-35 — Correct a supplier price
- **Category:** Alternate
- **Actor:** Primary user
- **Preconditions:** A cached product.
- **Action:** Correct the price ("that chicken is £5 now, not £4.50").
- **Expected outcome:** Cache updated; the user correction takes precedence until the next grocery order refreshes that price; logged with a timestamp (auditable), no approval.
- **Variations:** correct upward; correct downward; correct then place an order (order refresh overrides the manual correction — assert this precedence); correct a product not yet cached `[HLD-GAP]` (whether you can pre-seed a price for an uncached product is unstated).
- **HLD ref:** provision-model.md §User Overrides (supplier price corrections).
- **Notes:** The correction-vs-order-refresh precedence is the assertion.

#### PROV-36 — Update supplier stock availability from feedback
- **Category:** Alternate
- **Actor:** Feedback System (AI) → Provisions
- **Preconditions:** User feedback "couldn't find X at Tesco."
- **Action:** Feedback classifier routes the availability signal to Provisions.
- **Expected outcome:** Stock availability for that product is updated in the supplier cache; downstream the planner/grocery avoid relying on it.
- **Variations:** mark an item unavailable; the item later reappears in an order (availability refreshed); availability feedback that's really a substitution event (PROV-16). `[HLD-GAP]` — the cache shape (§Product cache) has no explicit `availability`/`in_stock` field, yet feedback is said to update "stock availability" — the field is implied but undefined.
- **HLD ref:** provision-model.md §How It Gets Used (Feedback System — availability).
- **Notes:** Cross-module: Feedback AI routing. The missing availability field is a schema GAP.

### Cross-module data contract

#### PROV-37 — Supply inventory + staples to the shopping-list calculation
- **Category:** Happy
- **Actor:** Meal Planner (caller, read-only) ← Provisions
- **Preconditions:** A plan being generated; inventory and staples populated.
- **Action:** Planner runs `Shopping list = (plan ingredients − current inventory) + staples at low/out`, reading Provisions read-only.
- **Expected outcome:** For each plan ingredient, inventory is subtracted (have 200g, need 600g → list 400g; have 800g → list 0g); staples at `low`/`out` are added regardless of plan need; each list item is mapped to a supplier product (smallest sufficient pack — surplus stays in inventory after delivery); cost summed (flagged partial if some prices missing). Provisions is NOT mutated by this read.
- **Variations:** inventory fully covers an ingredient (0 on list); partial cover; a `low` staple not needed by the plan (still added); pack-size rounding leaves surplus (stays in inventory); empty inventory (full list); inventory tracking off (planner assumes empty pantry — full list every week).
- **HLD ref:** provision-model.md §Shopping List Calculation (formula, steps), §Pack size awareness, §Core Principle (inventory disabled).
- **Notes:** Cross-module: Planner (owns the formula), Grocery (consumes the list). Provisions read-only here — assert no inventory change from a list calc.

#### PROV-38 — Defrost lead-time drives a scheduling reminder
- **Category:** Alternate
- **Actor:** Provisions (data) → Meal Planner + Notification System
- **Preconditions:** A frozen item with `defrost_lead_time_hours` is scheduled for a meal.
- **Action:** Planner schedules the frozen meal; the system reads the defrost lead time.
- **Expected outcome:** A "move to fridge" reminder is triggered at `meal_time − defrost_lead_time_hours` (e.g. Wednesday dinner → Tuesday-evening reminder); the planner treats defrost as a pre-cook action with a lead time.
- **Variations:** overnight_fridge (12h); quick-defrost; microwave (near-zero lead); a frozen meal scheduled with insufficient lead time before the meal `[HLD-GAP]` (what the planner does when the defrost window can't fit before the slot is unstated); user's defrost tolerance lives in the Preference Model, the item's actual requirement lives here (boundary).
- **HLD ref:** provision-model.md §Freezer management, §How It Gets Used (Notification System — defrost reminders).
- **Notes:** Cross-module: Planner (pre-cook actions), Notification, Preference (defrost tolerance). Defrost-window-too-short is a GAP.

### Edge / stale-data / feature-off

#### PROV-39 — Stale-inventory flag (no update 3+ weeks)
- **Category:** Edge
- **Actor:** System → Primary user
- **Preconditions:** An inventory item unupdated for 3+ weeks; the planner depends on it for a plan.
- **Action:** Plan generation references the stale item.
- **Expected outcome:** The item is flagged as potentially stale and the user is prompted ("Your plan relies on rice added 4 weeks ago — do you still have it?") — not silently trusted, not silently dropped.
- **Variations:** exactly 3 weeks (boundary); item updated 2 weeks ago (not flagged); stale item the plan does NOT depend on (no prompt); user confirms still-have vs marks gone (→ deduction/removal).
- **HLD ref:** provision-model.md §Guardrails (Inventory — 3+ weeks stale flag), §Accuracy (degrades daily).
- **Notes:** Time-boundary; only flags when the plan depends on the item.

#### PROV-40 — Mixed feature-on/off state (Everything Is Optional)
- **Category:** Edge
- **Actor:** Primary user / Planner
- **Preconditions:** Some Provisions features enabled, others not (e.g. inventory ON, budget OFF; or budget ON, expiry OFF).
- **Action:** Generate a plan / use the system with a partial Provisions setup.
- **Expected outcome:** The system NEVER fails because a feature is off; each feature reads its own data and gracefully ignores missing data from others; the planner adjusts its optimisation to whatever is available (inventory-off → assume empty pantry; expiry-off → no freshness scheduling; budget-off → no cost constraint; equipment-off → assume all common tools; waste-off → no learning; staples-off → manual replenishment; supplier-off → no cost/pack estimation).
- **Variations:** inventory on / budget off; budget on / expiry off; staples on / everything else off; all features off (system still produces a valid full-shopping-list plan); every feature on (full optimisation).
- **HLD ref:** provision-model.md §Core Principle: Everything Is Optional (the feature on/off table).
- **Notes:** This is the domain's defining invariant — assert "valid plan, no failure" across the on/off matrix. High-value soak scenario (combinatorial).

#### PROV-41 — Concurrent household inventory edits
- **Category:** Edge / Error
- **Actor:** Two household members
- **Preconditions:** Shared household Provisions; two members edit the same item simultaneously ("I used the last eggs" from both).
- **Action:** Both submit conflicting updates to the same inventory item.
- **Expected outcome:** `[HLD-GAP]` (explicit Open Question) — likely last-write-wins with a notification ("Partner marked eggs as used — you may need to update your entry"), but the HLD explicitly DEFERS this to Household Model design and does not specify the rule.
- **Variations:** simultaneous depletion of the last unit; one edits quantity while the other removes; one in location A's instance vs another (separate Provision instances — no conflict). 
- **HLD ref:** provision-model.md §Open Questions (Shared household inventory edits); §Boundaries (multi-location → separate Provision instances).
- **Notes:** Explicitly unresolved Open Question — flagged, not invented. Soak-mode concurrency candidate.

#### PROV-42 — Skipped-meal ingredients remain in inventory
- **Category:** Edge
- **Actor:** Primary user / Planner
- **Preconditions:** A planned meal whose ingredients were already purchased; user skips it (ate out / not hungry).
- **Action:** Mark the meal skipped (no cook event fires).
- **Expected outcome:** The purchased ingredients REMAIN in inventory (no deduction); the planner's mid-week re-optimisation reschedules them into remaining days, with expiry-driven urgency if perishable.
- **Variations:** skip a meal with perishable ingredients (urgent reschedule); skip with shelf-stable ingredients (low urgency); skip the last meal of the week (ingredients carry over) `[HLD-GAP]` (carry-over across week boundaries into the next plan is not stated).
- **HLD ref:** provision-model.md §Inventory updates (skipped meals), §How It Gets Used (pantry-first re-optimisation).
- **Notes:** Cross-module: Planner mid-week re-optimisation. Assert NO deduction on skip.

### Flagship cross-module journey

#### PROV-43 — Order delivered → inventory + supplier cache populated → recipe cooked → ingredients deducted → one ingredient runs out → flagged & queued for next shop
- **Category:** Happy (flagship end-to-end)
- **Actor:** Primary user (+ Grocery module, Meal Planner, Notification System as system actors)
- **Preconditions:** Authenticated; a household/location; a confirmed grocery order for a week's plan; inventory + budget + staples tracking on.
- **Action (sequence):**
  1. A confirmed grocery order is delivered; the grocery module auto-adds items to inventory (`source: tesco_order`, actual `cost_paid`, mapping keys) and populates/refreshes the **supplier cache** (prices, pack sizes); category-based expiry defaults are applied *(cross-module: Grocery)*.
  2. Spend tracking updates the week's `actual_so_far` against the budget target; the supplier cache feeds future cost estimates *(cross-module: Budget/Planner)*.
  3. A few days later the user marks a recipe **cooked**; the system shows the cook-event deduction confirmation and, on confirm, **deducts** the recipe's ingredients from inventory (adjusting from any user-corrected quantities).
  4. One ingredient's deduction would drive it below zero / to empty, and a **staple** spice (e.g. cumin) is now `out`; quantity is floored at zero (with the alert) and the staple status flips to `out` *(guardrail + staple lifecycle)*.
  5. Because the staple is `out` (and `is_staple`), it is **auto-added to the next shopping list** regardless of plan need; recipes needing it are flagged *(cross-module: Planner shopping-list calc)*.
  6. The notification system surfaces an expiry alert for a near-expiry fridge item from the same order, and a defrost reminder for any frozen portion scheduled mid-week *(cross-module: Notification)*.
- **Expected outcome:** Inventory reflects the delivery minus the cook (no negative quantities), the supplier cache and spend tracking are populated, the depleted staple is queued for the next shop with affected recipes flagged, and the relevant expiry/defrost notifications have fired — all without any feature failing and with every override auditable.
- **Variations:** order with a substitution (PROV-16) before the cook; partial cook (proportional deduction); budget in ramp-up (partial cost estimate, PROV-30); inventory tracking the only feature on (others gracefully ignored, PROV-39); a `spoiled_early` waste log mid-week tightening that category's expiry default.
- **HLD ref:** provision-model.md §Inventory updates (grocery order, cook-event deduction, batch cook), §How supplier data builds up, §Budget tracking, §Spice rack and staples, §Shopping List Calculation, §Guardrails (zero-floor), §Expiry tracking, §Freezer management; system-overview.md §Data Model 3.
- **Notes:** CROSS-MODULE backbone — step 1 (order→inventory+cache) and step 5 (shopping-list calc) are owned by **Grocery** and **Planner** respectively and detailed there + in the cross-journey file. This is the Provisions integration spine; assertions span inventory state + supplier cache + spend tracking + staple status + notifications. High-value flagship for the cross-journey synthesis.

---

## Appendix — `[HLD-GAP]` findings (consolidated)

| # | Gap | Pathway |
|---|---|---|
| GP1 | Manual-add required/optional fields and validation rules never enumerated. | PROV-02 |
| GP2 | Mapping-key confirmation UX for manual adds not described. | PROV-01 |
| GP3 | Behaviour when an ordered item has no category expiry default (expiry unset?). | PROV-03 |
| GP4 | Batch cook of a servings=1 recipe — whether the split prompt is suppressed. | PROV-04 |
| GP5 | Whether DEPLETED/removed items remain readable in history vs return not-found. | PROV-07 |
| GP6 | Coupling (if any) between consuming a frozen portion and the defrost reminder. | PROV-10 |
| GP7 | Whether correcting a quantity to zero auto-depletes vs requires explicit removal. | PROV-12 |
| GP8 | Interaction between a manual freezer-item expiry and the frozen_at+max_freeze_weeks derivation. | PROV-13 |
| GP9 | Freezer→fridge storage move: metadata gain/loss and expiry recomputation. | PROV-14 |
| GP10 | Whether manual-remove supports a partial-quantity removal vs full removal only. | PROV-15 |
| GP11 | Staple status transitions: whether stocked→out direct jumps are allowed (no illegal-transition rules defined). | PROV-17 (state model 4.2) |
| GP12 | The "staples build naturally from grocery orders" auto-promotion trigger/threshold. | PROV-18 |
| GP13 | Waste-correction netting: whether the corrective new entry is a reversal and how the pair nets in summaries. | PROV-21 |
| GP14 | Data volumes / pagination for inventory (and other Provisions lists) unspecified. | PROV-06 |
| GP15 | Equipment-exists-vs-preferred routing for borderline feedback phrasing. | PROV-24 |
| GP16 | Whether a mid-cycle budget change can trigger remaining-days re-optimisation. | PROV-27 |
| GP17 | tolerance_over validation (negative? bounded?) not specified. | PROV-28 |
| GP18 | Two distinct over-budget triggers (3 consecutive weeks vs rolling-4-week average) not reconciled. | PROV-31 |
| GP19 | Whether an AI-mediated cost complaint directly mutates budget vs proposes a change (propose/accept vs no-approval override tension). | PROV-32 |
| GP20 | Price-sourcing model contradiction: primary doc says supplier cache is searched/scraped from products; system-overview says costs are learned from the user's actual paid prices, not scraped from feeds. | PROV-33 |
| GP21 | Pre-seeding a price for a not-yet-cached supplier product. | PROV-35 |
| GP22 | No explicit availability/in_stock field on the product cache, yet feedback "updates stock availability." | PROV-36 |
| GP23 | What the planner does when a defrost window can't fit before the scheduled slot. | PROV-38 |
| GP24 | Skipped-meal ingredient carry-over across week boundaries into the next plan. | PROV-42 |
| GP25 | Concurrent shared-household inventory edits — explicit Open Question, deferred to Household Model (last-write-wins + notification only suggested, not specified). | PROV-41 |
