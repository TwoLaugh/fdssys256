# Module: Feedback

## Purpose
Collects natural language feedback on meals, uses AI to interpret and score against a rubric, maintains an evolving preference model, and triggers recipe evolution.

## Dependencies
- **→ AI.execute(FEEDBACK_INTERPRET)** — interpret raw feedback into structured scores
- **→ AI.execute(PREFERENCE_MODEL_UPDATE)** — regenerate preference model from accumulated feedback
- **→ Recipe.updateAvgRating()** — update recipe's aggregate rating
- **→ Recipe.getRecipe()** — get recipe details for AI context
- **→ Profile.getNutritionTargets()** — calculate nutrition fit score

## Data Model

### feedback_entry
```sql
CREATE TABLE feedback_entry (
    id                      BIGSERIAL PRIMARY KEY,
    meal_slot_id            BIGINT,               -- reference to Planner's table
    recipe_version_id       BIGINT NOT NULL,      -- reference to Recipe's table
    raw_feedback            TEXT,
    taste_score             DECIMAL(3,1),         -- 1.0-5.0
    ease_score              DECIMAL(3,1),
    portion_assessment      VARCHAR(20),          -- too_little/right/too_much
    repeat_desire           VARCHAR(20),          -- yes/maybe/no
    household_notes         TEXT,
    nutrition_fit_pct       DECIMAL(5,2),
    cost_per_serving_pence  INTEGER,
    ai_interpretation       TEXT,
    ai_suggested_changes    TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fe_recipe ON feedback_entry(recipe_version_id);
CREATE INDEX idx_fe_created ON feedback_entry(created_at DESC);
```

### preference_model
Versioned AI-maintained JSON document.

```sql
CREATE TABLE preference_model (
    id                      BIGSERIAL PRIMARY KEY,
    version                 INTEGER NOT NULL,
    model_data              JSONB NOT NULL,
    based_on_feedback_count INTEGER,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pm_version ON preference_model(version DESC);
```

## Preference Model Shape

```json
{
  "last_updated": "2026-04-15",
  "based_on_feedback_count": 87,
  "flavour_preferences": {
    "likes": ["tangy/acidic", "umami-rich"],
    "dislikes": ["overly sweet savoury dishes"],
    "notes": "..."
  },
  "texture_preferences": { "likes": [], "dislikes": [], "notes": "" },
  "ingredient_preferences": {
    "favourites": ["chicken thighs", "lemon"],
    "disliked": ["coriander"],
    "trending_positive": ["tahini"],
    "trending_negative": []
  },
  "cuisine_preferences": { "favourites": [], "enjoys": [], "less_preferred": [] },
  "cooking_preferences": { "preferred_complexity": "", "enjoyed_techniques": [] },
  "portion_patterns": { "notes": "" },
  "meal_type_preferences": { "breakfast": "", "lunch": "", "dinner": "", "snacks": "" },
  "recipes_to_repeat": [{"name": "", "reason": ""}],
  "recipes_to_avoid": [{"name": "", "reason": ""}],
  "active_experiments": []
}
```

Max ~2000 tokens. Regenerated every 5 feedback entries or weekly.

## API

### POST /api/v1/feedback
Submit feedback for a meal.

**Request:**
```json
{
  "mealSlotId": 2,
  "feedback": "The chicken was great but the sauce was too sweet. Portions were fine."
}
```

**Flow:**
1. Load recipe details for context
2. → AI.execute(FEEDBACK_INTERPRET, {feedback, recipe, rubric}) → scores + interpretation
3. → Profile.getNutritionTargets() → calculate nutrition_fit_pct
4. Store feedback entry
5. → Recipe.updateAvgRating(recipeId)
6. If total feedback count % 5 == 0 → regenerate preference model

**Response 201:**
```json
{
  "id": 1,
  "tasteScore": 3.5,
  "easeScore": 4.0,
  "portionAssessment": "right",
  "repeatDesire": "yes",
  "aiInterpretation": "User liked the protein but found sauce too sweet. Likely honey/soy ratio.",
  "aiSuggestedChanges": "Reduce honey from 2tbsp to 1tbsp, add rice vinegar."
}
```

### GET /api/v1/feedback?recipeId={id}
All feedback for a recipe.

### GET /api/v1/feedback/recent?count={n}
Most recent feedback entries.

### GET /api/v1/preference-model
Current preference model.

### POST /api/v1/preference-model/regenerate
Force-regenerate.

**Flow:**
1. Load current preference model
2. Load all feedback since last update
3. → AI.execute(PREFERENCE_MODEL_UPDATE, {currentModel, newFeedback}) → updated model
4. Store new version

**Response 200:** new preference model.

## Service Interface

```java
public interface FeedbackService {
    FeedbackEntryDto submitFeedback(Long mealSlotId, String rawFeedback);
    List<FeedbackEntryDto> getFeedbackForRecipe(Long recipeId);
    List<FeedbackEntryDto> getRecentFeedback(int count);

    PreferenceModelDto getCurrentPreferenceModel();
    PreferenceModelDto regeneratePreferenceModel();
}
```

## Consumed By
- **Planner** — getCurrentPreferenceModel() for plan generation context
- **Discovery** — getCurrentPreferenceModel() for recipe scoring
- **Recipe** — feedback triggers rating updates and recipe evolution
- **Health** — feedback data for weekly reviews

## Events Emitted
- `feedback.submitted` — new feedback (carries recipeId, scores)
- `preference_model.updated` — preference model regenerated
