# Data Model

## Entity Relationship Overview

```
UserProfile ─────────────────────────────────────────────────┐
  │                                                          │
  │ 1:N                                                      │
  ▼                                                          │
Allergy                                                      │
Intolerance                                                  │
IngredientDislike                                            │
CuisinePreference                                            │
Equipment                                                    │
                                                             │
Recipe ◄──── RecipeVersion ◄──── RecipeIngredient            │
  │              │                    │                       │
  │              │                    ▼                       │
  │              │            IngredientMapping (cache)       │
  │              │                                           │
  │              ▼                                           │
  │         FeedbackEntry                                    │
  │                                                          │
  ▼                                                          │
MealPlan ◄──── MealSlot ────► RecipeVersion                  │
  │              │                                           │
  │              ▼                                           │
  │         NutritionLog                                     │
  │                                                          │
  ▼                                                          │
ShoppingList ◄──── ShoppingItem                              │
                                                             │
PantryItem ──────────────────────────────────────────────────┘
WasteLog                                                     │
                                                             │
HealthLog ───────────────────────────────────────────────────┘
PreferenceModel (JSON document)
AiCallLog
```

---

## Profile Module

### user_profile
Single row (single user system). Extensible to multi-user by adding rows.

```sql
CREATE TABLE user_profile (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,

    -- Dietary identity
    dietary_identity VARCHAR(30),  -- 'omnivore','vegetarian','vegan','pescatarian','halal','kosher'

    -- Nutrition goals
    calorie_target_min  INTEGER,      -- daily, kcal
    calorie_target_max  INTEGER,
    protein_target_g    INTEGER,      -- daily grams
    carb_target_g       INTEGER,
    fat_target_g        INTEGER,
    goal_context        VARCHAR(30),  -- 'bulking','cutting','maintenance','general_health'

    -- Cooking preferences
    skill_level         VARCHAR(20),  -- 'beginner','intermediate','advanced'
    weeknight_max_mins  INTEGER DEFAULT 30,
    weekend_max_mins    INTEGER DEFAULT 120,
    batch_cook_willing  BOOLEAN DEFAULT FALSE,

    -- Budget
    weekly_budget_pence INTEGER,          -- stored as pence
    price_sensitivity   VARCHAR(20),      -- 'cheapest','mid_range','no_preference'
    organic_preference  BOOLEAN DEFAULT FALSE,

    -- Meal structure
    plans_breakfast     BOOLEAN DEFAULT TRUE,
    plans_lunch         BOOLEAN DEFAULT TRUE,
    plans_dinner        BOOLEAN DEFAULT TRUE,
    plans_snacks        BOOLEAN DEFAULT FALSE,

    -- Variety
    new_recipes_per_week    INTEGER DEFAULT 3,
    adventurousness         VARCHAR(20) DEFAULT 'moderate',  -- 'conservative','moderate','adventurous'

    -- Shopping
    primary_store       VARCHAR(50) DEFAULT 'tesco',
    shopping_frequency  VARCHAR(20) DEFAULT 'weekly',
    delivery_preference VARCHAR(30) DEFAULT 'delivery',

    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### allergy
Hard constraints — never violate.

```sql
CREATE TABLE allergy (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    allergen    VARCHAR(50) NOT NULL,  -- 'nuts','shellfish','gluten','soy','eggs','dairy','sesame'
    UNIQUE(profile_id, allergen)
);
```

### intolerance
Soft constraints — avoid by default, but user may accept occasionally.

```sql
CREATE TABLE intolerance (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    substance   VARCHAR(50) NOT NULL,  -- 'lactose','fructose','histamine','fodmap'
    severity    VARCHAR(20) DEFAULT 'moderate',  -- 'mild','moderate','severe'
    notes       TEXT,  -- "can handle small amounts of hard cheese"
    UNIQUE(profile_id, substance)
);
```

### ingredient_dislike
Things the user doesn't want in their food.

```sql
CREATE TABLE ingredient_dislike (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    ingredient  VARCHAR(100) NOT NULL,
    scope       VARCHAR(20) DEFAULT 'global',  -- 'global' or 'recipe_specific' (recipe-specific stored elsewhere)
    UNIQUE(profile_id, ingredient)
);
```

### cuisine_preference

```sql
CREATE TABLE cuisine_preference (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    cuisine     VARCHAR(50) NOT NULL,  -- 'mediterranean','east_asian','indian','mexican', etc.
    preference  VARCHAR(20) NOT NULL,  -- 'favourite','enjoy','neutral','less_preferred'
    UNIQUE(profile_id, cuisine)
);
```

### equipment

```sql
CREATE TABLE equipment (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    name        VARCHAR(50) NOT NULL,  -- 'oven','hob','microwave','slow_cooker','air_fryer','blender'
    UNIQUE(profile_id, name)
);
```

### fixed_meal_slot
Meals that don't change ("I always have overnight oats for weekday breakfast").

```sql
CREATE TABLE fixed_meal_slot (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    day_of_week VARCHAR(10),           -- 'monday','tuesday',... or NULL for every day
    meal_type   VARCHAR(20) NOT NULL,  -- 'breakfast','lunch','dinner','snack'
    description TEXT NOT NULL,         -- "overnight oats" or a recipe_id
    recipe_id   BIGINT REFERENCES recipe(id),
    applies_to  VARCHAR(20) DEFAULT 'weekday'  -- 'weekday','weekend','all'
);
```

### eating_out_schedule
Days/meals the user regularly eats out.

```sql
CREATE TABLE eating_out_schedule (
    id          BIGSERIAL PRIMARY KEY,
    profile_id  BIGINT NOT NULL REFERENCES user_profile(id),
    day_of_week VARCHAR(10) NOT NULL,
    meal_type   VARCHAR(20) NOT NULL,
    notes       TEXT  -- "Friday dinner — usually eat out"
);
```

---

## Recipe Module

### recipe
The root entity. A recipe is an evolving thing with multiple versions.

```sql
CREATE TABLE recipe (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    source          VARCHAR(20) NOT NULL,    -- 'ai_generated','user_submitted','imported'
    source_url      TEXT,                     -- original URL if imported
    current_version INTEGER NOT NULL DEFAULT 1,

    -- Aggregated stats (denormalised for listing performance)
    avg_rating      DECIMAL(3,2),            -- 1.00-5.00
    times_cooked    INTEGER DEFAULT 0,
    last_cooked_at  TIMESTAMP,

    -- Classification
    meal_types      VARCHAR(100)[],          -- '{breakfast,lunch,dinner,snack}'
    cuisine         VARCHAR(50),
    difficulty      VARCHAR(20),             -- 'easy','medium','hard'
    tags            VARCHAR(50)[],           -- '{one-pot,batch-cook,quick,high-protein}'

    archived        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recipe_cuisine ON recipe(cuisine);
CREATE INDEX idx_recipe_archived ON recipe(archived);
CREATE INDEX idx_recipe_avg_rating ON recipe(avg_rating DESC);
```

### recipe_version
Each evolution of a recipe is a new version.

```sql
CREATE TABLE recipe_version (
    id              BIGSERIAL PRIMARY KEY,
    recipe_id       BIGINT NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
    version_number  INTEGER NOT NULL,

    -- Content
    servings        INTEGER NOT NULL DEFAULT 2,
    prep_time_mins  INTEGER,
    cook_time_mins  INTEGER,
    steps           JSONB NOT NULL,          -- ["Step 1...", "Step 2..."]

    -- Nutrition (per serving, calculated by nutrition engine)
    calories        INTEGER,                 -- kcal
    protein_g       DECIMAL(6,1),
    carbs_g         DECIMAL(6,1),
    fat_g           DECIMAL(6,1),
    fibre_g         DECIMAL(6,1),
    nutrition_data  JSONB,                   -- full micro breakdown if available

    -- Versioning metadata
    changelog       TEXT,                    -- "Reduced honey, added rice vinegar"
    ai_notes        TEXT,                    -- AI's reasoning for changes

    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    UNIQUE(recipe_id, version_number)
);

CREATE INDEX idx_recipe_version_recipe ON recipe_version(recipe_id, version_number DESC);
```

### recipe_ingredient
Ingredients for a specific version.

```sql
CREATE TABLE recipe_ingredient (
    id                  BIGSERIAL PRIMARY KEY,
    recipe_version_id   BIGINT NOT NULL REFERENCES recipe_version(id) ON DELETE CASCADE,

    -- As written in the recipe
    original_text       TEXT NOT NULL,        -- "2 chicken breasts, skinless"

    -- Parsed by AI
    ingredient_name     VARCHAR(150) NOT NULL, -- "chicken breast, skinless, raw"
    quantity            DECIMAL(8,2),          -- 2
    unit                VARCHAR(30),           -- 'piece','g','ml','tbsp','tsp','cup','tin'
    grams_estimate      INTEGER,               -- 340 (AI-estimated weight in grams)

    -- Linked to nutrition database
    ingredient_mapping_id BIGINT REFERENCES ingredient_mapping(id),

    -- Category for shopping list grouping
    category            VARCHAR(30),           -- 'protein','dairy','vegetable','grain','spice','other'

    display_order       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_recipe_ingredient_version ON recipe_ingredient(recipe_version_id);
```

### recipe_note
Recipe-specific notes (distinct from global preferences).

```sql
CREATE TABLE recipe_note (
    id              BIGSERIAL PRIMARY KEY,
    recipe_id       BIGINT NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
    note            TEXT NOT NULL,
    source          VARCHAR(20) DEFAULT 'user',  -- 'user','ai'
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## Nutrition Module

### ingredient_mapping
Cache of ingredient → nutrition database mappings. Grows over time, reduces API calls.

```sql
CREATE TABLE ingredient_mapping (
    id                  BIGSERIAL PRIMARY KEY,
    search_term         VARCHAR(200) NOT NULL UNIQUE,  -- normalised: "chicken breast skinless raw"

    -- USDA match
    usda_fdc_id         INTEGER,
    usda_description    TEXT,

    -- Open Food Facts match (for branded products)
    off_barcode         VARCHAR(20),
    off_product_name    TEXT,

    -- Nutrition per 100g
    calories_per_100g   DECIMAL(6,1),
    protein_per_100g    DECIMAL(6,2),
    carbs_per_100g      DECIMAL(6,2),
    fat_per_100g        DECIMAL(6,2),
    fibre_per_100g      DECIMAL(6,2),
    full_nutrition      JSONB,               -- all micros from USDA

    -- Metadata
    default_piece_grams INTEGER,             -- "1 chicken breast" ≈ 170g
    confidence          DECIMAL(3,2),        -- 0.00-1.00
    source              VARCHAR(20) NOT NULL, -- 'usda','open_food_facts','manual'
    verified_by_user    BOOLEAN DEFAULT FALSE,

    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ingredient_mapping_search ON ingredient_mapping(search_term);
```

---

## Pantry Module

### pantry_item

```sql
CREATE TABLE pantry_item (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(150) NOT NULL,

    quantity        DECIMAL(8,2) NOT NULL,
    unit            VARCHAR(30) NOT NULL,       -- 'g','ml','pieces','tins','bags'
    grams_estimate  INTEGER,                     -- normalised weight for calculations

    category        VARCHAR(30) NOT NULL,        -- 'protein','dairy','vegetable','fruit','grain','spice','condiment','frozen','other'
    storage         VARCHAR(20) NOT NULL DEFAULT 'fridge', -- 'fridge','freezer','cupboard'

    expiry_date     DATE,
    opened          BOOLEAN DEFAULT FALSE,       -- opened items expire faster

    -- Tracking
    added_from      VARCHAR(20),                 -- 'shopping_list','manual','tesco_order'
    added_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pantry_item_expiry ON pantry_item(expiry_date ASC);
CREATE INDEX idx_pantry_item_storage ON pantry_item(storage);
CREATE INDEX idx_pantry_item_category ON pantry_item(category);
```

### waste_log
Track what gets thrown away.

```sql
CREATE TABLE waste_log (
    id              BIGSERIAL PRIMARY KEY,
    item_name       VARCHAR(150) NOT NULL,
    quantity        DECIMAL(8,2),
    unit            VARCHAR(30),
    estimated_cost_pence INTEGER,
    reason          VARCHAR(30),                 -- 'expired','didnt_like','made_too_much','went_bad','other'
    notes           TEXT,
    logged_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## Planner Module

### meal_plan

```sql
CREATE TABLE meal_plan (
    id              BIGSERIAL PRIMARY KEY,
    week_start_date DATE NOT NULL UNIQUE,        -- always a Monday
    status          VARCHAR(20) DEFAULT 'active', -- 'draft','active','completed'

    -- AI generation metadata
    generation_prompt_hash VARCHAR(64),           -- to detect if inputs changed
    estimated_cost_pence   INTEGER,

    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### meal_slot
Individual meal within a plan.

```sql
CREATE TABLE meal_slot (
    id              BIGSERIAL PRIMARY KEY,
    meal_plan_id    BIGINT NOT NULL REFERENCES meal_plan(id) ON DELETE CASCADE,

    date            DATE NOT NULL,
    meal_type       VARCHAR(20) NOT NULL,        -- 'breakfast','lunch','dinner','snack'

    recipe_version_id BIGINT REFERENCES recipe_version(id),
    servings        INTEGER NOT NULL DEFAULT 1,

    -- Status tracking
    status          VARCHAR(20) DEFAULT 'planned', -- 'planned','cooked','skipped','swapped','eating_out'
    cooked_at       TIMESTAMP,

    -- AI reasoning
    ai_notes        TEXT,                        -- "Uses chicken expiring Thursday"

    -- Override
    is_override     BOOLEAN DEFAULT FALSE,       -- user manually placed this
    override_note   TEXT,                        -- "I want tacos on Tuesday"

    UNIQUE(meal_plan_id, date, meal_type)
);

CREATE INDEX idx_meal_slot_plan ON meal_slot(meal_plan_id);
CREATE INDEX idx_meal_slot_date ON meal_slot(date);
CREATE INDEX idx_meal_slot_status ON meal_slot(status);
```

### ingredient_flow
Tracks how purchased/pantry ingredients are allocated across the week.

```sql
CREATE TABLE ingredient_flow (
    id              BIGSERIAL PRIMARY KEY,
    meal_plan_id    BIGINT NOT NULL REFERENCES meal_plan(id) ON DELETE CASCADE,
    ingredient_name VARCHAR(150) NOT NULL,
    total_needed_g  INTEGER NOT NULL,
    from_pantry_g   INTEGER DEFAULT 0,
    to_purchase_g   INTEGER DEFAULT 0,
    pack_size_g     INTEGER,                     -- Tesco pack size if known
    allocations     JSONB NOT NULL               -- [{"meal_slot_id": 1, "grams": 200}, ...]
);
```

---

## Shopping Module

### shopping_list

```sql
CREATE TABLE shopping_list (
    id              BIGSERIAL PRIMARY KEY,
    meal_plan_id    BIGINT NOT NULL REFERENCES meal_plan(id),

    status          VARCHAR(20) DEFAULT 'pending', -- 'pending','shopping','ordered','completed'
    estimated_cost_pence INTEGER,
    actual_cost_pence    INTEGER,

    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);
```

### shopping_item

```sql
CREATE TABLE shopping_item (
    id                  BIGSERIAL PRIMARY KEY,
    shopping_list_id    BIGINT NOT NULL REFERENCES shopping_list(id) ON DELETE CASCADE,

    name                VARCHAR(150) NOT NULL,
    quantity            DECIMAL(8,2) NOT NULL,
    unit                VARCHAR(30) NOT NULL,
    category            VARCHAR(30) NOT NULL,    -- for aisle grouping

    -- Status
    checked             BOOLEAN DEFAULT FALSE,

    -- Tesco matching
    tesco_product_name  TEXT,
    tesco_product_id    VARCHAR(50),
    estimated_price_pence INTEGER,
    actual_price_pence  INTEGER,

    -- If substituted
    substituted         BOOLEAN DEFAULT FALSE,
    substitution_note   TEXT,

    display_order       INTEGER DEFAULT 0
);

CREATE INDEX idx_shopping_item_list ON shopping_item(shopping_list_id);
```

---

## Feedback Module

### feedback_entry

```sql
CREATE TABLE feedback_entry (
    id              BIGSERIAL PRIMARY KEY,
    meal_slot_id    BIGINT REFERENCES meal_slot(id),
    recipe_version_id BIGINT NOT NULL REFERENCES recipe_version(id),

    -- User input (raw)
    raw_feedback    TEXT,                         -- "The sauce was too sweet but I loved the texture"

    -- AI-interpreted rubric scores
    taste_score         DECIMAL(3,1),            -- 1.0-5.0
    ease_score          DECIMAL(3,1),
    portion_assessment  VARCHAR(20),             -- 'too_little','right','too_much'
    repeat_desire       VARCHAR(20),             -- 'yes','maybe','no'
    household_notes     TEXT,

    -- Calculated scores
    nutrition_fit_pct   DECIMAL(5,2),            -- how well macros matched daily targets
    cost_per_serving_pence INTEGER,

    -- AI interpretation
    ai_interpretation   TEXT,                    -- "User found sauce too sweet, likely the honey+soy combo"
    ai_suggested_changes TEXT,                   -- "Reduce honey to 1tbsp, add rice vinegar"

    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feedback_recipe_version ON feedback_entry(recipe_version_id);
CREATE INDEX idx_feedback_created ON feedback_entry(created_at DESC);
```

### preference_model
Stored as a versioned JSON document. Only the latest is "active."

```sql
CREATE TABLE preference_model (
    id              BIGSERIAL PRIMARY KEY,
    version         INTEGER NOT NULL,
    model_data      JSONB NOT NULL,              -- the full preference model JSON
    based_on_feedback_count INTEGER,

    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_preference_model_version ON preference_model(version DESC);
```

---

## Health Module

### health_log

```sql
CREATE TABLE health_log (
    id              BIGSERIAL PRIMARY KEY,
    date            DATE NOT NULL,
    time_of_day     VARCHAR(20),                 -- 'morning','midday','evening' or null for daily

    -- Self-reported
    mood_score      INTEGER CHECK (mood_score BETWEEN 1 AND 5),
    energy_score    INTEGER CHECK (energy_score BETWEEN 1 AND 5),
    sleep_quality   INTEGER CHECK (sleep_quality BETWEEN 1 AND 5),

    -- Weight
    weight_kg       DECIMAL(5,2),

    -- Symptoms (stored as array for flexibility)
    symptoms        VARCHAR(50)[],               -- '{bloating,headache,brain_fog,fatigue}'

    notes           TEXT,

    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_health_log_date ON health_log(date DESC);
```

### progress_photo

```sql
CREATE TABLE progress_photo (
    id              BIGSERIAL PRIMARY KEY,
    date            DATE NOT NULL,
    file_path       TEXT NOT NULL,               -- local file path
    notes           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### nutrition_log
What was actually eaten (planned vs actual).

```sql
CREATE TABLE nutrition_log (
    id              BIGSERIAL PRIMARY KEY,
    date            DATE NOT NULL,
    meal_type       VARCHAR(20) NOT NULL,

    -- What was the plan?
    meal_slot_id    BIGINT REFERENCES meal_slot(id),

    -- What actually happened?
    status          VARCHAR(20) NOT NULL,         -- 'as_planned','modified','skipped','unplanned'
    actual_recipe_version_id BIGINT REFERENCES recipe_version(id),
    actual_servings DECIMAL(4,2),
    manual_entry    TEXT,                         -- if unplanned: "grabbed a sandwich from Pret"

    -- Nutrition (actual)
    calories        INTEGER,
    protein_g       DECIMAL(6,1),
    carbs_g         DECIMAL(6,1),
    fat_g           DECIMAL(6,1),

    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),

    UNIQUE(date, meal_type)
);

CREATE INDEX idx_nutrition_log_date ON nutrition_log(date DESC);
```

---

## AI Module

### ai_call_log

```sql
CREATE TABLE ai_call_log (
    id              BIGSERIAL PRIMARY KEY,

    task_type       VARCHAR(50) NOT NULL,         -- 'plan_assembly','recipe_import','feedback_interpret', etc.
    model_used      VARCHAR(50) NOT NULL,         -- 'claude-sonnet-4-6','claude-haiku-4-5'
    model_tier      VARCHAR(20) NOT NULL,         -- 'frontier','mid','cheap'

    input_tokens    INTEGER NOT NULL,
    output_tokens   INTEGER NOT NULL,
    cost_pence      INTEGER NOT NULL,             -- estimated cost in pence
    latency_ms      INTEGER NOT NULL,

    success         BOOLEAN NOT NULL,
    error_message   TEXT,

    -- Optional: store prompt/response for debugging (may want to disable in production for storage)
    prompt_hash     VARCHAR(64),

    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_call_log_task ON ai_call_log(task_type);
CREATE INDEX idx_ai_call_log_created ON ai_call_log(created_at DESC);
```

---

## Recipe Discovery Module

### discovered_recipe
Recipes found online but not yet accepted into the library.

```sql
CREATE TABLE discovered_recipe (
    id              BIGSERIAL PRIMARY KEY,

    name            VARCHAR(200) NOT NULL,
    source_url      TEXT NOT NULL,

    -- AI-extracted summary (not full recipe — only imported if accepted)
    summary         TEXT,
    estimated_calories INTEGER,
    estimated_prep_mins INTEGER,
    cuisine         VARCHAR(50),
    tags            VARCHAR(50)[],

    -- Scoring
    fit_score       DECIMAL(3,2),                -- 0.00-1.00 how well it matches preferences
    fit_reasoning   TEXT,

    -- Status
    status          VARCHAR(20) DEFAULT 'suggested', -- 'suggested','accepted','rejected','saved_for_later'

    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_discovered_status ON discovered_recipe(status);
```

---

## Notification Module

### notification

```sql
CREATE TABLE notification (
    id              BIGSERIAL PRIMARY KEY,

    type            VARCHAR(30) NOT NULL,         -- 'expiry_warning','defrost_reminder','prep_reminder','nutrition_alert','review_ready'
    title           VARCHAR(200) NOT NULL,
    body            TEXT NOT NULL,

    -- What triggered it
    source_type     VARCHAR(30),                  -- 'pantry_item','meal_slot','nutrition_log'
    source_id       BIGINT,

    -- Status
    read            BOOLEAN DEFAULT FALSE,
    dismissed       BOOLEAN DEFAULT FALSE,

    -- Scheduling
    trigger_at      TIMESTAMP NOT NULL,           -- when to show
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_trigger ON notification(trigger_at);
CREATE INDEX idx_notification_unread ON notification(read) WHERE NOT read;
```
