# Nutrition Engine — Detailed Design

## The Problem

Every recipe needs accurate-ish nutrition data (calories, protein, carbs, fat per serving). This data comes from mapping recipe ingredients to a nutrition database. The mapping is the hard part.

## Why It's Hard

Recipe ingredients are written for humans, not databases:

| Recipe says | USDA has |
|------------|----------|
| "2 chicken breasts" | "Chicken, broiler, breast, meat only, cooked, roasted" (multiple entries for raw/cooked/skin-on/skinless) |
| "a glug of olive oil" | "Oil, olive, salad or cooking" (but how many ml is "a glug"?) |
| "1 tin of chickpeas" | "Chickpeas, canned, drained" (but how many grams is a tin?) |
| "handful of spinach" | "Spinach, raw" (a handful is ~30g? 50g?) |
| "salt and pepper to taste" | Negligible calories but sodium matters if tracking |

Three sub-problems:
1. **Ingredient identification**: "chicken breast" → which database entry?
2. **Quantity normalisation**: "2 breasts" / "a glug" / "1 tin" → grams
3. **Cooked vs raw**: nutrition data is usually per 100g raw, but recipe quantities might be cooked weight

## Data Sources

### Primary: USDA FoodData Central
- Free, comprehensive, API available
- ~370k food entries with full macro/micro data
- Best for: raw ingredients, generic foods
- API: https://fdc.nal.usda.gov/api-guide
- Can download full dataset as CSV for local import (~250MB)

**Decision: Local import vs API calls?**
- API: simpler setup, always up-to-date, but adds latency per ingredient and external dependency
- Local: faster queries, works offline, but need to manage updates
- **Recommendation**: Start with API calls (simpler), cache results in our DB. Each ingredient only needs to be mapped once — after that it's a local lookup. Over time the cache covers most common ingredients and API calls become rare.

### Secondary: Open Food Facts
- Free, open source, API available
- Better for branded/packaged products (Tesco own-brand items, etc.)
- Barcode lookup support
- API: https://world.openfoodfacts.org/data
- Use when: recipe includes a branded ingredient, or when matching Tesco products to nutrition data

### Not using (for now)
- Nutritionix: good NLP but freemium, adds a paid dependency
- McCance & Widdowson: UK-specific, no API, would need manual import

## The Mapping Pipeline

When a recipe is created or imported, run this pipeline:

```
Recipe ingredients (natural language)
        │
        ▼
   ┌─────────────┐
   │  AI Parsing  │  ← Cheap model (Haiku)
   │              │
   │  Input: "2 chicken breasts, skinless"
   │  Output: {
   │    "ingredient": "chicken breast, skinless, raw",
   │    "quantity": 2,
   │    "unit": "piece",
   │    "grams_estimate": 340,
   │    "usda_search_term": "chicken breast skinless raw"
   │  }
   └──────┬──────┘
          │
          ▼
   ┌─────────────┐
   │ Cache Check  │  ← Have we mapped "chicken breast skinless raw" before?
   │              │
   │  YES → use cached USDA ID + nutrition per 100g
   │  NO  → search USDA API
   └──────┬──────┘
          │ (cache miss)
          ▼
   ┌─────────────┐
   │  USDA API   │  ← Search for "chicken breast skinless raw"
   │  Search     │     Returns top 5-10 matches
   └──────┬──────┘
          │
          ▼
   ┌─────────────┐
   │  AI Match   │  ← Cheap model: "Which of these USDA entries best matches
   │  Selection  │     'chicken breast, skinless, raw'?"
   │              │     Returns: USDA FDC ID + confidence score
   └──────┬──────┘
          │
          ▼
   ┌─────────────┐
   │  Calculate  │  ← nutrition_per_100g × (grams_estimate / 100)
   │  Nutrition  │     per ingredient, sum for full recipe, divide by servings
   └──────┬──────┘
          │
          ▼
   ┌─────────────┐
   │  Cache &    │  ← Store: ingredient text → USDA ID mapping
   │  Store      │     Store: recipe nutrition per serving
   └─────────────┘
```

### The AI Parsing Step (detail)

This is where we handle vague quantities and normalise everything to grams.

Prompt to cheap model:
```
Parse these recipe ingredients into structured data.
For each ingredient, provide:
- ingredient: standardised name (lowercase, include state like "raw"/"cooked" if relevant)
- quantity: numeric amount
- unit: the unit used (piece, g, ml, tbsp, tsp, cup, tin, handful, etc.)
- grams_estimate: your best estimate in grams (use standard conversions:
    1 chicken breast ≈ 170g, 1 tin chickpeas ≈ 240g drained, 1 tbsp oil ≈ 14g,
    1 handful spinach ≈ 30g, 1 medium onion ≈ 150g, etc.)
- usda_search_term: a clean search term to find this in USDA FoodData Central
- is_cooked: whether the quantity refers to cooked or raw weight

Ingredients:
{{ingredient_list}}
```

This is a well-scoped task a cheap model handles reliably. The standard conversions in the prompt act as guardrails for common vague quantities.

### The USDA Match Step (detail)

When the cache misses and we get USDA search results back, we need to pick the right one. USDA often returns many similar entries:

```
Search: "chicken breast skinless raw"
Results:
1. Chicken, broilers or fryers, breast, skinless, boneless, meat only, raw (FDC ID: 171077)
2. Chicken, broilers or fryers, breast, skinless, boneless, meat only, cooked, grilled (FDC ID: 171079)
3. Chicken breast, rotisserie, skin not eaten (FDC ID: 174571)
4. Chicken breast tenders, breaded, cooked (FDC ID: 174608)
```

AI picks #1 with high confidence. This mapping is cached: next time any recipe uses "chicken breast skinless raw", it goes straight to FDC ID 171077.

## Ingredient Mapping Cache

Over time, this becomes the most valuable part of the nutrition engine.

```
Table: ingredient_mapping
├── search_term (text, indexed)     "chicken breast skinless raw"
├── usda_fdc_id (integer)           171077
├── nutrition_per_100g (jsonb)      {"calories": 120, "protein": 22.5, "fat": 2.6, "carbs": 0}
├── default_piece_grams (integer)   170  (one chicken breast ≈ 170g)
├── confidence (float)              0.95
├── last_verified (timestamp)
└── source (text)                   "usda" | "open_food_facts" | "manual"
```

After a few months of use, common ingredients are all cached and the system barely needs to call external APIs.

## Accuracy Expectations

| Factor | Impact | Mitigation |
|--------|--------|------------|
| Vague quantities ("a glug", "handful") | ±30% on that ingredient | AI estimates + standard conversions |
| Cooked vs raw confusion | ±20% on that ingredient | AI parsing notes cooked/raw state |
| Wrong USDA match | ±10-20% if close match, worse if wrong | AI selection + caching (fix once, correct forever) |
| Cooking method changes nutrition | ±5-10% | Ignore for v1, use raw-based calculations |
| Cumulative across a full recipe | ±10-15% overall | Good enough for meal planning |
| Branded products | Variable | Fall back to Open Food Facts |

**10-15% overall accuracy is what MyFitnessPal, Cronometer, and every other calorie app achieves.** This is fine for meal planning. We should display nutrition as rounded numbers (not "247.3 calories" — say "~250 calories") to communicate appropriate precision.

## Recalculation

Nutrition is recalculated when:
- A recipe is created or imported
- A recipe is evolved (new version with changed ingredients)
- User manually edits ingredients
- An ingredient mapping is corrected by the user ("that's not the right match")

## User Override

The user can correct any nutrition value:
- "This recipe is actually about 500 calories, not 400" → manual override stored
- "That's not regular chicken, it's free-range organic" → update the ingredient mapping
- Manual overrides take precedence over calculated values
- Flagged so recalculation doesn't overwrite them

## Open Questions
- Do we track micronutrients (iron, vitamin D, fibre, sodium) from the start, or just macros?
  - USDA data includes micros, so the data is available. Question is whether to display it.
  - Probably: calculate and store everything, but only show macros in v1 UI. Show micros in v2 when health tracking wants them.
- Should the nutrition engine proactively flag recipes with suspicious numbers? ("This recipe claims 100 calories but has 200g of pasta — that seems low")
- Alcohol: some recipes include wine/beer. Track alcohol calories?
