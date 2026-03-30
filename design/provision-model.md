# Provisions — Design

*Physical constraints, inventory, equipment, budget, and supplier data. One of the three data models the system optimises against.*

## What It Is

Provisions holds everything about *what the user has to work with* — ingredients in the house, kitchen equipment, physical environment, grocery budget, and supplier pricing/availability. It's the physical-world counterpart to the Preference Model (what the user *wants*) and the Nutrition Model (what the user's body *needs*). The planner optimises meals against all three simultaneously.

The model has five concerns:

| Concern | What it holds | Updated by |
|---|---|---|
| **Inventory** | Fridge, freezer, cupboard contents — items, quantities, expiry dates, storage location | Auto-deduct on cook, auto-add from grocery orders, manual edits, waste logging |
| **Equipment** | Kitchen tools available — constrains which recipes are feasible | User via settings (mostly static) |
| **Environment** | Kitchen constraints and context — size, shared spaces, multiple locations | User via settings (mostly static) |
| **Budget** | Weekly grocery spend target, price sensitivity, quality preferences | User via settings; adjusted from actual spend tracking |
| **Supplier data** | Product availability, pack sizes, pricing, substitution history | Cached from grocery orders; updated per shop |

This is **not**:
- The Preference Model (taste, cooking style, lifestyle — what the user *wants*)
- The Nutrition Model (calorie/macro/micro targets — what the user's body *needs*)
- The Grocery module (GroceryProvider abstraction, Tesco automation — that's the *output* that acts on Provisions data)
- The Meal Planner (which reads Provisions as constraints — Provisions is passive state, not logic)

---

## Inventory

The core dynamic state — what ingredients are physically in the house, where, in what quantity, and when they expire.

### Item structure

```json
{
  "item_id": "inv-0042",
  "name": "chicken thighs",
  "category": "protein",
  "storage_location": "fridge",
  "quantity": 600,
  "unit": "g",
  "pack_size_g": 600,
  "expiry_date": "2026-04-18",
  "added_at": "2026-04-15T18:30:00",
  "source": "tesco_order",
  "source_ref": "order-2026-04-15",
  "cost_paid": 3.50,
  "usda_mapping_id": "chicken-thigh-skinless-raw",
  "notes": null
}
```

### Storage locations

Four locations, each with different shelf-life and planning implications:

| Location | Planning implication |
|---|---|
| **Fridge** | Use within expiry window (typically 2-5 days). Planner schedules recipes using fridge items before they expire. |
| **Freezer** | Long-term storage. Requires defrost lead time. Planner must account for defrost window when scheduling. |
| **Cupboard** | Shelf-stable. Long expiry. Base ingredients the planner can assume are available (flour, rice, oil, spices). |
| **Spice rack** | Subset of cupboard — modelled separately because spices are used in small quantities, rarely expire in a meaningful way, and don't drive recipe selection (no one plans a meal around "I have cumin"). Tracked for grocery completeness, not for planning. |

### Freezer management

Frozen items carry additional metadata:

```json
{
  "item_id": "inv-0087",
  "name": "bolognese (batch cooked)",
  "storage_location": "freezer",
  "quantity": 3,
  "unit": "portions",
  "frozen_at": "2026-04-10",
  "max_freeze_weeks": 12,
  "defrost_method": "overnight_fridge",
  "defrost_lead_time_hours": 12,
  "source": "batch_cook",
  "source_recipe_id": "bolognese-v3",
  "notes": null
}
```

The planner uses `defrost_lead_time_hours` when scheduling frozen items — if a frozen meal is planned for Wednesday dinner, the notification system triggers a "move to fridge" reminder on Tuesday evening. The user's defrost tolerance (overnight vs quick-defrost vs microwave) is a Lifestyle Config preference in the Preference Model; Provisions stores the item's actual defrost requirements.

### Expiry tracking

Every fridge item has an `expiry_date`. The system uses this in two ways:

1. **Proactive planning:** The planner prioritises recipes that use items approaching expiry. If chicken expires Thursday, the planner schedules a chicken recipe for Wednesday or earlier.
2. **Notifications:** The notification system alerts the user when items are within 1-2 days of expiry — "Your chicken thighs expire tomorrow. Wednesday's plan uses them, or you could freeze them today."

Cupboard items have expiry dates too but they're informational rather than planning-critical — the planner doesn't urgently schedule around a tin of chickpeas expiring in 8 months.

### Inventory updates

Inventory changes from five sources:

| Source | Mechanism | Reliability |
|---|---|---|
| **Grocery order** | Auto-add from confirmed Tesco order (items, quantities, actual prices, substitutions) | High — structured data from the order |
| **Cook event** | Auto-deduct recipe ingredients when user marks a meal as cooked | Medium — assumes recipe was followed exactly |
| **Manual add** | User adds items bought elsewhere (corner shop, market, gifts) | High — user-entered |
| **Manual remove** | User removes items (eaten as snack, gone off, given away) | High — user-entered |
| **Waste log** | User logs wasted items with reason — deducts from inventory and records waste data | High — user-entered |

**Cook-event deduction detail:** When the user confirms "I cooked Chicken Stir Fry," the system deducts the recipe's ingredient list from inventory. The user sees a confirmation: "Removed from pantry: 400g chicken thighs, 1 pepper, 200g rice, 2 tbsp soy sauce. Anything different?" The user can correct before confirming. Partial cooks ("I halved the recipe") adjust the deduction proportionally.

**Snack and standalone consumption:** Items eaten outside of planned recipes (grabbed a banana, had some yoghurt) are logged via the nutrition logger's standalone food logging. If the logged item exists in inventory, the system prompts: "Remove 1 banana from pantry?" This keeps inventory roughly accurate without requiring the user to manually manage every item.

---

## Equipment

A mostly-static list of what kitchen tools are available. The planner and recipe optimiser use this to filter recipes — no slow cooker means no slow cooker recipes.

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

Equipment is a hard filter — if a recipe requires equipment the user doesn't have, it's excluded from plan composition entirely. The recipe optimiser can propose equipment substitutions ("this recipe uses a food processor — a stick blender would work for this step") but only if the substitution doesn't fundamentally change the recipe.

### Equipment and environments

Equipment lists are per-environment (see [Environment](#environment) below). If the user has different kitchens (e.g., home vs partner's house), each has its own equipment profile. The active environment determines which equipment list the planner uses.

---

## Environment

Physical kitchen context — constraints that affect what's feasible but change rarely.

### Shape

```json
{
  "environments": [
    {
      "env_id": "home",
      "label": "Home kitchen",
      "active": true,
      "kitchen_size": "medium",
      "hob_count": 4,
      "oven_count": 1,
      "worktop_space": "adequate",
      "shared_kitchen": false,
      "storage_capacity": {
        "fridge": "medium",
        "freezer": "small_drawer",
        "cupboard": "adequate"
      },
      "notes": "Standard kitchen, no space constraints"
    }
  ]
}
```

Environment constraints affect the planner subtly:

- **Small freezer** → limits batch cooking volume (can't freeze 10 portions if the drawer holds 4)
- **Limited hob** → limits recipes requiring multiple simultaneous pans
- **Shared kitchen** → may limit cooking times/access (relevant for house-shares)
- **Small worktop** → limits complex multi-component recipes

These are soft signals the planner uses for scoring, not hard filters (unlike equipment). A recipe requiring three pans on a two-ring hob is *possible* but inconvenient — the planner should prefer simpler alternatives.

The multi-environment model supports the user moving between kitchens. Switching active environment swaps both the equipment list and the storage capacity constraints. Inventory is per-environment (you don't take your fridge contents when you visit your partner).

---

## Budget

Weekly grocery spend constraints. The simplest concern conceptually but one of the hardest for the planner to optimise against, because it requires knowing grocery prices.

### Shape

```json
{
  "budget": {
    "weekly_target": 50.00,
    "currency": "GBP",
    "tolerance": {"over": 10.00, "under": null},
    "enforcement": "weekly_total",
    "price_sensitivity": "moderate",
    "quality_preferences": {
      "organic": "when_price_comparable",
      "free_range_eggs": "always",
      "free_range_meat": "preferred",
      "branded_vs_own_label": "own_label_preferred"
    },
    "notes": "Willing to spend more on protein quality, flexible on everything else"
  }
}
```

**`weekly_target`** is the primary constraint. The planner must estimate the grocery cost of a proposed plan before presenting it. This requires knowing prices, which comes from the supplier data cache.

**`tolerance`** allows the planner flexibility — a £50 target with £10 tolerance means the planner can propose a £58 plan rather than degrading meal quality to hit £50 exactly. The user sees the estimated cost and decides.

**`enforcement: "weekly_total"`** means the planner optimises the weekly grocery bill, not per-meal cost. An expensive steak dinner is fine if the rest of the week is cheap.

**`quality_preferences`** affect product selection in the grocery order, not recipe selection. "Free range eggs always" means the grocery module selects free-range eggs regardless of price; "organic when price comparable" means select organic if the premium is under a threshold (e.g., <20% more).

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

Cached knowledge about the grocery supplier — product availability, pack sizes, pricing, and substitution patterns. This is what makes cost estimation and grocery ordering possible.

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
  "in_stock": true,
  "last_checked": "2026-04-14",
  "price_history": [
    {"date": "2026-04-07", "price": 4.50},
    {"date": "2026-03-31", "price": 4.25}
  ],
  "clubcard_price": 3.75,
  "substitution_history": [
    {"date": "2026-04-01", "substituted_with": "tesco-chicken-thigh-bone-in-1kg", "accepted": false}
  ],
  "usda_mapping_id": "chicken-thigh-skinless-raw"
}
```

### How supplier data builds up

The cache starts empty and grows from grocery orders:

1. **First order:** The grocery module searches Tesco for each ingredient, selects products, and caches the product data (name, price, pack size).
2. **Subsequent orders:** Cached products are reused. Prices are refreshed. New products are added when recipes introduce new ingredients.
3. **Over time:** The cache covers most commonly used ingredients and the system rarely needs to search for new products.

This is the same caching pattern as the nutrition engine's ingredient mapping — solve the lookup once, reuse forever.

### Pack size awareness

Pack sizes create a real planning problem. If a recipe needs 200g of spinach but Tesco sells 250g bags, the planner has two choices:
1. Buy the 250g bag and schedule another recipe that uses the remaining 50g before it wilts
2. Accept the 50g waste

The planner should prefer option 1 — **ingredient utilisation** is a key planning objective. When composing the weekly plan, the planner tracks purchased quantities (at pack-size granularity) against planned usage across all meals. Leftover quantities from oversized packs become a soft constraint: "I'm buying 1kg of chicken for three recipes that need 800g total — is there a fourth recipe that could use the remaining 200g?"

This is one of the hardest optimisation challenges for the planner and directly depends on accurate pack size data in the supplier cache.

### Substitution tracking

Tesco (and other supermarkets) frequently substitute items in delivery orders. Tracking substitution history serves two purposes:

1. **Inventory accuracy:** The user's actual pantry contains the substituted item, not the ordered one. When a substitution is confirmed (user accepted it), inventory reflects what actually arrived.
2. **Future ordering:** If Tesco consistently substitutes coriander for parsley, the system can flag this to the user or pre-select an alternative product.

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

Waste data aggregates into weekly/monthly summaries: total cost wasted, most-wasted items, most common reasons. The planner uses patterns over time — if spinach is wasted 3 weeks in a row, it reduces spinach quantities or schedules spinach recipes earlier in the week.

---

## How It Gets Used

Each consumer reads a different slice of Provisions.

### By the Meal Planner

**Primary consumer.** Reads:
- Inventory: what's already in the house (prioritise using it, especially near-expiry items)
- Equipment: hard filter on recipe feasibility
- Environment: soft scoring for kitchen complexity
- Budget: cost constraint on the overall plan
- Supplier data: pack sizes and prices for cost estimation and ingredient utilisation

The planner's relationship with Provisions is the most complex of the three data models because it involves cost estimation, pack size optimisation, and expiry-driven scheduling — not just constraint checking.

### By the Recipe Optimiser

**Reads mainly equipment and budget:**
- Equipment: "This recipe uses a food processor — suggest a knife-prep alternative?"
- Budget: "This recipe uses fillet steak — suggest a cheaper cut?"
- Inventory: "You have leftover roast chicken — suggest using that instead of buying raw chicken?"

### By the Grocery Module

**Primary writer, secondary reader.** The grocery module:
- Reads: the shopping list (plan ingredients minus inventory), supplier data cache (known products, prices)
- Writes: confirmed order items → inventory, actual prices → supplier data cache, substitutions → substitution history

### By the Notification System

**Reads inventory for alerts:**
- "Your chicken thighs expire tomorrow"
- "Move the bolognese from freezer to fridge tonight for Wednesday's dinner"
- "You're running low on olive oil — added to next shop?"

### By the Feedback System

**Receives routed feedback:**
- Cost complaints ("this week was too expensive") → may adjust budget target or price sensitivity
- Availability issues ("couldn't find X at Tesco") → updates stock availability
- Equipment feedback ("I don't have a food processor") → updates equipment list
- Waste observations ("I keep throwing away salad") → logged to waste tracking

---

## Boundaries with Other Models

| Concern | Lives in | Not in Provisions because |
|---|---|---|
| Taste preferences (likes, dislikes) | Preference Model | Subjective — Provisions is physical constraints |
| Cooking method preferences | Preference Model (taste profile) | Style preference, not equipment constraint |
| Batch cooking preferences (prep days, leftover tolerance) | Preference Model (lifestyle config) | User scheduling preference — Provisions stores what's *in* the freezer, not whether the user *wants* to freeze things |
| Reheating preferences | Preference Model (lifestyle config) | User preference — Provisions stores what equipment exists for reheating |
| Calorie/macro/micro targets | Nutrition Model | Nutritional requirements, not physical constraints |
| Recipe storage and versioning | Recipe Engine | Catalogue concern — Provisions constrains recipe selection, doesn't store recipes |
| Grocery ordering automation | Grocery module | Provisions is the data; the grocery module is the action |
| Dietary identity (vegetarian, etc.) | Preference Model (hard constraints) | Identity constraint, not a physical/financial constraint |

### Key interaction rules

**Equipment vs cooking preferences.** Provisions owns what equipment *exists*. The Preference Model owns what equipment the user *prefers to use*. If the user has an air fryer (Provisions) but dislikes air-fried food (Preference Model), the planner respects both — the air fryer exists but isn't preferred.

**Budget vs nutrition targets.** Provisions owns the financial constraint. The Nutrition Model owns the nutritional constraint. When they conflict (180g protein/day is expensive on a £50/week budget), the planner's constraint resolution system handles it — see the system overview's [Constraint resolution](system-overview.md#constraint-resolution) section.

**Inventory vs the grocery module.** Provisions owns the *state* of what's in the house. The grocery module owns the *action* of buying more. The shopping list is calculated by the planner (plan ingredients minus inventory), passed to the grocery module for ordering, and the confirmed order updates Provisions. Clear data flow: Provisions → Planner → Grocery Module → Provisions.

---

## Bootstrapping (Cold Start)

### Inventory

Day one: inventory is empty. The first weekly plan assumes nothing is in the house and generates a full shopping list. After the first grocery order, inventory is populated from the order.

**Quick-start option:** During onboarding, prompt for staples: "Do you usually have these in your cupboard? Rice, pasta, olive oil, salt, pepper, soy sauce, flour, tinned tomatoes..." The user ticks what applies. This seeds the cupboard with shelf-stable basics so the first plan doesn't redundantly order flour and salt.

### Equipment

Collected during onboarding. A checklist of common kitchen equipment — user ticks what they have. Quick, low-effort, and immediately useful for recipe filtering.

### Environment

Sensible defaults (single kitchen, medium size, adequate storage). Only ask during onboarding if the user indicates a non-standard setup (shared kitchen, very small kitchen, multiple locations).

### Budget

Asked during onboarding: "Roughly how much do you spend on groceries per week?" Options: £30, £50, £75, £100, custom. Plus price sensitivity: "Do you prefer the cheapest option, or are you willing to pay more for quality?" This is enough to start — real spend tracking refines the picture over time.

### Supplier data

Starts empty. Builds from the first grocery order. No bootstrapping needed — the grocery module searches for products on-demand and caches results.

---

## Open Questions

- **Multi-supplier support.** v1 is Tesco-only behind the GroceryProvider abstraction. When a second supplier is added, how does the product cache work? Separate caches per supplier, or a unified product model with per-supplier pricing? Separate caches are simpler but prevent cross-supplier price comparison.
- **Shared household inventory edits.** If multiple household members can update inventory, what happens with concurrent edits? "I used the last eggs" from two people simultaneously. Likely needs last-write-wins with a notification: "Partner marked eggs as used — you may need to update your entry."
- **Staple auto-replenishment.** Should the system detect when staples (oil, rice, spices) run low and auto-add them to the shopping list without the planner explicitly needing them for a recipe? This is convenient but adds complexity. Could be a simple threshold: if olive oil drops below 200ml, add to next order.
- **Inventory accuracy decay.** Over time, inventory will drift from reality (snacks eaten without logging, quantities estimated wrong, items used in untracked cooking). How aggressively should the system prompt for inventory review? Weekly "is this still accurate?" prompts? Or only when the planner detects a plan depends on an item that was added a long time ago?
- **Receipt scanning accuracy.** OCR + AI parsing of supermarket receipts is a potential v2 input source for non-Tesco shops. The accuracy threshold for this to be useful (vs just annoying) needs testing. Mapping "TESCO CHKN BRST 500G" to a structured inventory item is a non-trivial NLP task.
