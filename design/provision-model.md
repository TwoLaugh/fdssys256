# Provision Model — Design

*Physical constraints, inventory, equipment, budget, and supplier data. One of the three data models the system optimises against.*

## What It Is

Provisions holds everything about *what the user has to work with* — ingredients in the house, kitchen equipment, grocery budget, and supplier pricing/availability. It's the physical-world counterpart to the Preference Model (what the user *wants*) and the Nutrition Model (what the user's body *needs*). The planner optimises meals against all three simultaneously.

The model has four concerns:

| Concern | What it holds | Updated by |
|---|---|---|
| **Inventory** | Fridge, freezer, cupboard, spice rack contents — items, quantities, expiry dates, storage location | Auto-deduct on cook, auto-add from grocery orders, manual edits, waste logging |
| **Equipment** | Kitchen tools available — constrains which recipes are feasible | User via settings (mostly static) |
| **Budget** | Weekly grocery spend target, price sensitivity | User via settings; adjusted from actual spend tracking |
| **Supplier data** | Product cache — pack sizes, pricing, substitution history | Cached from grocery orders; updated per shop |

This is **not**:
- The Preference Model (taste, cooking style, lifestyle — what the user *wants*. Also owns grocery quality preferences like organic/free-range — those are values, not financial constraints)
- The Nutrition Model (calorie/macro/micro targets — what the user's body *needs*)
- The Grocery module (GroceryProvider abstraction, Tesco automation — that's the *output* that acts on Provisions data)
- The Meal Planner (which reads Provisions as constraints — Provisions is passive state, not logic)
- The Household Model (multi-location support — spending time at different houses with different kitchens/inventories — is a household concern, not a Provisions concern)

---

## Core Principle: Everything Is Optional

Every Provisions feature is additive — the system works without it, and each feature the user engages with makes plans smarter. Nothing is a prerequisite.

| Feature | If enabled | If disabled |
|---|---|---|
| **Inventory tracking** | Planner uses what's in the house, schedules around expiry, reduces shopping list | Planner assumes empty pantry, generates full shopping lists every week |
| **Expiry tracking** | Expiry-driven scheduling, waste reduction, freshness alerts | No freshness pressure — planner picks recipes without considering what needs using up |
| **Budget** | Cost-constrained plans, spend tracking, price optimisation | No cost constraint — grocery order still works, user just doesn't get cost optimisation |
| **Equipment** | Hard filter on recipe feasibility | Planner assumes all common equipment is available — user may see infeasible recipes |
| **Waste logging** | Waste-reduction feedback loop, quantity adjustments over time | No learning from waste patterns |
| **Staples list** | Auto-replenishment of basics when low/out | User manages replenishment manually |
| **Supplier data** | Cost estimation, pack-size-aware planning, substitution awareness | Planner can't estimate costs or optimise for pack sizes — still produces valid plans |

This means the system must handle mixed states cleanly. A user might track inventory but not budget, or set a budget but not bother with expiry dates. Each feature reads from its own data and gracefully ignores missing data from other features. The planner checks what data is available and adjusts its optimisation accordingly — it never fails because a feature is turned off.

---

## Inventory

The core dynamic state — what ingredients are physically in the house, where, in what quantity, and when they expire.

### Item structure

All inventory items share a base structure. Additional fields apply depending on storage location and item type.

```json
{
  "item_id": "inv-0042",
  "name": "chicken thighs",
  "category": "protein",
  "storage_location": "fridge",
  "quantity": 600,
  "unit": "g",
  "expiry_date": "2026-04-18",
  "added_at": "2026-04-15T18:30:00",
  "source": "tesco_order",
  "source_ref": "order-2026-04-15",
  "cost_paid": 3.50,
  "ingredient_mapping_key": "chicken thigh skinless raw",
  "notes": null
}
```

**Base fields (all items):**
- `item_id`, `name`, `category`, `storage_location`, `added_at`, `source` — identity and provenance
- `ingredient_mapping_key` — the `search_term` key into the Nutrition Model's ingredient mapping cache (e.g., `"chicken thigh skinless raw"`). Used to match inventory items to recipe ingredients and to calculate nutrition for logged intake. Set automatically when items are added from a grocery order (the grocery module maps products to the Nutrition Model's cache); for manual adds, the system infers the mapping or the user confirms it.

**Quantity-tracked items (fridge, freezer, cupboard):**
- `quantity`, `unit`, `expiry_date`, `cost_paid`, `source_ref`

**Status-tracked items (staples — see [Spice Rack and Staples](#spice-rack-and-staples)):**
- `status` (`stocked` / `low` / `out`) instead of precise quantity

**Freezer extensions:**
- `frozen_at`, `max_freeze_weeks`, `defrost_method`, `defrost_lead_time_hours`, `source_recipe_id` (for batch-cooked items)

**`source` values:** `tesco_order` | `other_shop` | `manual_add` | `batch_cook` | `gift`

### Storage locations

Four locations, each with different shelf-life and planning implications:

| Location | Planning implication |
|---|---|
| **Fridge** | Use within expiry window (typically 2-5 days). Planner schedules recipes using fridge items before they expire. |
| **Freezer** | Long-term storage. Requires defrost lead time. Planner must account for defrost window when scheduling. |
| **Cupboard** | Shelf-stable. Long expiry. Base ingredients the planner can assume are available (flour, rice, oil). |
| **Spice rack** | Tracked by status (`stocked` / `low` / `out`) rather than precise quantities — gram-level tracking is pointless for spices, but running out of cumin mid-recipe is a real problem. See [Spice Rack and Staples](#spice-rack-and-staples). |

### Spice rack and staples

Spices and shelf-stable basics (oil, salt, flour, rice, soy sauce, etc.) don't need quantity tracking — they need *availability* tracking. These items use a simple status instead of precise quantities:

```json
{
  "item_id": "inv-0103",
  "name": "cumin",
  "category": "spice",
  "storage_location": "spice_rack",
  "status": "stocked",
  "is_staple": true,
  "source": "manual_add",
  "added_at": "2026-04-01T10:00:00",
  "ingredient_mapping_key": "cumin ground"
}
```

**Statuses:**
- `stocked` — have it, no action needed
- `low` — running low, add to next shop
- `out` — don't have it, add to next shop and flag recipes that need it

The `is_staple` flag marks items the user always wants to have in the house. When any staple hits `low` or `out`, it's automatically included in the next shopping list without the planner explicitly needing it for a recipe. The user sets up their staples during onboarding (or they build naturally from grocery orders), and the system handles replenishment from there.

Non-staple spices exist too — if the user buys saffron for one recipe, it's tracked but not auto-replenished.

**Why this matters for recipes:** A recipe that needs turmeric when your turmeric is `out` has a real problem — the planner should either avoid that recipe, flag the missing spice, or add it to the shopping list. Unlike fridge ingredients where the planner actively schedules around availability, spice/staple gaps are resolved by shopping, not by rearranging the plan.

### Freezer management

Frozen items use the base inventory structure plus freezer-specific extensions:

```json
{
  "item_id": "inv-0087",
  "name": "bolognese (batch cooked)",
  "category": "prepared_meal",
  "storage_location": "freezer",
  "quantity": 3,
  "unit": "portions",
  "cost_paid": null,
  "added_at": "2026-04-10T14:00:00",
  "source": "batch_cook",
  "source_recipe_id": "bolognese-v3",
  "ingredient_mapping_key": null,

  "frozen_at": "2026-04-10",
  "max_freeze_weeks": 12,
  "defrost_method": "overnight_fridge",
  "defrost_lead_time_hours": 12
}
```

The planner uses `defrost_lead_time_hours` when scheduling frozen items — if a frozen meal is planned for Wednesday dinner, the notification system triggers a "move to fridge" reminder on Tuesday evening. The user's defrost tolerance (overnight vs quick-defrost vs microwave) is a Lifestyle Config preference in the Preference Model; Provisions stores the item's actual defrost requirements.

Note: defrost scheduling means the planner needs a concept of "pre-cook actions" with lead times — not just "which recipe goes on which day" but "what preparation must happen the day before." This is a planner design concern, documented here because the data that drives it lives in Provisions.

### Expiry tracking

Every fridge item has an `expiry_date`. The system uses this in two ways:

1. **Proactive planning:** The planner prioritises recipes that use items approaching expiry. If chicken expires Thursday, the planner schedules a chicken recipe for Wednesday or earlier.
2. **Notifications:** The notification system alerts the user when items are within the expiry alert window (configurable, default: 2 days for fridge items, 2 weeks for freezer items). "Your chicken thighs expire tomorrow. Wednesday's plan uses them, or you could freeze them today."

Cupboard items have expiry dates too but they're informational rather than planning-critical — the planner doesn't urgently schedule around a tin of chickpeas expiring in 8 months.

**Where do expiry dates come from?** Grocery orders (e.g., Tesco) do not include expiry dates in their delivery data. Expiry dates are populated by:
1. **Category-based defaults.** The system estimates expiry from food category: fresh chicken = delivery date + 3 days, fresh vegetables = + 5 days, dairy = + 7 days, etc. These defaults are conservative and can be refined over time from waste data (if `spoiled_early` waste entries suggest a category's default is too generous, the system tightens it).
2. **User correction.** The user can override any expiry date ("this chicken says use by Friday" → update to Friday). User corrections take precedence over defaults.
3. **Freezer items.** Expiry is calculated from `frozen_at + max_freeze_weeks`. `max_freeze_weeks` comes from recipe metadata or category defaults.

If the user doesn't track expiry dates (see [Core Principle: Everything Is Optional](#core-principle-everything-is-optional)), inventory items simply have no `expiry_date` field. The planner still uses inventory for "what's in the house" but doesn't do expiry-driven scheduling or freshness alerts.

### Inventory updates

Inventory changes from six sources:

| Source | Mechanism | Reliability |
|---|---|---|
| **Grocery order** | Auto-add from confirmed Tesco order (items, quantities, actual prices, substitutions) | High — structured data from the order |
| **Cook event** | Auto-deduct recipe ingredients when user marks a meal as cooked. For batch cooks, also adds prepared portions to inventory (see below). | Medium — assumes recipe was followed exactly |
| **Meal consumption** | Auto-deduct one portion when user confirms eating a pre-made meal from inventory (batch-cooked or frozen). Distinct from cook events — nothing is cooked, just consumed. | High — single-tap confirmation |
| **Manual add** | User adds items bought elsewhere (corner shop, market, gifts) | High — user-entered |
| **Manual remove** | User removes items (eaten as snack, gone off, given away) | High — user-entered |
| **Waste log** | User logs wasted items with reason — deducts from inventory and records waste data | High — user-entered |

**Cook-event deduction detail:** When the user confirms "I cooked Chicken Stir Fry," the system deducts the recipe's ingredient list from inventory. The user sees a confirmation: "Removed from pantry: 400g chicken thighs, 1 pepper, 200g rice, 2 tbsp soy sauce. Anything different?" The user can correct before confirming. Partial cooks ("I halved the recipe") adjust the deduction proportionally.

**Batch cook detail:** When a batch cook produces multiple portions, the system deducts raw ingredients and adds prepared portions to inventory. The user specifies the storage split: "5 portions of curry — how many for the fridge vs freezer?" Default: enough fridge portions to cover the next 2-3 days (within the recipe's fridge shelf life), remainder to freezer. This creates two inventory entries from one cook event — e.g., 2 fridge portions (expiry: delivery + recipe's `max_fridge_days`) and 3 freezer portions (with full freezer metadata). The user can adjust the split.

**Snack and standalone consumption:** Items eaten outside of planned recipes (grabbed a banana, had some yoghurt) are logged via the nutrition logger's standalone food logging. If the logged item exists in inventory, the system prompts: "Remove 1 banana from pantry?" This keeps inventory roughly accurate without requiring the user to manually manage every item. This cross-model interaction (Nutrition Model logger → Provisions inventory) is the canonical flow for unplanned consumption — the Nutrition Model doc should cross-reference it.

**Skipped meals:** When a planned meal is skipped (user ate out, wasn't hungry), the already-purchased ingredients remain in inventory. The planner's mid-week re-optimisation handles rescheduling these into remaining days, with expiry-driven urgency if the ingredients are perishable.

---

## Equipment

A mostly-static list of what kitchen tools are available. The planner and Recipe Optimiser use this to filter recipes — no slow cooker means no slow cooker recipes.

### Shape

```json
{
  "equipment": [
    {"name": "oven", "available": true},
    {"name": "hob", "available": true, "details": "4-ring gas"},
    {"name": "microwave", "available": true},
    {"name": "air_fryer", "available": true, "details": "4L capacity"},
    {"name": "slow_cooker", "available": false},
    {"name": "blender", "available": true, "details": "stick blender only"},
    {"name": "food_processor", "available": false},
    {"name": "grill", "available": true, "details": "oven grill only"},
    {"name": "bbq", "available": false},
    {"name": "rice_cooker", "available": false},
    {"name": "stand_mixer", "available": false},
    {"name": "pressure_cooker", "available": false}
  ]
}
```

Equipment is a hard filter — if a recipe requires equipment the user doesn't have, it's excluded from plan composition entirely. The Recipe Optimiser can propose equipment substitutions ("this recipe uses a food processor — a stick blender would work for this step") but only if the substitution doesn't fundamentally change the recipe.

---

## Budget

Weekly grocery spend constraints. The simplest concern conceptually but one of the hardest for the planner to optimise against, because it requires knowing grocery prices.

**Budget optimisation is a progressive enhancement.** Cost estimates require supplier data, which starts empty and builds from grocery orders. For the first several weeks, the system cannot estimate costs for most ingredients, and budget enforcement is effectively disabled. Estimates become accurate after approximately 4-6 weeks of ordering as the supplier cache gains coverage. The system should communicate this: "Cost estimate based on 60% of ingredients with known prices — accuracy will improve."

### Shape

```json
{
  "budget": {
    "weekly_target": 50.00,
    "currency": "GBP",
    "tolerance_over": 10.00,
    "price_sensitivity": "moderate"
  }
}
```

**`weekly_target`** is the primary constraint. The planner estimates the weekly grocery cost of a proposed plan and optimises against this total — not per-meal or per-day cost. An expensive steak dinner is fine if the rest of the week is cheap.

**`tolerance_over`** allows the planner flexibility — a £50 target with £10 tolerance means the planner can propose a £58 plan rather than degrading meal quality to hit £50 exactly. The user sees the estimated cost and decides.

**`price_sensitivity`** is a coarse signal: `low` (don't worry about cost), `moderate` (balance cost with quality), `high` (always prefer cheapest option). Affects how aggressively the planner and Recipe Optimiser suggest cheaper alternatives.

**Note:** Product quality preferences (organic, free-range, branded vs own-label) live in the Preference Model's lifestyle config, not here. Those are about what the user *values*, not what they can *afford*. The grocery module consults the Preference Model for quality rules and the Provision Model for budget limits — the two are independent.

### Budget tracking

The system tracks actual spend against the budget target:

```json
{
  "spend_tracking": {
    "current_week": {
      "target": 50.00,
      "actual_so_far": 42.30,
      "remaining": 7.70,
      "orders": [
        {"date": "2026-04-14", "supplier": "tesco", "total": 42.30, "order_ref": "order-2026-04-14"}
      ]
    },
    "rolling_4_week_average": 48.75
  }
}
```

The rolling average helps the planner and user understand real spending trends vs the target. If the 4-week average is consistently £15 over target, the planner can flag this: "Your average weekly spend is £65 against a £50 target. Would you like to adjust the target, or should I prioritise cheaper recipes?"

---

## Supplier Data

A product cache that builds from grocery orders — prices, pack sizes, and substitution history. This is what makes cost estimation and pack-size-aware planning possible. It's not a model of supplier behaviour; it's a lookup table the system populates over time.

### Product cache

```json
{
  "product_id": "tesco-chicken-thigh-skinless-1kg",
  "supplier": "tesco",
  "name": "Tesco Chicken Thigh Fillets Skinless 1kg",
  "price": 4.50,
  "price_per_unit": 0.45,
  "unit": "100g",
  "pack_size_g": 1000,
  "category": "protein",
  "last_checked": "2026-04-14",
  "clubcard_price": 3.75,
  "substitution_history": [
    {"date": "2026-04-01", "substituted_with": "tesco-chicken-thigh-bone-in-1kg", "accepted": false}
  ],
  "ingredient_mapping_key": "chicken thigh skinless raw"
}
```

### How supplier data builds up

The cache starts empty and grows from grocery orders:

1. **First order:** The grocery module searches Tesco for each ingredient, selects products, and caches the product data (name, price, pack size).
2. **Subsequent orders:** Cached products are reused. Prices are refreshed. New products are added when recipes introduce new ingredients.
3. **Over time:** The cache covers most commonly used ingredients and the system rarely needs to search for new products.

This is the same caching pattern as the Nutrition Model's ingredient mapping — solve the lookup once, reuse forever.

### Pack size awareness

Pack sizes create a real planning problem. If a recipe needs 200g of spinach but Tesco sells 250g bags, the planner buys the 250g bag. The remaining 50g is either used in another recipe or wasted.

**v1 approach:** The grocery module selects the smallest sufficient pack size for each ingredient. No cross-recipe optimisation — the planner doesn't try to schedule a fourth recipe to use up 200g of leftover chicken from pack-size rounding. This is simple and good enough. Waste tracking will naturally reveal whether pack-size waste is a real problem.

**Future (informed by waste data):** If waste tracking shows significant pack-size-driven waste, the planner can add ingredient utilisation as a secondary objective during plan composition — preferring recipe combinations that use up full packs. This is one of the hardest optimisation challenges and should only be built if the data justifies it.

### Substitution tracking

Tesco (and other supermarkets) frequently substitute items in delivery orders. Tracking substitution history keeps inventory accurate — the user's actual pantry contains the substituted item, not the ordered one. When a substitution is confirmed (user accepted it), inventory reflects what actually arrived.

---

## Food Waste Tracking

When food is thrown away, the user logs it with a reason. This data feeds back into planning to reduce future waste.

### Shape

```json
{
  "waste_log": [
    {
      "item": "spinach",
      "quantity_g": 100,
      "reason": "expired",
      "cost_estimate": 0.50,
      "date": "2026-04-16",
      "notes": "Bought too much, didn't use in time"
    },
    {
      "item": "leftover pasta bake",
      "quantity_g": 300,
      "reason": "leftover_not_eaten",
      "cost_estimate": 1.20,
      "date": "2026-04-17",
      "notes": null
    }
  ]
}
```

### Waste reasons

| Reason | What it tells the planner |
|---|---|
| `expired` | Schedule this ingredient earlier in the week, or buy smaller quantities |
| `leftover_not_eaten` | Batch sizing was too large, or leftover tolerance is lower than config suggests |
| `didn't_like` | Routed to Feedback System as a preference signal |
| `spoiled_early` | Actual shelf life shorter than expected — adjust expiry estimates |
| `made_too_much` | Recipe portion sizing needs adjustment |

Waste data aggregates into weekly/monthly summaries: total cost wasted, most-wasted items, most common reasons. Raw waste entries are retained for 3 months, then rolled into weekly summaries. The planner uses patterns over time — if spinach is wasted 3 weeks in a row, it reduces spinach quantities or schedules spinach recipes earlier in the week.

---

## Shopping List Calculation

The shopping list is the bridge between Provisions and the grocery module. It's calculated by deterministic code (not AI), **owned by the Planner module**, and uses data from Provisions. It's documented here because the formula depends entirely on Provisions data structures, but the implementation lives in the Planner. The Planner HLD will define the full algorithm; this section specifies the data contract from the Provisions side.

### Formula

```
Shopping list = (plan ingredients − current inventory) + staples at low/out
```

In practice this has several steps:

1. **Aggregate plan ingredients.** Sum all ingredients needed across the week's recipes, combining duplicates (3 recipes using chicken → total chicken needed).
2. **Subtract inventory.** For each ingredient, check if it exists in inventory. If the user has 200g chicken but the plan needs 600g, the shopping list needs 400g. If the user has 800g, the shopping list needs 0g for chicken.
3. **Add staples.** Any staple item at `low` or `out` is added to the shopping list regardless of whether the plan needs it. This is the auto-replenishment mechanism.
4. **Map to supplier products.** Each shopping list item is matched to a supplier product from the cache. If the item needs 400g chicken and the smallest pack is 1kg, the shopping list orders 1kg. The 600g surplus remains in inventory after delivery, available for future cook events.
5. **Calculate estimated cost.** Sum the prices of all supplier products. If some items have no cached price (early weeks), flag the estimate as partial.

**Who owns this logic:** The planner triggers the calculation as part of plan generation. The shopping list is a planner output that feeds into the grocery module. Provisions is read-only during this process — it supplies the inventory and staples data but doesn't contain the calculation logic.

---

## How It Gets Used

Each consumer reads a different slice of Provisions.

### By the Meal Planner

**Primary consumer.** Reads:
- Inventory: what's already in the house (prioritise using it, especially near-expiry items)
- Staples: flag recipes that need out-of-stock spices or basics
- Equipment: hard filter on recipe feasibility
- Budget: cost constraint on the overall plan
- Supplier data: pack sizes and prices for cost estimation

The planner's relationship with Provisions is the most complex of the three data models because it involves cost estimation and expiry-driven scheduling — not just constraint checking.

**Pantry-first planning:** For mid-week re-optimisation (when ingredients are already purchased and some are approaching expiry), the planner should invert its normal approach: start from "what needs using up" and build meals around those ingredients, rather than selecting ideal recipes and checking inventory. This is how most home cooks think and naturally solves expiry-driven scheduling without it being a separate optimisation objective.

### By the Recipe Optimiser

**Reads mainly equipment and budget:**
- Equipment: "This recipe uses a food processor — suggest a knife-prep alternative?"
- Budget: "This recipe uses fillet steak — suggest a cheaper cut?"
- Inventory: "You have leftover roast chicken — suggest using that instead of buying raw chicken?"

### By the Grocery Module

**Primary writer, secondary reader.** The grocery module:
- Reads: the shopping list (from planner), supplier data cache (known products, prices)
- Writes: confirmed order items → inventory, actual prices → supplier data cache, substitutions → substitution history

### By the Notification System

**Reads inventory for alerts:**
- Expiry alerts at `expiry_date - N days` (configurable; default: 2 days for fridge, 14 days for freezer)
- Defrost reminders at `meal_time - defrost_lead_time_hours`
- Staple replenishment: "Running low on paprika — added to next shop"

### By the Feedback System

**Receives routed feedback:**
- Cost complaints ("this week was too expensive") → may adjust budget target or price sensitivity
- Availability issues ("couldn't find X at Tesco") → updates stock availability in supplier cache
- Equipment feedback ("I don't have a food processor") → updates equipment list
- Waste observations ("I keep throwing away salad") → logged to waste tracking

---

## Accuracy Expectations

Provisions data degrades over time. The system should be honest about this rather than displaying false precision.

| Data | Accuracy | Mitigation |
|---|---|---|
| Inventory from grocery orders | High at time of delivery, degrades daily | Cook-event deduction, manual corrections, periodic prompts |
| Inventory from manual adds | Medium — users estimate quantities | Display as approximate, accept corrections |
| Cook-event deductions | ±20% — assumes recipe followed exactly | Confirmation prompt with correction option |
| Cost estimates (first 4 weeks) | Low — sparse supplier cache | Display as "partial estimate" with coverage % |
| Cost estimates (after 6+ weeks) | ±10-15% — prices may be 1-2 weeks stale | Display as ranges (~£45-55), not point values |
| Staple status | High — user-maintained, simple model | Low friction to update (single tap) |

**Display strategy:** Show inventory quantities as approximate ("~400g chicken") rather than false-precision ("412g chicken"). Show cost estimates as ranges. When the system is uncertain, say so.

---

## Guardrails

### Inventory
- Quantity never goes below zero — if a cook-event deduction would produce a negative quantity, floor at zero and alert the user ("Inventory shows 0g chicken thighs after cooking — you may have had more than the system tracked")
- Items with no updates for 3+ weeks are flagged as potentially stale when the planner depends on them: "Your plan relies on rice added 4 weeks ago — do you still have it?"

### Budget
- Weekly target must be positive. No upper limit enforced — the system doesn't judge spending.
- If actual spend exceeds target + tolerance for 3+ consecutive weeks, prompt: "Your spending consistently exceeds your target. Would you like to adjust it?"

### Supplier cache
- Prices older than 2 weeks are flagged as "estimated" in cost calculations
- Prices older than 4 weeks are excluded from cost estimates entirely — the system treats these ingredients as unpriced and notes the gap
- **Exception during ramp-up:** For the first 8 weeks of use (while the cache is building coverage), staleness thresholds are relaxed — no prices are excluded, though all are flagged as "estimated" after 2 weeks. This prevents the staleness policy from undermining the budget ramp-up period (see [Budget](#budget))

### Waste logging
- If inventory tracking is active, waste quantity cannot exceed current inventory for that item (prevents logging more waste than exists). If inventory tracking is off, waste logging is unconstrained — the system accepts the user's estimate without validation.
- Waste entries are immutable once logged — corrections create a new entry, not an edit

---

## User Overrides

The user can correct any Provisions data directly:

- **Inventory corrections:** Adjust quantity ("actually I have 400g, not 600g"), change expiry date, change storage location (moved chicken from fridge to freezer). Overrides are preserved — subsequent cook-event deductions adjust from the corrected value, not the original.
- **Supplier price corrections:** "That chicken is actually £5 now, not £4.50." Updates the cache. The user's correction takes precedence until the next grocery order refreshes the price.
- **Budget adjustments:** Change target, tolerance, or sensitivity at any time. Takes effect on the next plan generation.
- **Staple status:** Single-tap update from `stocked` → `low` → `out`. No friction.

All overrides are logged with timestamps for auditability but do not require approval flows — Provisions is the user's physical reality, and they are the authority on it.

---

## Boundaries with Other Models

| Concern | Lives in | Not in Provisions because |
|---|---|---|
| Taste preferences (likes, dislikes) | Preference Model | Subjective — Provisions is physical constraints |
| Cooking method preferences | Preference Model (taste profile) | Style preference, not equipment constraint |
| Grocery quality preferences (organic, free-range, branded) | Preference Model (lifestyle config) | What the user *values*, not what they can *afford* — the grocery module consults both models independently |
| Batch cooking preferences (prep days, leftover tolerance) | Preference Model (lifestyle config) | User scheduling preference — Provisions stores what's *in* the freezer, not whether the user *wants* to freeze things |
| Reheating preferences | Preference Model (lifestyle config) | User preference — Provisions stores what equipment exists for reheating |
| Defrost tolerance (overnight vs microwave) | Preference Model (lifestyle config) | User preference — Provisions stores the item's actual defrost requirements |
| Calorie/macro/micro targets | Nutrition Model | Nutritional requirements, not physical constraints |
| Recipe storage and versioning | Recipe Engine | Catalogue concern — Provisions constrains recipe selection, doesn't store recipes |
| Grocery ordering automation | Grocery module | Provisions is the data; the grocery module is the action |
| Dietary identity (vegetarian, etc.) | Preference Model (hard constraints) | Identity constraint, not a physical/financial constraint |
| Multi-location support (uni vs parents, own place vs partner's) | Household Model | Different locations imply different households, each with its own Provision Model instance (separate inventory, equipment, budget, supplier data). The Household Model owns which locations exist and which is active — Provisions just models one location's state. Transferring items between locations (e.g., taking meal-prepped food from parents' house to uni) is a Household Model operation that writes to both Provision Models |

### Key interaction rules

**Equipment vs cooking preferences.** Provisions owns what equipment *exists*. The Preference Model owns what equipment the user *prefers to use*. If the user has an air fryer (Provisions) but dislikes air-fried food (Preference Model), the planner respects both — the air fryer exists but isn't preferred.

**Budget vs nutrition targets.** Provisions owns the financial constraint. The Nutrition Model owns the nutritional constraint. When they conflict (180g protein/day is expensive on a £50/week budget), the planner's constraint resolution system handles it — see the system overview's [Constraint resolution](system-overview.md#constraint-resolution) section.

**Budget vs quality preferences.** Provisions owns the budget. The Preference Model owns quality preferences. A user with a £50 budget who "always" wants free-range eggs is expressing a priority — the budget enforces the trade-off. The grocery module respects both: it selects free-range eggs (Preference Model) and reports the cost impact against the budget (Provision Model).

**Inventory vs the grocery module.** Provisions owns the *state* of what's in the house. The grocery module owns the *action* of buying more. The shopping list is calculated by the planner (see [Shopping List Calculation](#shopping-list-calculation)), passed to the grocery module for ordering, and the confirmed order updates Provisions. Clear data flow: Provisions → Planner → Grocery Module → Provisions.

---

## Bootstrapping (Cold Start)

### Inventory

Day one: inventory is empty. The first weekly plan assumes nothing is in the house and generates a full shopping list. After the first grocery order, inventory is populated from the order.

**Quick-start option:** During onboarding, prompt for staples: "Do you usually have these in your cupboard? Rice, pasta, olive oil, salt, pepper, soy sauce, flour, tinned tomatoes..." The user ticks what applies. These become the initial staples list (all marked `stocked`) so the first plan doesn't redundantly order flour and salt.

### Equipment

Collected during onboarding. A checklist of common kitchen equipment — user ticks what they have. Quick, low-effort, and immediately useful for recipe filtering.

### Budget

Asked during onboarding: "Roughly how much do you spend on groceries per week?" Options: £30, £50, £75, £100, custom. Plus price sensitivity: low / moderate / high. This is enough to start — real spend tracking refines the picture over time. Or the user skips this entirely and the system plans without a cost constraint.

### Supplier data

Starts empty. Builds from the first grocery order. No bootstrapping needed — the grocery module searches for products on-demand and caches results.

### Waste tracking

Starts empty. The system cannot provide waste-reduction recommendations until it has sufficient data (approximately 4+ weeks of logging). Until then, waste logging is purely a personal record.

---

## Open Questions

- **Shared household inventory edits.** If multiple household members can update inventory, what happens with concurrent edits? "I used the last eggs" from two people simultaneously. Likely needs last-write-wins with a notification: "Partner marked eggs as used — you may need to update your entry." Deferred to Household Model design.
