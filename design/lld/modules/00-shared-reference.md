# Module: Shared Reference Data

## Purpose
Lookup/reference tables that define the controlled vocabularies used across multiple modules. These are small, rarely-changing tables that get seeded on app startup. Using integer FKs instead of raw strings means smaller storage, faster joins, and no typos.

## Dependencies
**None.** Foundational — seeded at startup.

## Data Model

### nutrient
The master list of trackable nutrients. Used by nutrition targets, ingredient mapping, nutrition logging.

```sql
CREATE TABLE nutrient (
    id          SMALLSERIAL PRIMARY KEY,  -- small: <100 nutrients
    code        VARCHAR(20) NOT NULL UNIQUE,  -- 'calories', 'protein', 'iron', 'vitamin_d'
    name        VARCHAR(50) NOT NULL,         -- 'Calories', 'Protein', 'Iron', 'Vitamin D'
    unit        VARCHAR(10) NOT NULL,         -- 'kcal', 'g', 'mg', 'mcg'
    category    VARCHAR(20) NOT NULL,         -- 'macro', 'mineral', 'vitamin', 'other'
    display_order SMALLINT DEFAULT 0
);

-- Seed data (subset — full list is ~30 rows)
INSERT INTO nutrient (code, name, unit, category, display_order) VALUES
('calories',    'Calories',         'kcal', 'macro',   1),
('protein',     'Protein',          'g',    'macro',   2),
('carbs',       'Carbohydrates',    'g',    'macro',   3),
('fat',         'Fat',              'g',    'macro',   4),
('fibre',       'Fibre',            'g',    'macro',   5),
('saturated_fat','Saturated Fat',   'g',    'macro',   6),
('sugar',       'Sugar',            'g',    'macro',   7),
('sodium',      'Sodium',           'mg',   'mineral', 10),
('potassium',   'Potassium',        'mg',   'mineral', 11),
('calcium',     'Calcium',          'mg',   'mineral', 12),
('iron',        'Iron',             'mg',   'mineral', 13),
('magnesium',   'Magnesium',        'mg',   'mineral', 14),
('zinc',        'Zinc',             'mg',   'mineral', 15),
('phosphorus',  'Phosphorus',       'mg',   'mineral', 16),
('selenium',    'Selenium',         'mcg',  'mineral', 17),
('copper',      'Copper',           'mg',   'mineral', 18),
('manganese',   'Manganese',        'mg',   'mineral', 19),
('vitamin_a',   'Vitamin A',        'mcg',  'vitamin', 20),
('vitamin_c',   'Vitamin C',        'mg',   'vitamin', 21),
('vitamin_d',   'Vitamin D',        'mcg',  'vitamin', 22),
('vitamin_e',   'Vitamin E',        'mg',   'vitamin', 23),
('vitamin_k',   'Vitamin K',        'mcg',  'vitamin', 24),
('vitamin_b1',  'Thiamine (B1)',    'mg',   'vitamin', 25),
('vitamin_b2',  'Riboflavin (B2)',  'mg',   'vitamin', 26),
('vitamin_b3',  'Niacin (B3)',      'mg',   'vitamin', 27),
('vitamin_b5',  'Pantothenic Acid', 'mg',   'vitamin', 28),
('vitamin_b6',  'Vitamin B6',       'mg',   'vitamin', 29),
('vitamin_b12', 'Vitamin B12',      'mcg',  'vitamin', 30),
('folate',      'Folate',           'mcg',  'vitamin', 31),
('cholesterol', 'Cholesterol',      'mg',   'other',   40),
('omega_3',     'Omega-3',          'g',    'other',   41),
('omega_6',     'Omega-6',          'g',    'other',   42);
```

### allergen
```sql
CREATE TABLE allergen (
    id      SMALLSERIAL PRIMARY KEY,
    code    VARCHAR(20) NOT NULL UNIQUE,
    name    VARCHAR(40) NOT NULL
);

INSERT INTO allergen (code, name) VALUES
('nuts',      'Tree Nuts'),
('peanuts',   'Peanuts'),
('shellfish', 'Shellfish'),
('fish',      'Fish'),
('gluten',    'Gluten'),
('soy',       'Soy'),
('eggs',      'Eggs'),
('dairy',     'Dairy'),
('sesame',    'Sesame'),
('celery',    'Celery'),
('mustard',   'Mustard'),
('lupin',     'Lupin'),
('molluscs',  'Molluscs'),
('sulphites', 'Sulphites');
```

### cuisine_type
```sql
CREATE TABLE cuisine_type (
    id      SMALLSERIAL PRIMARY KEY,
    code    VARCHAR(20) NOT NULL UNIQUE,
    name    VARCHAR(40) NOT NULL
);

INSERT INTO cuisine_type (code, name) VALUES
('mediterranean', 'Mediterranean'),
('east_asian',    'East Asian'),
('south_asian',   'South Asian'),
('middle_eastern','Middle Eastern'),
('mexican',       'Mexican'),
('italian',       'Italian'),
('french',        'French'),
('british',       'British'),
('american',      'American'),
('african',       'African'),
('caribbean',     'Caribbean'),
('nordic',        'Nordic'),
('japanese',      'Japanese'),
('thai',          'Thai'),
('korean',        'Korean'),
('vietnamese',    'Vietnamese');
```

### food_category
Used by pantry items, recipe ingredients, shopping items.

```sql
CREATE TABLE food_category (
    id      SMALLSERIAL PRIMARY KEY,
    code    VARCHAR(20) NOT NULL UNIQUE,
    name    VARCHAR(40) NOT NULL
);

INSERT INTO food_category (code, name) VALUES
('protein',   'Protein'),
('dairy',     'Dairy'),
('vegetable', 'Vegetables'),
('fruit',     'Fruit'),
('grain',     'Grains & Pasta'),
('spice',     'Herbs & Spices'),
('condiment', 'Condiments & Sauces'),
('oil',       'Oils & Fats'),
('baking',    'Baking'),
('tinned',    'Tinned & Jarred'),
('frozen',    'Frozen'),
('snack',     'Snacks'),
('drink',     'Drinks'),
('other',     'Other');
```

### meal_type
```sql
CREATE TABLE meal_type (
    id      SMALLSERIAL PRIMARY KEY,
    code    VARCHAR(10) NOT NULL UNIQUE,
    name    VARCHAR(20) NOT NULL
);

INSERT INTO meal_type (code, name) VALUES
('breakfast', 'Breakfast'),
('lunch',     'Lunch'),
('dinner',    'Dinner'),
('snack',     'Snack');
```

## Notes

- All IDs use `SMALLSERIAL` (2 bytes, max 32,767) — these tables will never exceed a few dozen rows
- `code` is the programmatic key (used in API responses, never shown to users)
- `name` is the display label (shown in UI)
- These tables are **read-only at runtime** — seeded at startup, only changed by migrations
- Other modules reference these via FK (e.g., `allergen_id SMALLINT REFERENCES allergen(id)`)
- The API can expose these as `/api/v1/reference/{type}` for the frontend to populate dropdowns

## API

### GET /api/v1/reference/nutrients
### GET /api/v1/reference/allergens
### GET /api/v1/reference/cuisines
### GET /api/v1/reference/food-categories
### GET /api/v1/reference/meal-types

All return the same shape:
```json
[
  {"id": 1, "code": "protein", "name": "Protein"}
]
```

## Consumed By
Every module that stores categorised data.
