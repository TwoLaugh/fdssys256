# Module: Profile

## Purpose
Stores and serves user identity, dietary constraints, nutrition goals, cooking preferences, and configuration. The foundational module — everything else reads from it.

## Dependencies
- **→ Shared Reference** — `nutrient`, `allergen`, `cuisine_type`, `meal_type` lookup tables (FK references)

## Data Model

### user_profile
```sql
CREATE TABLE user_account (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE user_profile (
    id                  BIGSERIAL PRIMARY KEY,
    account_id          BIGINT NOT NULL UNIQUE REFERENCES user_account(id),
    name                VARCHAR(100) NOT NULL,
    dietary_identity    VARCHAR(30),          -- omnivore/vegetarian/vegan/pescatarian/halal/kosher
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

### nutrition_target
Row-per-nutrient design — supports all macros and micronutrients without schema changes.

```sql
CREATE TABLE nutrition_target (
    id              BIGSERIAL PRIMARY KEY,
    profile_id      BIGINT NOT NULL REFERENCES user_profile(id),
    nutrient_id     SMALLINT NOT NULL REFERENCES nutrient(id),
    target_min      DECIMAL(8,2),
    target_max      DECIMAL(8,2),
    priority        VARCHAR(10) DEFAULT 'normal',  -- critical/normal/nice_to_have
    UNIQUE(profile_id, nutrient_id)
);
```

### allergy
```sql
CREATE TABLE allergy (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    allergen_id SMALLINT NOT NULL REFERENCES allergen(id),
    UNIQUE(profile_id, allergen_id)
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
    id              BIGSERIAL PRIMARY KEY,
    profile_id      BIGINT NOT NULL REFERENCES user_profile(id),
    cuisine_type_id SMALLINT NOT NULL REFERENCES cuisine_type(id),
    preference      VARCHAR(20) NOT NULL,     -- favourite/enjoy/neutral/less_preferred
    UNIQUE(profile_id, cuisine_type_id)
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
    id              BIGSERIAL PRIMARY KEY,
    profile_id      BIGINT NOT NULL REFERENCES user_profile(id),
    day_of_week     VARCHAR(10),              -- monday/tuesday/... or NULL for every day
    meal_type_id    SMALLINT NOT NULL REFERENCES meal_type(id),
    description     TEXT NOT NULL,
    recipe_id       BIGINT,                   -- optional external reference (Recipe module)
    applies_to      VARCHAR(20) DEFAULT 'weekday'  -- weekday/weekend/all
);
```

### eating_out_schedule
```sql
CREATE TABLE eating_out_schedule (
    id              BIGSERIAL PRIMARY KEY,
    profile_id      BIGINT NOT NULL REFERENCES user_profile(id),
    day_of_week     VARCHAR(10) NOT NULL,
    meal_type_id    SMALLINT NOT NULL REFERENCES meal_type(id),
    notes           TEXT
);
```

## API

### POST /api/v1/auth/register
Create a new account.

**Request:**
```json
{
  "username": "irene",
  "password": "...",
  "name": "Irene"
}
```
**Response 201:** `{ "token": "jwt..." }`

### POST /api/v1/auth/login
**Request:** `{ "username": "irene", "password": "..." }`

**Response 200:** `{ "token": "jwt..." }`

All endpoints below require `Authorization: Bearer <token>`.

---

### GET /api/v1/profile
Returns the full profile for the authenticated user.

**Response 200:**
```json
{
  "id": 1,
  "name": "Irene",
  "dietaryIdentity": "omnivore",
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
  "nutritionTargets": [
    {"nutrient": {"id": 1, "code": "calories", "name": "Calories", "unit": "kcal"}, "targetMin": 2000, "targetMax": 2200, "priority": "critical"},
    {"nutrient": {"id": 2, "code": "protein", "name": "Protein", "unit": "g"}, "targetMin": 140, "targetMax": 160, "priority": "critical"},
    {"nutrient": {"id": 3, "code": "carbs", "name": "Carbohydrates", "unit": "g"}, "targetMin": 180, "targetMax": 220, "priority": "normal"},
    {"nutrient": {"id": 4, "code": "fat", "name": "Fat", "unit": "g"}, "targetMin": 60, "targetMax": 80, "priority": "normal"},
    {"nutrient": {"id": 13, "code": "iron", "name": "Iron", "unit": "mg"}, "targetMin": 8, "targetMax": null, "priority": "nice_to_have"}
  ],
  "allergies": [
    {"id": 1, "code": "nuts", "name": "Tree Nuts"}
  ],
  "intolerances": [
    {"substance": "lactose", "severity": "moderate", "notes": "hard cheese is fine"}
  ],
  "ingredientDislikes": ["coriander", "blue cheese"],
  "cuisinePreferences": [
    {"cuisine": {"id": 1, "code": "mediterranean", "name": "Mediterranean"}, "preference": "favourite"}
  ],
  "equipment": ["oven", "hob", "air_fryer", "blender"],
  "fixedMealSlots": [
    {"dayOfWeek": null, "mealType": {"id": 1, "code": "breakfast", "name": "Breakfast"}, "description": "overnight oats", "appliesTo": "weekday"}
  ],
  "eatingOutSchedule": [
    {"dayOfWeek": "friday", "mealType": {"id": 3, "code": "dinner", "name": "Dinner"}, "notes": "usually eat out"}
  ]
}
```

### PUT /api/v1/profile
Partial update. Send only the fields to change.

**Request:**
```json
{
  "goalContext": "cutting",
  "allergies": [1, 9]
}
```
**Response 200:** full profile.

### PUT /api/v1/profile/nutrition-targets
Replace full nutrition target list.

**Request:**
```json
[
  {"nutrientId": 1, "targetMin": 2000, "targetMax": 2200, "priority": "critical"},
  {"nutrientId": 2, "targetMin": 140, "targetMax": 160, "priority": "critical"}
]
```

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
// All methods resolve the current user from Spring Security context.
// Other modules call these with an explicit profileId.
public interface ProfileService {
    UserProfileDto getProfile();
    UserProfileDto updateProfile(UpdateProfileRequest request);

    // Auth (operates on user_account table)
    AuthTokenDto register(RegisterRequest request);
    AuthTokenDto login(LoginRequest request);

    // Nutrition targets (row-per-nutrient)
    List<NutritionTargetDto> getNutritionTargets();
    void updateNutritionTargets(List<UpdateNutritionTargetRequest> targets);

    // Convenience methods for other modules (take profileId explicitly)
    List<AllergenDto> getAllergens(Long profileId);
    List<String> getDislikedIngredients(Long profileId);
    String getDietaryIdentity(Long profileId);
    CookingPreferences getCookingPreferences(Long profileId);
    List<NutritionTargetDto> getNutritionTargets(Long profileId);
    List<FixedMealSlotDto> getFixedMealSlots(Long profileId);
    List<EatingOutDto> getEatingOutSchedule(Long profileId);
    int getNewRecipesPerWeek(Long profileId);
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
