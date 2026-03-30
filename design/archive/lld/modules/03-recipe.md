# Module: Recipe

## Purpose
Stores, versions, and manages all recipes regardless of source (AI-generated, user-submitted, imported from URL). Handles recipe CRUD, versioning with changelogs, recipe-specific notes, and AI-powered recipe evolution.

## Dependencies
- **→ Shared Reference** — `cuisine_type`, `meal_type`, `food_category` lookup tables (FK references)
- **→ Profile.getAllergens()** — filter recipes by dietary safety
- **→ Profile.getDislikedIngredients()** — filter/flag disliked ingredients
- **→ AI.execute(RECIPE_IMPORT)** — extract recipe from URL HTML
- **→ AI.execute(RECIPE_GENERATE)** — create new AI recipe
- **→ AI.execute(RECIPE_EVOLVE)** — evolve recipe based on feedback
- **→ AI.chat(RECIPE_SUGGEST_CHANGES)** — interactive recipe modification
- **→ NutritionEngine.calculateForRecipe()** — calculate macros for ingredients

## Data Model

### recipe
```sql
CREATE TABLE recipe (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    source          VARCHAR(20) NOT NULL,     -- ai_generated/user_submitted/imported
    source_url      TEXT,
    current_version INTEGER NOT NULL DEFAULT 1,
    avg_rating      DECIMAL(3,2),
    times_cooked    INTEGER DEFAULT 0,
    last_cooked_at  TIMESTAMP,
    cuisine_type_id SMALLINT REFERENCES cuisine_type(id),
    difficulty      VARCHAR(20),              -- easy/medium/hard
    tags            VARCHAR(50)[],
    archived        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recipe_cuisine ON recipe(cuisine_type_id);
CREATE INDEX idx_recipe_archived ON recipe(archived);
CREATE INDEX idx_recipe_avg_rating ON recipe(avg_rating DESC);
```

### recipe_meal_type
Junction table — a recipe can suit multiple meal types.

```sql
CREATE TABLE recipe_meal_type (
    recipe_id       BIGINT NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
    meal_type_id    SMALLINT NOT NULL REFERENCES meal_type(id),
    PRIMARY KEY (recipe_id, meal_type_id)
);
```

### recipe_version
```sql
CREATE TABLE recipe_version (
    id              BIGSERIAL PRIMARY KEY,
    recipe_id       BIGINT NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
    version_number  INTEGER NOT NULL,
    servings        INTEGER NOT NULL DEFAULT 2,
    prep_time_mins  INTEGER,
    cook_time_mins  INTEGER,
    steps           JSONB NOT NULL,           -- ["Step 1...", "Step 2..."]
    calories        INTEGER,
    protein_g       DECIMAL(6,1),
    carbs_g         DECIMAL(6,1),
    fat_g           DECIMAL(6,1),
    fibre_g         DECIMAL(6,1),
    nutrition_data  JSONB,                    -- full micro breakdown
    changelog       TEXT,
    ai_notes        TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(recipe_id, version_number)
);

CREATE INDEX idx_rv_recipe ON recipe_version(recipe_id, version_number DESC);
```

### recipe_ingredient
```sql
CREATE TABLE recipe_ingredient (
    id                      BIGSERIAL PRIMARY KEY,
    recipe_version_id       BIGINT NOT NULL REFERENCES recipe_version(id) ON DELETE CASCADE,
    original_text           TEXT NOT NULL,
    ingredient_name         VARCHAR(150) NOT NULL,
    quantity                DECIMAL(8,2),
    unit                    VARCHAR(30),
    grams_estimate          INTEGER,
    ingredient_mapping_id   BIGINT,           -- FK to NutritionEngine's table
    food_category_id        SMALLINT REFERENCES food_category(id),
    display_order           INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_ri_version ON recipe_ingredient(recipe_version_id);
```

### recipe_note
```sql
CREATE TABLE recipe_note (
    id          BIGSERIAL PRIMARY KEY,
    recipe_id   BIGINT NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
    note        TEXT NOT NULL,
    source      VARCHAR(20) DEFAULT 'user',   -- user/ai
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

## API

### GET /api/v1/recipes
List with filters.

**Query params:** `source`, `cuisine`, `mealType`, `tags`, `minRating`, `archived`, `search`, `sort` (rating/recent/times_cooked/name), `page`, `size`

**Response 200:**
```json
{
  "content": [
    {
      "id": 1,
      "name": "Chicken Stir Fry",
      "source": "ai_generated",
      "currentVersion": 2,
      "avgRating": 4.5,
      "timesCooked": 3,
      "lastCookedAt": "2026-04-01T18:30:00Z",
      "mealTypes": [{"id": 3, "code": "dinner", "name": "Dinner"}],
      "cuisine": {"id": 2, "code": "east_asian", "name": "East Asian"},
      "difficulty": "easy",
      "tags": ["quick", "high-protein"],
      "calories": 450,
      "proteinG": 38,
      "prepTimeMins": 10,
      "cookTimeMins": 15
    }
  ],
  "totalElements": 45,
  "totalPages": 3,
  "page": 0
}
```

### GET /api/v1/recipes/{id}
Full recipe with current version, ingredients, steps, nutrition, notes.

### GET /api/v1/recipes/{id}/versions
Version history with changelogs and scores.

### POST /api/v1/recipes
Create manually.

**Request:**
```json
{
  "name": "My Favourite Pasta",
  "mealTypes": [3],
  "cuisineTypeId": 6,
  "difficulty": "easy",
  "servings": 2,
  "prepTimeMins": 5,
  "cookTimeMins": 15,
  "ingredients": ["200g penne pasta", "2 cloves garlic, minced", "1 tin chopped tomatoes"],
  "steps": ["Cook pasta.", "Fry garlic.", "Add tomatoes, simmer 10 min.", "Toss."]
}
```
**Response 201:** full recipe (nutrition auto-calculated via → NutritionEngine).

### POST /api/v1/recipes/import
Import from URL.

**Request:** `{ "url": "https://www.bbcgoodfood.com/recipes/..." }`

**Flow:**
1. Fetch URL HTML
2. → AI.execute(RECIPE_IMPORT, {html}) → structured recipe
3. → NutritionEngine.calculateForRecipe(ingredients) → macros
4. Store recipe + version + ingredients
5. Return full recipe

**Response 201:** full recipe.

### POST /api/v1/recipes/{id}/suggest-changes
Chat with AI about a recipe.

**Request:** `{ "message": "Can you make this lower carb?" }`

**Response 200:**
```json
{
  "response": "I'd suggest replacing rice with cauliflower rice...",
  "proposedChanges": {
    "ingredientChanges": [
      {"from": "200g jasmine rice", "to": "300g cauliflower rice"}
    ],
    "estimatedNutrition": {"calories": 380, "proteinG": 38, "carbsG": 15, "fatG": 18}
  }
}
```

### POST /api/v1/recipes/{id}/apply-changes
Confirm suggested changes → creates new version.

**Request:** `{ "changelog": "Swapped rice for cauliflower rice — lower carb version" }`

**Response 201:** full recipe with new version.

### POST /api/v1/recipes/{id}/notes
Add a recipe-specific note.

### PUT /api/v1/recipes/{id}/archive
Soft-delete. **Response 204.**

## Service Interface

```java
public interface RecipeService {
    RecipeDetailDto getRecipe(Long id);
    Page<RecipeSummaryDto> listRecipes(RecipeFilterRequest filter, Pageable pageable);
    RecipeDetailDto createRecipe(CreateRecipeRequest request);
    RecipeDetailDto importFromUrl(String url);
    void archiveRecipe(Long id);

    List<RecipeVersionSummaryDto> getVersionHistory(Long recipeId);
    RecipeDetailDto createNewVersion(Long recipeId, CreateVersionRequest request);

    void addNote(Long recipeId, String note, String source);

    RecipeSuggestionDto suggestChanges(Long recipeId, String userMessage);
    RecipeDetailDto applyChanges(Long recipeId, String changelog);

    // For Planner (lightweight index for pass 1)
    List<RecipeIndexEntry> getRecipeIndex();
    RecipeDetailDto getRecipeVersion(Long recipeId, int versionNumber);

    // Called by Planner when meal is cooked
    void incrementTimesCooked(Long recipeId);
    // Called by Feedback when new rating comes in
    void updateAvgRating(Long recipeId);
}
```

## Consumed By
- **Planner** — getRecipeIndex(), getRecipe(), incrementTimesCooked()
- **Feedback** — updateAvgRating(), createNewVersion() (for recipe evolution)
- **Discovery** — createRecipe() (when accepting a discovered recipe)
- **Shopping** — reads recipe ingredients for list generation
- **NutritionTracker** — reads recipe nutrition data

## Events Emitted
- `recipe.created` — new recipe added to library
- `recipe.version_created` — recipe evolved (new version)
- `recipe.cooked` — recipe was marked as cooked (carries recipeId, versionId)
