# Nutrition Model — Design

*The user's nutritional targets, intake tracking, and food/mood journaling. One of the three data models the system optimises against.*

## What It Is

The Nutrition Model holds everything about *what the user's body needs* and *what it's actually getting*. It's the quantitative counterpart to the Preference Model (which holds what the user *wants*). The planner optimises meals against both simultaneously.

The model has four concerns:

| Concern | What it holds | Updated by |
|---|---|---|
| **Nutritional targets** | Calorie/macro/micro goals, per-meal distribution, daily floors | User via settings; health platform directives (if connected) |
| **Intake tracking** | Planned vs actual intake per meal, daily/weekly aggregates | Nutrition logger (automated from plan + manual corrections) |
| **Nutrition engine** | Ingredient-to-nutrition mapping pipeline, USDA/Open Food Facts cache | Automated on recipe create/import/evolve, user corrections |
| **Food/mood journal** | Free-text notes tied to meals — how the user felt after eating | User input after meals |

This is **not**:
- The Preference Model (taste, cooking style, lifestyle — what the user *wants*)
- Dietary identity (vegetarian, keto, etc. — owned by the Preference Model's hard constraints tier; the Nutrition Model consumes it as an input)
- The Meal Planner (which reads targets and optimises against them — the Nutrition Model is passive state, not logic)
- A health tracking platform (mood scales, weight trends, labs, wearables, genomics — that's a separate system, the [Personal Biology Platform](https://github.com/TwoLaugh/project-ideas/tree/main/personal-biology-platform), which integrates with MealPrep AI via dietary directives)

---

## Nutritional Targets

### Macro targets

Macro targets are set in **absolute grams, not ratios**. "180g protein" is a hard floor, not "30% of calories" — the distinction matters because calorie targets can flex while protein floors shouldn't.

```json
{
  "calories": {
    "daily_target": 2200,
    "tolerance": {"under": 100, "over": 150},
    "enforcement": "weekly_average"
  },
  "protein": {
    "daily_target_g": 180,
    "daily_floor_g": 160,
    "enforcement": "daily_floor",
    "notes": "Floor is non-negotiable — every day must hit 160g minimum, weekly average should hit 180g"
  },
  "carbs": {
    "daily_target_g": 220,
    "enforcement": "weekly_average"
  },
  "fat": {
    "daily_target_g": 75,
    "enforcement": "weekly_average"
  },
  "fibre": {
    "daily_target_g": 30,
    "enforcement": "weekly_average"
  }
}
```

**Daily floors vs weekly averages.** Some targets (like protein) need to be consistent daily — you can't skip protein on Monday and double it on Tuesday. Others (like total calories, carbs, fat) can flex day-to-day as long as the weekly total converges. The `enforcement` field tells the planner which strategy to use.

**Per-meal macro distribution.** The planner needs to know how to spread targets across meals, not just hit a daily total:

```json
{
  "per_meal_targets": {
    "breakfast": {"calories": 500, "protein_g": 35},
    "lunch": {"calories": 650, "protein_g": 50},
    "dinner": {"calories": 700, "protein_g": 55},
    "snacks": {"calories": 350, "protein_g": 40}
  }
}
```

These are guidelines, not hard constraints — the planner can redistribute if a recipe is a better fit elsewhere, as long as the daily totals and floors are respected.

**TDEE variations.** Activity level affects calorie needs:

```json
{
  "activity_adjustment": {
    "rest_day": {"calorie_modifier": 0, "carb_modifier_g": -30},
    "light_activity": {"calorie_modifier": 0, "carb_modifier_g": 0},
    "training_day": {"calorie_modifier": 300, "carb_modifier_g": 50},
    "heavy_training": {"calorie_modifier": 500, "carb_modifier_g": 80}
  },
  "input_method": "manual",
  "notes": "Manual activity input initially — user marks each day. Wearable-driven later via health tracking integration."
}
```

### Micro targets

Full micronutrient tracking from day one. USDA data includes micros, so the data is available — calculate and store everything, but v1 UI shows macros only. Micros surface in v2 when health tracking wants them.

```json
{
  "tracked_micros": {
    "iron_mg": {"target": 18, "notes": "Higher for menstruating women"},
    "zinc_mg": {"target": 11},
    "vitamin_b12_mcg": {"target": 2.4},
    "vitamin_d_mcg": {"target": 15, "notes": "Supplemented — track food contribution separately"},
    "omega3_g": {"target": 1.6, "source_preference": "food over supplements"},
    "magnesium_mg": {"target": 400},
    "calcium_mg": {"target": 1000},
    "sodium_mg": {"upper_limit": 2300},
    "potassium_mg": {"target": 3400}
  },
  "display_in_ui": false,
  "track_internally": true
}
```

Users can set targets for any tracked micro. Defaults come from standard dietary reference intakes, adjusted for age/sex from the user's profile metadata.

### Dietary patterns

**Intermittent fasting / eating windows:**

```json
{
  "eating_window": {
    "enabled": true,
    "window_start": "12:00",
    "window_end": "20:00",
    "notes": "16:8 pattern — no calories before noon"
  }
}
```

The eating window is a **Nutrition Model constraint**, not a Preference Model setting. The Preference Model's `meal_timing` describes the user's preferred schedule *within* the eating window. If the Nutrition Model says "eating window 12:00-20:00" but the Preference Model says "breakfast at 07:00", the eating window takes precedence — the planner should flag the incompatibility and suggest the user update their meal structure. See [Boundaries with Other Models](#boundaries-with-other-models).

---

## Intake Tracking (Nutrition Logger)

The nutrition logger tracks what the user actually ate versus what was planned. It works like MyFitnessPal but is pre-populated from the meal plan.

### How it works

1. **Pre-filled from plan.** Each day's meals are pre-populated from the weekly plan with full nutrition data already calculated. The user confirms a meal was eaten as planned with a single tap.
2. **Override on deviation.** If the user ate something different, they can:
   - Free-text entry: "I had a cheese sandwich instead" → AI parses, maps through the nutrition engine, logs actual nutrition
   - Manual edit: adjust quantities, swap ingredients
   - Skip: mark meal as skipped (logged as zero intake)
3. **Standalone food logging.** Snacks, drinks, and unplanned items can be searched and logged directly from the USDA/Open Food Facts databases — same search as MyFitnessPal. Accompaniments from the Preference Model (yoghurt, fruit, coffee) are pre-suggested based on the user's habits.

### Planned vs actual tracking

```json
{
  "2026-04-15": {
    "breakfast": {
      "planned": {"recipe_id": "overnight-oats-v3", "calories": 480, "protein_g": 32},
      "actual": {"status": "confirmed", "calories": 480, "protein_g": 32}
    },
    "lunch": {
      "planned": {"recipe_id": "chicken-stir-fry-v2", "calories": 620, "protein_g": 48},
      "actual": {"status": "overridden", "free_text": "grabbed a burrito from the shop", "calories": 750, "protein_g": 35}
    },
    "dinner": {
      "planned": {"recipe_id": "miso-salmon", "calories": 580, "protein_g": 52},
      "actual": {"status": "pending"}
    },
    "snacks": {
      "logged": [
        {"item": "banana", "calories": 105, "protein_g": 1.3, "source": "usda"},
        {"item": "greek yoghurt 150g", "calories": 130, "protein_g": 15, "source": "usda"}
      ]
    },
    "daily_totals": {
      "planned": {"calories": 1680, "protein_g": 132},
      "actual_so_far": {"calories": 1465, "protein_g": 83.3},
      "remaining": {"calories": 735, "protein_g": 96.7}
    }
  }
}
```

**Divergence detection.** When actual intake diverges significantly from planned (e.g., user skipped lunch, or ate a 750-cal burrito instead of a 620-cal stir fry), the remaining daily/weekly targets shift. If the divergence is large enough, this triggers the planner's mid-week re-optimisation to adjust remaining meals — e.g., shift remaining dinners to be higher protein to compensate for a low-protein lunch.

### Logger vs Feedback System

The logger and the Feedback System are distinct entry points with different purposes:
- **Logger:** records *what you ate* — intake correction. Writes directly to the Nutrition Model's intake tracking. "I had a burrito instead of the stir fry."
- **Feedback System:** records *what you thought about what you ate* — quality and preference signals. Routes through the AI classifier to any of the four data destinations. "That stir fry was too salty and the portion was small."

Both accept free-text and involve AI interpretation, but they write to different places and serve different feedback loops.

---

## Food/Mood Journal

A simple free-text journal tied to meal slots. After eating, the user can note how they felt — "felt bloated after dinner", "energy crashed at 3pm", "felt great after that lunch". This is the only health-adjacent feature that lives in MealPrep AI, because it's tightly coupled to specific meals rather than being general health tracking.

### Shape

```json
{
  "2026-04-15": {
    "lunch": {
      "journal_entry": "Felt sluggish about an hour after eating. Might have been too carb-heavy.",
      "logged_at": "2026-04-15T14:30:00"
    },
    "dinner": {
      "journal_entry": "Really satisfied, good energy all evening. Perfect portion.",
      "logged_at": "2026-04-15T20:15:00"
    }
  }
}
```

### How it's used

- **By the Feedback System:** Journal entries are available as context when the AI classifier routes feedback. "I felt bloated after dinner" alongside the actual meal data helps the classifier route more accurately — is it a portion issue (Nutrition Model), an ingredient sensitivity (possibly a health platform directive), or a recipe problem (Recipe Engine)?
- **By the user:** A personal food diary they can review to spot their own patterns. No AI analysis within MealPrep AI — deeper correlations (food vs mood vs biomarkers) are the job of the health platform if connected.
- **Exported to health platform:** If the Personal Biology Platform is connected, journal entries are available as a data source for cross-domain correlation (e.g., correlating food diary with gut microbiome test results or wearable HRV data).

### What this is NOT

This is not a structured health tracking system. No mood scales, no symptom tags, no weight logging, no lab results, no wearable data. All of that lives in the [Personal Biology Platform](https://github.com/TwoLaugh/project-ideas/tree/main/personal-biology-platform) — a separate app that handles the full spectrum of biological data (genomics, blood panels, microbiome, wearables, metabolomics, etc.). MealPrep AI provides the food/intake data; the health platform provides the biological context; the integration layer connects them.

---

## Health Platform Integration

MealPrep AI is a meal planning system, not a health tracking platform. Comprehensive health tracking — mood/energy scales, weight trends, lab results, wearable data, genomics, microbiome — belongs in the separate [Personal Biology Platform](https://github.com/TwoLaugh/project-ideas/tree/main/personal-biology-platform). When the user connects both systems, they communicate through **dietary directives** — discrete, actionable instructions that the health platform pushes to MealPrep AI.

### Why directives, not raw data sync

The health platform holds genomic data, blood panels, microbiome results, wearable streams, and dozens of other biomarker sources. MealPrep AI doesn't need or want any of that raw data — it needs to know *what to do differently when planning meals*. Directives are the translation layer: the health platform does the analysis, MealPrep AI receives the actionable output.

### Directive structure

Each directive is a discrete instruction that maps to something MealPrep AI already understands:

```json
{
  "directive_id": "dir-047",
  "source_platform": "personal-biology-platform",
  "created_at": "2026-04-15T10:00:00Z",
  "status": "pending_review",

  "type": "elimination_trial",
  "evidence": {
    "summary": "Elevated IgG response to eggs detected in food sensitivity panel. Gut microbiome shows reduced barrier integrity markers.",
    "tests_referenced": ["IgG food sensitivity panel (2026-04-10)", "Gut microbiome (2026-04-01)"],
    "confidence": "moderate"
  },

  "instruction": {
    "action": "restrict_ingredient",
    "target": "eggs",
    "scope": "all_forms",
    "duration": {
      "type": "staged_protocol",
      "phases": [
        {"phase": "elimination", "duration_weeks": 6, "rule": "complete exclusion"},
        {"phase": "reintroduction", "duration_weeks": 4, "rule": "introduce once per week, log journal response"},
        {"phase": "resolution", "rule": "user decides based on journal entries during reintroduction"}
      ]
    }
  },

  "maps_to": {
    "model": "preference_model",
    "tier": "hard_constraints",
    "temporary": true,
    "auto_expires": "2026-07-01"
  }
}
```

### Directive types

| Type | Example | Maps to |
|---|---|---|
| **Ingredient restriction** | "Eliminate eggs for 6 weeks" | Preference Model hard constraints (temporary) |
| **Target adjustment** | "Increase iron target to 25mg based on low ferritin + MTHFR variant" | Nutrition Model micro targets |
| **Macro rebalance** | "Increase carb ratio on training days based on HRV recovery data" | Nutrition Model activity adjustments |
| **Elimination trial** | "Low-FODMAP protocol: 4-week elimination, staged reintroduction" | Preference Model hard constraints (temporary, multi-phase) |
| **Reintroduction protocol** | "Reintroduce dairy — one serving per week, monitor journal" | Planner scheduling + journal prompts |
| **Sensitivity downgrade** | "Egg reintroduction successful — downgrade from hard restriction to soft preference (limit to 3x/week)" | Move from hard constraints to taste profile soft constraints |

### Propose, not apply

The same principle as the Recipe Optimiser's user-recipe policy: **directives are always proposed to the user, never auto-applied**. When a directive arrives:

1. The user sees a notification: "Your health platform suggests eliminating eggs for 6 weeks based on your recent food sensitivity panel."
2. The directive shows the evidence summary and the proposed action.
3. The user accepts, rejects, or modifies (e.g., "I'll reduce eggs but not eliminate completely").
4. Accepted directives are applied to the appropriate model with their metadata (source, confidence, expiry) preserved.

The health platform cannot silently change meal plans. Even high-confidence directives require user review.

### What MealPrep AI exports

The integration is bidirectional. MealPrep AI provides data the health platform consumes:

- **Actual intake data** — what the user ate (macro/micro breakdown per meal, per day)
- **Meal composition** — which ingredients, in what quantities
- **Food/mood journal entries** — free-text notes tied to specific meals
- **Adherence to directives** — did the user actually follow the elimination protocol?

This data feeds into the health platform's cross-domain correlations — e.g., correlating actual iron intake with ferritin trends, or food diary entries with gut microbiome changes.

### Without the health platform

MealPrep AI works fully standalone. The health platform integration is an optional enhancement. Without it:
- Nutritional targets are set manually by the user
- No dietary directives — the user manages restrictions and targets directly
- The food/mood journal is a personal diary with no automated analysis
- The system is still a complete meal planning tool — the health platform just makes it smarter about *why* certain targets or restrictions exist

---

## Nutrition Engine (Ingredient Mapping)

The nutrition engine is the calculation layer — it maps recipe ingredients to nutrition databases and computes per-recipe, per-serving nutrition values. Every recipe in the system gets its nutrition calculated through this pipeline, regardless of source (user-entered, imported, AI-generated, web-discovered). External nutrition data from imported recipes is discarded and recalculated internally — see [Data quality and nutrition ownership](system-overview.md#data-quality-and-nutrition-ownership) in the system overview.

### The Problem

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

### Data Sources

**Primary: USDA FoodData Central**
- Free, comprehensive, API available
- ~370k food entries with full macro/micro data
- Best for: raw ingredients, generic foods
- API: https://fdc.nal.usda.gov/api-guide

**Secondary: Open Food Facts**
- Free, open source, API available
- Better for branded/packaged products (Tesco own-brand items, etc.)
- Barcode lookup support
- API: https://world.openfoodfacts.org/data
- Use when: recipe includes a branded ingredient, or when matching Tesco products to nutrition data

**Not using (for now):**
- Nutritionix: good NLP but freemium, adds a paid dependency
- McCance & Widdowson: UK-specific, no API, would need manual import

**Caching strategy:** Start with API calls (simpler), cache results in our DB. Each ingredient only needs to be mapped once — after that it's a local lookup. Over time the cache covers most common ingredients and API calls become rare.

### The Mapping Pipeline

When a recipe is created, imported, or evolved, run this pipeline:

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

#### AI Parsing Step (detail)

Handles vague quantities and normalises everything to grams.

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

#### USDA Match Step (detail)

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

### Ingredient Mapping Cache

Over time, this becomes the most valuable part of the nutrition engine.

```
Table: ingredient_mapping
├── search_term (text, indexed)     "chicken breast skinless raw"
├── usda_fdc_id (integer)           171077
├── nutrition_per_100g (jsonb)      {"calories": 120, "protein": 22.5, "fat": 2.6, "carbs": 0, "iron_mg": 0.4, ...}
├── default_piece_grams (integer)   170  (one chicken breast ≈ 170g)
├── confidence (float)              0.95
├── last_verified (timestamp)
└── source (text)                   "usda" | "open_food_facts" | "manual"
```

The `nutrition_per_100g` field stores full macro and micro data, not just macros — even if the v1 UI only displays macros. After a few months of use, common ingredients are all cached and the system barely needs to call external APIs.

### Accuracy Expectations

| Factor | Impact | Mitigation |
|--------|--------|------------|
| Vague quantities ("a glug", "handful") | ±30% on that ingredient | AI estimates + standard conversions |
| Cooked vs raw confusion | ±20% on that ingredient | AI parsing notes cooked/raw state |
| Wrong USDA match | ±10-20% if close match, worse if wrong | AI selection + caching (fix once, correct forever) |
| Cooking method changes nutrition | ±5-10% | Ignore for v1, use raw-based calculations |
| Cumulative across a full recipe | ±10-15% overall | Good enough for meal planning |
| Branded products | Variable | Fall back to Open Food Facts |

**10-15% overall accuracy is what MyFitnessPal, Cronometer, and every other calorie app achieves.** This is fine for meal planning. Display nutrition as rounded numbers (not "247.3 calories" — say "~250 calories") to communicate appropriate precision.

### Recalculation triggers

Nutrition is recalculated when:
- A recipe is created or imported (all sources — external nutrition data is discarded)
- A recipe is evolved (new version with changed ingredients)
- User manually edits ingredients
- An ingredient mapping is corrected by the user ("that's not the right match")

### User Override

The user can correct any nutrition value:
- "This recipe is actually about 500 calories, not 400" → manual override stored
- "That's not regular chicken, it's free-range organic" → update the ingredient mapping
- Manual overrides take precedence over calculated values
- Flagged so recalculation doesn't overwrite them

---

## How It Gets Used

Each consumer reads a different slice of the Nutrition Model.

### By the Meal Planner

**Primary consumer.** Reads:
- Macro targets (daily floors + weekly averages) and per-meal distribution to score recipe combinations
- Micro targets to identify gaps across the planning period
- Activity adjustments to flex calorie/carb targets on training days
- Eating window to constrain which meal slots are active
- Actual intake (from logger) to adjust remaining targets during mid-week re-optimisation

### By the Recipe Optimiser

**Reads nutrition targets to adapt recipes:**
- "This recipe is 400 calories but the lunch slot needs 650 — increase portion or add a side"
- "Protein is 25g but the floor needs 50g — suggest a higher-protein variant"

### By the Notification System

**Reads intake tracking for alerts:**
- "You're at 100g protein with one meal left — dinner needs to be protein-heavy"
- "You've exceeded your sodium target today"

### By the Feedback System

**Receives routed feedback:**
- Portion complaints ("too much food", "still hungry") → may adjust per-meal calorie distribution
- Journal entries provide context for feedback classification

### By the Health Platform (if connected)

**Reads:**
- Actual intake data (macro/micro per meal/day)
- Meal composition (ingredients, quantities)
- Food/mood journal entries
- Directive adherence

**Writes:**
- Dietary directives (restrictions, target adjustments, elimination protocols)

---

## Boundaries with Other Models

| Concern | Lives in | Not in Nutrition Model because |
|---|---|---|
| Dietary identity (vegetarian, keto, etc.) | Preference Model (hard constraints) | Identity constraint, not a nutritional target — consumed as input |
| Taste preferences (likes, dislikes, cuisines) | Preference Model (taste profile) | Subjective preferences, not nutritional requirements |
| Meal structure, timing preferences | Preference Model (lifestyle config) | Scheduling preferences — but eating windows live here |
| Portion *style* (large-volume, low-density) | Preference Model (taste profile) | Presentation preference — but calorie/gram targets per meal live here |
| Pantry inventory, budget | Provisions | Physical/financial constraints, not nutritional |
| Recipe storage, versioning | Recipe Engine | Catalogue concern — nutrition engine calculates values, Recipe Engine stores them on the recipe |
| Health tracking (mood scales, weight, labs, wearables, genomics) | Personal Biology Platform (separate app) | Full health tracking is a separate system — MealPrep AI receives dietary directives, not raw health data |
| Food-health correlations and reviews | Personal Biology Platform (separate app) | Cross-domain analysis requires biomarker data MealPrep AI doesn't hold |

### Key interaction rules

**Eating windows vs meal timing.** The Nutrition Model's eating window is the hard boundary. The Preference Model's `meal_timing` is the preferred schedule *within* that window. If they conflict (eating window starts at noon but preferred breakfast is 7am), the eating window wins — the planner flags the incompatibility and suggests the user update their meal structure or eating window.

**Portion sizing.** The Nutrition Model owns calorie/gram targets per meal. The Preference Model's `portion_style` (e.g., "prefers large-volume, lower-calorie-density plates") informs *how* to hit those targets. If the Nutrition Model says "400 cal lunch" and the preference says "large volume", the planner leans toward voluminous low-calorie-density foods (big salads with protein) rather than calorie-dense compact meals.

---

## Open Questions

- **Bioavailability modelling.** Cooking methods affect nutrient availability (e.g., cooking tomatoes increases lycopene bioavailability, pairing iron-rich foods with vitamin C increases iron absorption). v1 uses raw-based calculations, but the architecture should not preclude adding cooking-method and ingredient-pairing adjustments to the nutrition calculation later. The mapping pipeline's per-ingredient structure supports this — a future step could apply modifiers after the base calculation.
- **Alcohol tracking.** Some recipes include wine/beer. Alcohol has 7 cal/g and affects metabolism. Worth tracking as a macro-like category, but low priority for v1.
- **Supplement integration.** If the user takes supplements, those contribute to micro targets. Low priority — can be added as standalone logged items via the nutrition logger without changing the model.
- **Plausibility checking.** Should the nutrition engine proactively flag recipes with suspicious numbers? ("This recipe claims 100 calories but has 200g of pasta — that seems low.") Useful as a data quality signal, especially for low-trust imported/discovered recipes.
