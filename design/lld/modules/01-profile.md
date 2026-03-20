# Module: Profile

## Purpose
Stores and serves user identity, dietary constraints, nutrition goals, cooking preferences, and configuration. The foundational module — everything else reads from it.

## Dependencies
**None.** This module is fully standalone.

## Data Model

### user_profile
```sql
CREATE TABLE user_profile (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    dietary_identity    VARCHAR(30),          -- omnivore/vegetarian/vegan/pescatarian/halal/kosher
    calorie_target_min  INTEGER,
    calorie_target_max  INTEGER,
    protein_target_g    INTEGER,
    carb_target_g       INTEGER,
    fat_target_g        INTEGER,
    goal_context        VARCHAR(30),          -- bulking/cutting/maintenance/general_health
    skill_level         VARCHAR(20),          -- beginner/intermediate/advanced
    weeknight_max_mins  INTEGER DEFAULT 30,
    weekend_max_mins    INTEGER DEFAULT 120,
    batch_cook_willing  BOOLEAN DEFAULT FALSE,
    weekly_budget_pence INTEGER,
    price_sensitivity   VARCHAR(20),          -- cheapest/mid_range/no_preference
    organic_preference  BOOLEAN DEFAULT FALSE,
    plans_breakfast     BOOLEAN DEFAULT TRUE,
    plans_lunch         BOOLEAN DEFAULT TRUE,
    plans_dinner        BOOLEAN DEFAULT TRUE,
    plans_snacks        BOOLEAN DEFAULT FALSE,
    new_recipes_per_week INTEGER DEFAULT 3,
    adventurousness     VARCHAR(20) DEFAULT 'moderate',
    primary_store       VARCHAR(50) DEFAULT 'tesco',
    shopping_frequency  VARCHAR(20) DEFAULT 'weekly',
    delivery_preference VARCHAR(30) DEFAULT 'delivery',
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### allergy
```sql
CREATE TABLE allergy (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    allergen    VARCHAR(50) NOT NULL,
    UNIQUE(profile_id, allergen)
);
```

### intolerance
```sql
CREATE TABLE intolerance (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    substance   VARCHAR(50) NOT NULL,
    severity    VARCHAR(20) DEFAULT 'moderate',
    notes       TEXT,
    UNIQUE(profile_id, substance)
);
```

### ingredient_dislike
```sql
CREATE TABLE ingredient_dislike (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    ingredient  VARCHAR(100) NOT NULL,
    UNIQUE(profile_id, ingredient)
);
```

### cuisine_preference
```sql
CREATE TABLE cuisine_preference (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    cuisine     VARCHAR(50) NOT NULL,
    preference  VARCHAR(20) NOT NULL,     -- favourite/enjoy/neutral/less_preferred
    UNIQUE(profile_id, cuisine)
);
```

### equipment
```sql
CREATE TABLE equipment (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    name        VARCHAR(50) NOT NULL,
    UNIQUE(profile_id, name)
);
```

### fixed_meal_slot
```sql
CREATE TABLE fixed_meal_slot (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    day_of_week VARCHAR(10),              -- monday/tuesday/... or NULL for every day
    meal_type   VARCHAR(20) NOT NULL,
    description TEXT NOT NULL,
    recipe_id   BIGINT,                   -- optional external reference (Recipe module)
    applies_to  VARCHAR(20) DEFAULT 'weekday'  -- weekday/weekend/all
);
```

### eating_out_schedule
```sql
CREATE TABLE eating_out_schedule (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    day_of_week VARCHAR(10) NOT NULL,
    meal_type   VARCHAR(20) NOT NULL,
    notes       TEXT
);
```

## API

### GET /api/v1/profile
Returns the full profile with all related data (allergies, intolerances, dislikes, cuisine prefs, equipment, fixed slots, eating out schedule).

**Response 200:**
```json
{
  "id": 1,
  "name": "Irene",
  "dietaryIdentity": "omnivore",
  "calorieTargetMin": 2000,
  "calorieTargetMax": 2200,
  "proteinTargetG": 150,
  "carbTargetG": 200,
  "fatTargetG": 70,
  "goalContext": "maintenance",
  "skillLevel": "intermediate",
  "weeknightMaxMins": 30,
  "weekendMaxMins": 120,
  "batchCookWilling": true,
  "weeklyBudgetPence": 7000,
  "priceSensitivity": "mid_range",
  "organicPreference": false,
  "plansBreakfast": true,
  "plansLunch": true,
  "plansDinner": true,
  "plansSnacks": false,
  "newRecipesPerWeek": 3,
  "adventurousness": "moderate",
  "primaryStore": "tesco",
  "shoppingFrequency": "weekly",
  "deliveryPreference": "delivery",
  "allergies": ["nuts"],
  "intolerances": [
    {"substance": "lactose", "severity": "moderate", "notes": "hard cheese is fine"}
  ],
  "ingredientDislikes": ["coriander", "blue cheese"],
  "cuisinePreferences": [
    {"cuisine": "mediterranean", "preference": "favourite"}
  ],
  "equipment": ["oven", "hob", "air_fryer", "blender"],
  "fixedMealSlots": [
    {"dayOfWeek": null, "mealType": "breakfast", "description": "overnight oats", "appliesTo": "weekday"}
  ],
  "eatingOutSchedule": [
    {"dayOfWeek": "friday", "mealType": "dinner", "notes": "usually eat out"}
  ]
}
```

### PUT /api/v1/profile
Partial update. Send only the fields to change.

**Request:**
```json
{
  "calorieTargetMin": 2100,
  "allergies": ["nuts", "sesame"]
}
```
**Response 200:** full profile.

### PUT /api/v1/profile/allergies
Replace full allergy list.

### PUT /api/v1/profile/intolerances
Replace full intolerance list.

### PUT /api/v1/profile/dislikes
Replace full dislike list.

### PUT /api/v1/profile/cuisines
Replace full cuisine preference list.

### PUT /api/v1/profile/equipment
Replace full equipment list.

### PUT /api/v1/profile/fixed-slots
Replace fixed meal slots.

### PUT /api/v1/profile/eating-out
Replace eating out schedule.

## Service Interface

```java
public interface ProfileService {
    UserProfileDto getProfile();
    UserProfileDto updateProfile(UpdateProfileRequest request);

    // Convenience methods for other modules
    List<String> getAllergens();
    List<String> getDislikedIngredients();
    String getDietaryIdentity();
    NutritionTargets getNutritionTargets();
    CookingPreferences getCookingPreferences();
    List<FixedMealSlotDto> getFixedMealSlots();
    List<EatingOutDto> getEatingOutSchedule();
    int getNewRecipesPerWeek();
}
```

## Consumed By
- **Planner** — reads constraints, goals, fixed slots, eating out schedule
- **Recipe** — reads allergens + dislikes for filtering
- **Discovery** — reads preferences + constraints for scoring
- **Feedback** — reads nutrition targets for rubric scoring
- **Shopping** — reads budget, store preference
- **Health** — reads nutrition targets for review generation

## Events Emitted
- `profile.updated` — when any profile field changes (other modules may want to invalidate caches)
