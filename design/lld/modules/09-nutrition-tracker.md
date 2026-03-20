# Module: Nutrition Tracker

## Purpose
Tracks planned vs actual nutrition intake. Pre-populates from the meal plan each day, user confirms/skips/adjusts, provides daily and weekly dashboards.

## Dependencies
- **→ Shared Reference** — `meal_type` lookup table (FK reference)
- **→ Planner.getCurrentPlan()** — get planned meals for each day
- **→ Recipe (via plan slots)** — recipe nutrition data per serving
- **→ NutritionEngine.aggregateMeals()** — calculate daily totals
- **→ Profile.getNutritionTargets()** — targets for comparison

## Data Model

### nutrition_log
```sql
CREATE TABLE nutrition_log (
    id                          BIGSERIAL PRIMARY KEY,
    date                        DATE NOT NULL,
    meal_type_id                SMALLINT NOT NULL REFERENCES meal_type(id),
    meal_slot_id                BIGINT,               -- reference to Planner's table
    status                      VARCHAR(20) NOT NULL,  -- as_planned/modified/skipped/unplanned
    actual_recipe_version_id    BIGINT,               -- reference to Recipe's table
    actual_servings             DECIMAL(4,2),
    manual_entry                TEXT,                  -- "grabbed a sandwich from Pret"
    calories                    INTEGER,
    protein_g                   DECIMAL(6,1),
    carbs_g                     DECIMAL(6,1),
    fat_g                       DECIMAL(6,1),
    created_at                  TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(date, meal_type_id)
);

CREATE INDEX idx_nl_date ON nutrition_log(date DESC);
```

## API

### GET /api/v1/nutrition/daily/{date}
Daily nutrition with per-meal breakdown.

**Response 200:**
```json
{
  "date": "2026-03-23",
  "target": {"calories": 2100, "proteinG": 150, "carbsG": 200, "fatG": 70},
  "actual": {"calories": 1850, "proteinG": 135, "carbsG": 180, "fatG": 62},
  "percentages": {"calories": 88, "proteinG": 90, "carbsG": 90, "fatG": 89},
  "meals": [
    {
      "mealType": {"id": 1, "code": "breakfast", "name": "Breakfast"},
      "status": "as_planned",
      "recipeName": "Overnight Oats",
      "calories": 350,
      "proteinG": 15,
      "carbsG": 50,
      "fatG": 12
    },
    {
      "mealType": {"id": 3, "code": "dinner", "name": "Dinner"},
      "status": "skipped",
      "recipeName": "Salmon Stir Fry",
      "calories": 0,
      "proteinG": 0,
      "carbsG": 0,
      "fatG": 0
    }
  ]
}
```

### GET /api/v1/nutrition/weekly/{weekStartDate}
Weekly summary with daily totals and averages.

**Response 200:**
```json
{
  "weekStartDate": "2026-03-23",
  "target": {"calories": 2100, "proteinG": 150, "carbsG": 200, "fatG": 70},
  "dailyAverage": {"calories": 1980, "proteinG": 142, "carbsG": 195, "fatG": 65},
  "days": [
    {"date": "2026-03-23", "calories": 2150, "proteinG": 145, "carbsG": 210, "fatG": 68}
  ]
}
```

### PUT /api/v1/nutrition/log/{date}/{mealType}
Update what was actually eaten.

**Request:**
```json
{
  "status": "modified",
  "actualServings": 1.5
}
```

Or for an unplanned meal:
```json
{
  "status": "unplanned",
  "manualEntry": "Sandwich from Pret — approx 450cal",
  "calories": 450,
  "proteinG": 25,
  "carbsG": 45,
  "fatG": 18
}
```

## Service Interface

```java
public interface NutritionTrackerService {
    DailyNutritionDto getDailyNutrition(LocalDate date);
    WeeklyNutritionDto getWeeklyNutrition(LocalDate weekStartDate);
    NutritionLogDto updateLog(LocalDate date, Short mealTypeId, UpdateNutritionLogRequest request);

    // Called when a new plan is generated — pre-populate the week
    void populateFromPlan(Long mealPlanId);

    // Called when a meal is cooked — confirm as "as_planned"
    void confirmMealAsPlanned(LocalDate date, Short mealTypeId, Long recipeVersionId, double servings);

    // Called when a meal is skipped
    void markMealSkipped(LocalDate date, Short mealTypeId);
}
```

## Consumed By
- **Health** — nutrition data for weekly/monthly reviews
- **Planner** — historical nutrition data to spot patterns (consistently under on protein, etc.)

## Events Emitted
- `nutrition.daily_logged` — full day of nutrition logged
- `nutrition.significant_deviation` — daily intake far from target (>30% off)
