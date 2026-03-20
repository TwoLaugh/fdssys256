# Module: Nutrition Engine

## Purpose
Maps recipe ingredients to nutrition databases (USDA FoodData Central, Open Food Facts), calculates per-serving macros, and maintains an ingredient mapping cache that grows over time.

## Dependencies
- **→ AI.execute(INGREDIENT_PARSE)** — parse "2 chicken breasts, skinless" into structured data
- **→ AI.execute(INGREDIENT_MATCH_USDA)** — pick best USDA match from search results
- **External: USDA FoodData Central API** — nutrition data for raw ingredients
- **External: Open Food Facts API** — nutrition data for branded products

## Data Model

### ingredient_mapping
Cache of ingredient text → nutrition database entry. This is the module's core asset.

```sql
CREATE TABLE ingredient_mapping (
    id                  BIGSERIAL PRIMARY KEY,
    search_term         VARCHAR(200) NOT NULL UNIQUE,

    -- USDA match
    usda_fdc_id         INTEGER,
    usda_description    TEXT,

    -- Open Food Facts match (branded products)
    off_barcode         VARCHAR(20),
    off_product_name    TEXT,

    -- Nutrition per 100g
    calories_per_100g   DECIMAL(6,1),
    protein_per_100g    DECIMAL(6,2),
    carbs_per_100g      DECIMAL(6,2),
    fat_per_100g        DECIMAL(6,2),
    fibre_per_100g      DECIMAL(6,2),
    full_nutrition      JSONB,                -- all micros

    -- Metadata
    default_piece_grams INTEGER,              -- "1 chicken breast" ≈ 170g
    confidence          DECIMAL(3,2),
    source              VARCHAR(20) NOT NULL,  -- usda/open_food_facts/manual
    verified_by_user    BOOLEAN DEFAULT FALSE,

    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_im_search ON ingredient_mapping(search_term);
```

## API

No external REST API. This module is consumed only by other backend modules (primarily Recipe). If we ever need a direct endpoint:

### GET /api/v1/nutrition/ingredients/search?q={term}
Search the mapping cache.

### PUT /api/v1/nutrition/ingredients/{id}
User corrects a mapping.

**Request:**
```json
{
  "caloriesPer100g": 165,
  "proteinPer100g": 31.0,
  "verifiedByUser": true
}
```

## Service Interface

```java
public interface NutritionEngine {
    /**
     * Parse raw ingredient strings, map to nutrition DB, calculate recipe totals.
     * Main entry point — called when a recipe is created/versioned.
     *
     * Returns per-serving nutrition + parsed ingredient details.
     */
    RecipeNutrition calculateForRecipe(List<String> rawIngredients, int servings);

    /**
     * Map a single ingredient. Returns cached result or does full lookup.
     */
    IngredientMappingDto mapIngredient(String ingredientText);

    /**
     * Aggregate nutrition across a list of meals (used by tracker).
     */
    DailyNutrition aggregateMeals(List<MealNutritionInput> meals);
}

public record RecipeNutrition(
    int caloriesPerServing,
    double proteinG,
    double carbsG,
    double fatG,
    double fibreG,
    JsonNode fullNutrition,
    List<ParsedIngredient> parsedIngredients
) {}

public record ParsedIngredient(
    String originalText,
    String ingredientName,
    Double quantity,
    String unit,
    Integer gramsEstimate,
    Long ingredientMappingId,
    String category
) {}
```

## Internal Flow: calculateForRecipe()

```
Input: ["500g chicken thighs", "2 tbsp soy sauce", "1 tbsp honey"]

Step 1: AI parse (single call, all ingredients at once)
  → AI.execute(INGREDIENT_PARSE, {ingredients})
  → [{name: "chicken thigh raw", qty: 500, unit: "g", grams: 500, usdaSearch: "chicken thigh raw"},
     {name: "soy sauce", qty: 2, unit: "tbsp", grams: 30, usdaSearch: "soy sauce"},
     {name: "honey", qty: 1, unit: "tbsp", grams: 21, usdaSearch: "honey"}]

Step 2: For each parsed ingredient, check cache
  → ingredient_mapping WHERE search_term = "chicken thigh raw"
  → HIT: return cached nutrition per 100g
  → MISS: go to step 3

Step 3: USDA API search (on cache miss)
  → GET https://api.nal.usda.gov/fdc/v1/foods/search?query=chicken+thigh+raw
  → Returns top 5-10 matches

Step 4: AI match (on cache miss)
  → AI.execute(INGREDIENT_MATCH_USDA, {searchTerm, usdaResults})
  → Returns: {fdcId: 171077, description: "Chicken, thigh, raw", confidence: 0.95}

Step 5: Fetch full nutrition from USDA
  → GET https://api.nal.usda.gov/fdc/v1/food/{fdcId}
  → Extract: calories, protein, carbs, fat, fibre per 100g

Step 6: Cache the mapping
  → INSERT INTO ingredient_mapping (search_term, usda_fdc_id, calories_per_100g, ...)

Step 7: Calculate per-ingredient nutrition
  → (nutrition_per_100g) × (grams_estimate / 100) for each ingredient

Step 8: Sum and divide by servings
  → Return RecipeNutrition
```

## Consumed By
- **Recipe** — calculateForRecipe() when creating/versioning recipes
- **NutritionTracker** — aggregateMeals() for daily totals
- **Planner** — indirectly (via recipe nutrition data already stored)

## Events Emitted
- `ingredient.mapped` — new ingredient added to cache (useful for monitoring cache growth)
