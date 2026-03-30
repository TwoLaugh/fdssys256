# Module: Planner

## Purpose
The central orchestrator. Generates weekly meal plans by arranging recipes, optimises ingredient utilisation across pack sizes, and handles mid-week adjustments (skips, swaps, rebalancing).

## Dependencies
- **→ Shared Reference** — `meal_type` lookup table (FK reference)
- **→ Profile.getProfile()** — constraints, goals, fixed slots, eating out schedule
- **→ Profile.getNutritionTargets()** — calorie/macro targets
- **→ Recipe.getRecipeIndex()** — lightweight recipe list for pass 1
- **→ Recipe.getRecipe(id)** — full recipe details for pass 2
- **→ Recipe.incrementTimesCooked()** — update recipe stats
- **→ Pantry.getAvailableItems()** — current pantry state
- **→ Pantry.getItemsExpiringBefore()** — items to prioritise
- **→ Pantry.deductIngredients()** — subtract when meal is cooked
- **→ Feedback.getCurrentPreferenceModel()** — learned preferences
- **→ AI.execute(RECIPE_SELECTION)** — pass 1 (select candidates)
- **→ AI.execute(PLAN_ASSEMBLY)** — pass 2 (arrange into week)
- **→ AI.execute(PLAN_ADJUSTMENT)** — rebalance after disruption

## Data Model

### meal_plan
```sql
CREATE TABLE meal_plan (
    id                      BIGSERIAL PRIMARY KEY,
    week_start_date         DATE NOT NULL UNIQUE,
    status                  VARCHAR(20) DEFAULT 'active',  -- draft/active/completed
    generation_prompt_hash  VARCHAR(64),
    estimated_cost_pence    INTEGER,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### meal_slot
```sql
CREATE TABLE meal_slot (
    id                  BIGSERIAL PRIMARY KEY,
    meal_plan_id        BIGINT NOT NULL REFERENCES meal_plan(id) ON DELETE CASCADE,
    date                DATE NOT NULL,
    meal_type_id        SMALLINT NOT NULL REFERENCES meal_type(id),
    recipe_version_id   BIGINT,                   -- FK to Recipe module's table
    servings            INTEGER NOT NULL DEFAULT 1,
    status              VARCHAR(20) DEFAULT 'planned',  -- planned/cooked/skipped/swapped/eating_out
    cooked_at           TIMESTAMP,
    ai_notes            TEXT,
    is_override         BOOLEAN DEFAULT FALSE,
    override_note       TEXT,
    UNIQUE(meal_plan_id, date, meal_type)
);

CREATE INDEX idx_ms_plan ON meal_slot(meal_plan_id);
CREATE INDEX idx_ms_date ON meal_slot(date);
CREATE INDEX idx_ms_status ON meal_slot(status);
```

### ingredient_flow
How ingredients are allocated across the week.

```sql
CREATE TABLE ingredient_flow (
    id              BIGSERIAL PRIMARY KEY,
    meal_plan_id    BIGINT NOT NULL REFERENCES meal_plan(id) ON DELETE CASCADE,
    ingredient_name VARCHAR(150) NOT NULL,
    total_needed_g  INTEGER NOT NULL,
    from_pantry_g   INTEGER DEFAULT 0,
    to_purchase_g   INTEGER DEFAULT 0,
    pack_size_g     INTEGER,
    allocations     JSONB NOT NULL            -- [{"meal_slot_id": 1, "grams": 200}, ...]
);
```

## API

### POST /api/v1/plans/generate
Generate a new weekly meal plan.

**Request:**
```json
{
  "weekStartDate": "2026-03-23",
  "overrides": [
    {"date": "2026-03-25", "mealTypeId": 3, "note": "I want tacos"},
    {"date": "2026-03-28", "mealTypeId": 3, "note": "eating out"}
  ]
}
```

**Flow (two-pass):**
1. Load profile, preference model, pantry, recipe index
2. → AI.execute(RECIPE_SELECTION) with index + context → 15-20 recipe IDs
3. → Recipe.getRecipe(id) for each selected recipe
4. → AI.execute(PLAN_ASSEMBLY) with full recipes + context → structured plan
5. Store meal_plan + meal_slots + ingredient_flow

**Response 201:**
```json
{
  "id": 1,
  "weekStartDate": "2026-03-23",
  "status": "active",
  "estimatedCostPence": 4500,
  "slots": [
    {
      "id": 1,
      "date": "2026-03-23",
      "mealType": {"id": 1, "code": "breakfast", "name": "Breakfast"},
      "recipe": {"id": 5, "name": "Overnight Oats", "calories": 350},
      "servings": 1,
      "status": "planned",
      "aiNotes": "Fixed breakfast slot"
    }
  ],
  "ingredientFlow": [
    {
      "ingredientName": "chicken breast",
      "totalNeededG": 680,
      "fromPantryG": 500,
      "toPurchaseG": 180,
      "allocations": [
        {"mealSlotId": 2, "grams": 200, "date": "2026-03-23"},
        {"mealSlotId": 8, "grams": 250, "date": "2026-03-25"}
      ]
    }
  ],
  "dailyNutrition": [
    {"date": "2026-03-23", "calories": 2150, "proteinG": 145, "carbsG": 210, "fatG": 68}
  ]
}
```

### GET /api/v1/plans/current
Get the active plan.

### GET /api/v1/plans/{id}
Get a specific plan.

### PUT /api/v1/plans/{planId}/slots/{slotId}/cooked
Mark a meal as cooked. Triggers side effects.

**Side effects:**
1. Set slot status = 'cooked', cooked_at = now
2. → Pantry.deductIngredients(recipe.ingredients)
3. → Recipe.incrementTimesCooked(recipeId)
4. Creates a NutritionLog entry (via → NutritionTracker or directly)

**Response 200:** updated slot.

### POST /api/v1/plans/{planId}/slots/{slotId}/skip
Skip a meal with intent.

**Request:**
```json
{
  "intent": "adjust_week"
}
```

**Intents:**
- `no_change` — mark as skipped, no plan changes
- `move_to_another_day` — AI picks best day for this meal
- `adjust_week` — AI rebalances remaining days (calls → AI.execute(PLAN_ADJUSTMENT))

**Response 200:** updated plan (may have changed multiple slots).

### POST /api/v1/plans/{planId}/slots/{slotId}/swap
Swap for a different recipe.

**Request:**
```json
{
  "recipeId": 15
}
```
If `recipeId` is null, AI suggests an alternative.

**Response 200:** updated plan.

## Service Interface

```java
public interface PlannerService {
    MealPlanDto generatePlan(GeneratePlanRequest request);
    MealPlanDto getCurrentPlan();
    MealPlanDto getPlan(Long id);

    MealSlotDto markAsCooked(Long planId, Long slotId);
    MealPlanDto skipMeal(Long planId, Long slotId, SkipIntent intent);
    MealPlanDto swapMeal(Long planId, Long slotId, Long newRecipeId);
}

public enum SkipIntent { NO_CHANGE, MOVE_TO_ANOTHER_DAY, ADJUST_WEEK }
```

## Consumed By
- **Shopping** — reads current plan to generate shopping list
- **NutritionTracker** — reads plan slots for daily nutrition population
- **Notification** — reads plan for prep reminders

## Events Emitted
- `plan.generated` — new weekly plan created
- `plan.slot_cooked` — meal was cooked (carries slotId, recipeId, date)
- `plan.slot_skipped` — meal was skipped
- `plan.adjusted` — plan was rebalanced mid-week
