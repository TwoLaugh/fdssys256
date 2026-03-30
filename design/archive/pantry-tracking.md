# Provisions — Environment, Inventory & Sourcing

*Loop 3 in the three-loop architecture. "Provisions" is the broadened concept of what was previously just "Pantry." It covers everything about what you have to work with: ingredients in the house, equipment, kitchen environment, budget, and supplier availability.*

## What Provisions Includes

### Pantry Inventory (dynamic, changes daily)
- Items across fridge, freezer, and cupboard
- Each item: name, quantity, unit, category, storage location, expiry date
- In: auto-add from shopping list completion, manual additions
- Out: auto-deduct when meal is marked cooked, manual removal
- Freezer management: frozen portions, defrost reminders
- Expiry alerts for items approaching use-by date

### Equipment (mostly static)
- What kitchen equipment exists: oven, hob, microwave, slow cooker, air fryer, instant pot, BBQ, blender, food processor, etc.
- Constrains which recipes are feasible — no air fryer means no air fryer recipes
- Can vary by environment (see below)

### Environment (mostly static)
- Kitchen constraints: "small kitchen, one hob", "no oven", "shared kitchen"
- Could support multiple environments: parents' house vs own flat
- Each environment has its own equipment list and potentially different pantry
- Active environment determines what the planner works with

### Budget (changes occasionally)
- Weekly grocery budget target
- Price sensitivity: "always cheapest" / "mid-range" / "don't care"
- Organic/free-range preference
- Feeds into planner to constrain recipe selection and shopping

### Supplier Availability (updated from shopping)
- Primary store: Tesco / Sainsbury's / etc.
- Shopping frequency: weekly / twice weekly / ad-hoc
- Delivery vs click-and-collect vs in-store
- Known pack sizes (learned from Tesco orders over time)
- Product availability patterns (seasonal, frequently out of stock)
- Price data (cached from Tesco — enables cost estimation in plans)

### Food Waste Tracking
- What was thrown away, why (expired, didn't like, made too much), estimated cost
- Feeds back into the planner: if spinach gets wasted often, plan smaller quantities or use it earlier in the week
- Aggregated in weekly/monthly reviews

## How Provisions Feeds the Planner (Loop 3)

```
Provisions state (what's available)
    │
    ▼
MEAL PLANNER (constrained by equipment, budget, pantry stock)
    │
    ▼
Shopping List (plan ingredients − pantry stock)
    │
    ▼
Tesco Order (browser automation, user reviews before checkout)
    │
    ▼
Provisions updated (purchased items → pantry, price data → supplier cache)
    │
    ▼
Feedback ("too expensive", "couldn't find X", "needs equipment I don't have")
    │
    ▼
Provisions state refined
```

## Automated Tracking Approaches

### v1: Manual + Auto-Deduct
- User adds items when they buy them (or auto-added from shopping list)
- When a meal is marked cooked, auto-deduct the recipe's ingredients
- Prompt: "You cooked Chicken Stir Fry — I've removed these from your pantry: [list]. Anything different?"
- Simple but relies on discipline for non-recipe consumption

### v2: Tesco Order History Sync
- If Tesco integration already exists, pull exact items + quantities from orders
- Exact, structured data — no guessing
- Only covers Tesco shops — not corner shop trips, farmers markets etc.

### v3: Receipt Scanning
- Phone camera → OCR → parse items + quantities
- Works for supermarket shops with structured receipts
- Challenges: OCR accuracy, mapping "TESCO CHICKEN BRST 500G" to a pantry item
- Tech: phone camera + vision model (Claude vision or lightweight OCR)

### Future: Barcode Scanning
- Scan items as they enter the kitchen
- Maps to product databases (Open Food Facts API)
- Gets product name, nutrition info, category
- Doesn't get expiry date or exact quantity remaining
