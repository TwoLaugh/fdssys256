# Module: Shopping

## Purpose
Generates shopping lists from the meal plan minus pantry stock. Tracks items, costs, and completion status. Hands off to Grocery module for Tesco ordering.

## Dependencies
- **→ Shared Reference** — `food_category` lookup table (FK reference)
- **→ Planner.getCurrentPlan()** — get the meal plan and ingredient flow
- **→ Pantry.getAvailableItems()** — what's already in the house
- **→ Pantry.addFromShoppingList()** — add purchased items to pantry
- **→ Profile.getProfile()** — budget target, store preference

## Data Model

### shopping_list
```sql
CREATE TABLE shopping_list (
    id                      BIGSERIAL PRIMARY KEY,
    meal_plan_id            BIGINT NOT NULL,      -- reference to Planner's table
    status                  VARCHAR(20) DEFAULT 'pending',  -- pending/shopping/ordered/completed
    estimated_cost_pence    INTEGER,
    actual_cost_pence       INTEGER,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMP
);
```

### shopping_item
```sql
CREATE TABLE shopping_item (
    id                      BIGSERIAL PRIMARY KEY,
    shopping_list_id        BIGINT NOT NULL REFERENCES shopping_list(id) ON DELETE CASCADE,
    name                    VARCHAR(150) NOT NULL,
    quantity                DECIMAL(8,2) NOT NULL,
    unit                    VARCHAR(30) NOT NULL,
    food_category_id        SMALLINT NOT NULL REFERENCES food_category(id),
    checked                 BOOLEAN DEFAULT FALSE,
    tesco_product_name      TEXT,
    tesco_product_id        VARCHAR(50),
    estimated_price_pence   INTEGER,
    actual_price_pence      INTEGER,
    substituted             BOOLEAN DEFAULT FALSE,
    substitution_note       TEXT,
    display_order           INTEGER DEFAULT 0
);

CREATE INDEX idx_si_list ON shopping_item(shopping_list_id);
```

## API

### POST /api/v1/shopping/generate
Generate shopping list from current plan.

**Flow:**
1. → Planner.getCurrentPlan() → ingredient flow
2. → Pantry.getAvailableItems()
3. For each ingredient: needed - pantry stock = to buy
4. Group by category, estimate costs
5. Store list + items

**Response 201:**
```json
{
  "id": 1,
  "mealPlanId": 1,
  "status": "pending",
  "estimatedCostPence": 3200,
  "items": [
    {
      "id": 1,
      "name": "Chicken breast",
      "quantity": 500,
      "unit": "g",
      "category": {"id": 1, "code": "protein", "name": "Protein"},
      "checked": false,
      "estimatedPricePence": 350
    }
  ]
}
```

### GET /api/v1/shopping/current
Get the current shopping list.

### GET /api/v1/shopping/{id}
Get a specific list.

### PUT /api/v1/shopping/{listId}/items/{itemId}/check
Toggle an item as checked.

### POST /api/v1/shopping/{id}/complete
Mark shopping as done. Optionally add purchased items to pantry.

**Request:**
```json
{
  "addToPantry": true
}
```

**Flow:**
1. → Pantry.addFromShoppingList(checked items)
2. Update list status = 'completed'
3. Calculate actual_cost_pence from item prices

**Response 200:** updated list.

## Service Interface

```java
public interface ShoppingService {
    ShoppingListDto generateFromCurrentPlan();
    ShoppingListDto getCurrentList();
    ShoppingListDto getList(Long id);
    void checkItem(Long listId, Long itemId, boolean checked);
    ShoppingListDto completeList(Long listId, boolean addToPantry);

    // Called by Grocery module to update Tesco product matches
    void updateItemTescoMatch(Long itemId, String productName, String productId, int pricePence);
    void markItemSubstituted(Long itemId, String note);
}
```

## Consumed By
- **Grocery** — reads shopping list items for Tesco ordering, updates with product matches
- **Health** — reads actual_cost for weekly budget review

## Events Emitted
- `shopping.list_generated` — new list created
- `shopping.list_completed` — shopping done (triggers pantry update)
