# Module: Pantry

## Purpose
Tracks ingredient inventory across fridge, freezer, and cupboard. Handles additions (from shopping, manual), deductions (when meals are cooked), expiry tracking, freezer management, and food waste logging.

## Dependencies
- **→ Shared Reference** — `food_category` lookup table (FK reference)

Other modules push data into it:
- **← Planner** calls deductIngredients() when a meal is cooked
- **← Shopping** calls addFromShoppingList() when shopping is completed

## Data Model

### pantry_item
```sql
CREATE TABLE pantry_item (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(150) NOT NULL,
    quantity        DECIMAL(8,2) NOT NULL,
    unit            VARCHAR(30) NOT NULL,
    grams_estimate  INTEGER,
    food_category_id SMALLINT NOT NULL REFERENCES food_category(id),
    storage         VARCHAR(20) NOT NULL DEFAULT 'fridge',  -- fridge/freezer/cupboard
    expiry_date     DATE,
    opened          BOOLEAN DEFAULT FALSE,
    added_from      VARCHAR(20),              -- shopping_list/manual/tesco_order
    added_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pi_expiry ON pantry_item(expiry_date ASC);
CREATE INDEX idx_pi_storage ON pantry_item(storage);
CREATE INDEX idx_pi_category ON pantry_item(food_category_id);
```

### waste_log
```sql
CREATE TABLE waste_log (
    id                      BIGSERIAL PRIMARY KEY,
    item_name               VARCHAR(150) NOT NULL,
    quantity                DECIMAL(8,2),
    unit                    VARCHAR(30),
    estimated_cost_pence    INTEGER,
    reason                  VARCHAR(30),      -- expired/didnt_like/made_too_much/went_bad/other
    notes                   TEXT,
    logged_at               TIMESTAMP NOT NULL DEFAULT NOW()
);
```

## API

### GET /api/v1/pantry
List all items.

**Query params:** `storage` (fridge/freezer/cupboard), `category`, `expiringSoon` (boolean — within 3 days), `sort` (expiry/name/category)

**Response 200:**
```json
[
  {
    "id": 1,
    "name": "Chicken breast",
    "quantity": 500,
    "unit": "g",
    "gramsEstimate": 500,
    "category": {"id": 1, "code": "protein", "name": "Protein"},
    "storage": "fridge",
    "expiryDate": "2026-03-22",
    "opened": false,
    "addedFrom": "shopping_list",
    "addedAt": "2026-03-20T10:00:00Z",
    "daysUntilExpiry": 2
  }
]
```

### POST /api/v1/pantry
Add an item.

**Request:**
```json
{
  "name": "Chicken breast",
  "quantity": 500,
  "unit": "g",
  "foodCategoryId": 1,
  "storage": "fridge",
  "expiryDate": "2026-03-22"
}
```

### PUT /api/v1/pantry/{id}
Update item (adjust quantity, change storage, mark as opened, etc.).

### DELETE /api/v1/pantry/{id}
Remove item.

### POST /api/v1/pantry/{id}/waste
Remove item and log as waste.

**Request:**
```json
{
  "reason": "expired",
  "estimatedCostPence": 250,
  "notes": "Forgot about it"
}
```

### GET /api/v1/pantry/waste?from={date}&to={date}
Get waste log for a date range.

**Response 200:**
```json
{
  "items": [
    {"itemName": "Spinach", "reason": "expired", "estimatedCostPence": 100, "loggedAt": "2026-03-20"}
  ],
  "totalWastePence": 450,
  "count": 3
}
```

## Service Interface

```java
public interface PantryService {
    List<PantryItemDto> listItems(PantryFilterRequest filter);
    PantryItemDto addItem(CreatePantryItemRequest request);
    PantryItemDto updateItem(Long id, UpdatePantryItemRequest request);
    void removeItem(Long id);

    void logWaste(Long itemId, WasteRequest request);
    WasteReportDto getWasteLog(LocalDate from, LocalDate to);

    // Called by Planner when a meal is cooked
    void deductIngredients(List<IngredientDeduction> ingredients);

    // Called by Shopping when shopping is completed
    void addFromShoppingList(List<PurchasedItem> items);

    // Called by Planner for context assembly
    List<PantryItemDto> getAvailableItems();
    List<PantryItemDto> getItemsExpiringBefore(LocalDate date);

    // Called by Notification module
    List<PantryItemDto> getExpiringWithinDays(int days);
    List<PantryItemDto> getFreezerItemsNeedingDefrost(LocalDate mealDate);
}

public record IngredientDeduction(
    String ingredientName,
    int gramsToDeduct
) {}

public record PurchasedItem(
    String name,
    double quantity,
    String unit,
    Short foodCategoryId,
    String storage,
    LocalDate expiryDate
) {}
```

## Deduction Logic

When `deductIngredients()` is called:

1. For each ingredient in the recipe:
   - Find matching pantry item(s) by name (fuzzy match — "chicken breast" matches "Chicken Breast Fillets")
   - Deduct the recipe's gram amount from the pantry item
   - If quantity reaches 0 or below → remove the item
   - If multiple pantry items match, deduct from the one expiring soonest first
2. If no match found → log a warning but don't fail (user may have already used it)

## Consumed By
- **Planner** — getAvailableItems(), getItemsExpiringBefore(), deductIngredients()
- **Shopping** — addFromShoppingList(), getAvailableItems() (to subtract from shopping list)
- **Notification** — getExpiringWithinDays(), getFreezerItemsNeedingDefrost()
- **Health** — getWasteLog() (for weekly review waste stats)

## Events Emitted
- `pantry.item_expiring_soon` — item within 2 days of expiry
- `pantry.item_depleted` — item fully used up
- `pantry.waste_logged` — food was wasted
