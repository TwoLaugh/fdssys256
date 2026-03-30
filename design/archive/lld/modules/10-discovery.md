# Module: Discovery

## Purpose
Finds new recipes online, filters them against user constraints and preferences, scores for fit, and presents suggestions. Accepted recipes get imported into the Recipe module.

## Dependencies
- **→ Profile.getProfile()** — constraints for hard filtering (allergies, dietary identity)
- **→ Feedback.getCurrentPreferenceModel()** — preferences for scoring
- **→ Recipe.getRecipeIndex()** — avoid suggesting duplicates of existing recipes
- **→ Recipe.importFromUrl()** — import accepted recipes
- **→ AI.execute(RECIPE_DISCOVERY)** — search + filter + score

## Data Model

### discovered_recipe
Staging table for recipes found but not yet accepted.

```sql
CREATE TABLE discovered_recipe (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    source_url          TEXT NOT NULL,
    summary             TEXT,
    estimated_calories  INTEGER,
    estimated_prep_mins INTEGER,
    cuisine             VARCHAR(50),
    tags                VARCHAR(50)[],
    fit_score           DECIMAL(3,2),         -- 0.00-1.00
    fit_reasoning       TEXT,
    status              VARCHAR(20) DEFAULT 'suggested',  -- suggested/accepted/rejected/saved_for_later
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dr_status ON discovered_recipe(status);
```

## API

### POST /api/v1/discovery/search
Find new recipes.

**Request:**
```json
{
  "focus": "quick weeknight dinners",
  "count": 5
}
```

**Flow:**
1. → Profile.getProfile() → constraints
2. → Feedback.getCurrentPreferenceModel() → preferences
3. → Recipe.getRecipeIndex() → existing recipe names (to avoid duplicates)
4. → AI.execute(RECIPE_DISCOVERY, {constraints, preferences, existingRecipes, focus, count})
5. AI searches online, filters, scores, returns top results
6. Store as discovered_recipe entries

**Response 200:**
```json
{
  "recipes": [
    {
      "id": 1,
      "name": "15-Minute Miso Salmon",
      "sourceUrl": "https://...",
      "summary": "Quick pan-fried salmon with miso glaze...",
      "estimatedCalories": 420,
      "estimatedPrepMins": 5,
      "cuisine": "japanese",
      "tags": ["quick", "high-protein"],
      "fitScore": 0.92,
      "fitReasoning": "High protein, quick prep, user likes umami flavours"
    }
  ]
}
```

### POST /api/v1/discovery/{id}/accept
Accept a suggestion → imports into Recipe library.

**Flow:**
1. → Recipe.importFromUrl(sourceUrl) → full recipe with nutrition
2. Update discovered_recipe status = 'accepted'

**Response 201:** full recipe from Recipe module.

### POST /api/v1/discovery/{id}/reject
Reject a suggestion (influences future discovery).

### POST /api/v1/discovery/{id}/save
Save for later.

### GET /api/v1/discovery/suggestions
Get current suggestions (status = 'suggested').

## Service Interface

```java
public interface DiscoveryService {
    List<DiscoveredRecipeDto> discoverRecipes(DiscoveryRequest request);
    RecipeDetailDto acceptRecipe(Long discoveredRecipeId);
    void rejectRecipe(Long discoveredRecipeId);
    void saveForLater(Long discoveredRecipeId);
    List<DiscoveredRecipeDto> getCurrentSuggestions();
}
```

## Consumed By
- **Recipe** — accepted recipes flow into the recipe store
- **Planner** — new recipes become available for planning

## Events Emitted
- `discovery.recipe_accepted` — user accepted a suggestion
- `discovery.recipe_rejected` — user rejected (used to improve future discovery)
