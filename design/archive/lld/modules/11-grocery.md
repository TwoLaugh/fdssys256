# Module: Grocery (Tesco Ordering)

## Purpose
Automates adding shopping list items to a Tesco basket via Claude browser control. User always reviews before checkout. Handles product matching and substitution detection.

## Dependencies
- **→ Shopping.getCurrentList()** — items to order
- **→ Shopping.updateItemTescoMatch()** — write back matched product info
- **→ Shopping.markItemSubstituted()** — flag substitutions
- **→ Pantry.addFromShoppingList()** — add purchased items after delivery
- **→ AI (Claude browser control)** — navigate Tesco website

## Data Model

### tesco_order_job
```sql
CREATE TABLE tesco_order_job (
    id                  BIGSERIAL PRIMARY KEY,
    shopping_list_id    BIGINT NOT NULL,      -- reference to Shopping's table
    status              VARCHAR(20) NOT NULL,  -- queued/in_progress/basket_ready/confirmed/failed
    items_matched       INTEGER DEFAULT 0,
    items_failed        INTEGER DEFAULT 0,
    total_basket_pence  INTEGER,
    error_message       TEXT,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### tesco_product_cache
Cache of previously matched products to speed up future orders.

```sql
CREATE TABLE tesco_product_cache (
    id                  BIGSERIAL PRIMARY KEY,
    search_term         VARCHAR(200) NOT NULL,
    tesco_product_id    VARCHAR(50),
    tesco_product_name  TEXT,
    price_pence         INTEGER,
    pack_size           VARCHAR(50),
    last_seen           TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(search_term)
);
```

## API

### POST /api/v1/grocery/order
Start a Tesco order from the current shopping list.

**Response 202 (accepted — async):**
```json
{
  "jobId": 1,
  "status": "queued",
  "message": "Starting Tesco order. You'll review the basket before checkout."
}
```

### GET /api/v1/grocery/order/{jobId}
Check order status.

**Response 200:**
```json
{
  "jobId": 1,
  "status": "basket_ready",
  "itemsMatched": 18,
  "itemsFailed": 2,
  "totalBasketPence": 3450,
  "failedItems": [
    {"name": "Fresh coriander", "reason": "out of stock"}
  ]
}
```

### POST /api/v1/grocery/order/{jobId}/confirm
User confirms checkout after reviewing basket.

**Flow:**
1. Claude confirms checkout on Tesco
2. → Pantry.addFromShoppingList(ordered items)
3. Update job status = 'confirmed'

### POST /api/v1/grocery/order/{jobId}/cancel
Cancel — clears the Tesco basket.

## Service Interface

```java
public interface GroceryService {
    TescoOrderJobDto startOrder(Long shoppingListId);
    TescoOrderJobDto getOrderStatus(Long jobId);
    TescoOrderJobDto confirmOrder(Long jobId);
    void cancelOrder(Long jobId);
}
```

## Internal Flow

```
For each shopping item:
  1. Check tesco_product_cache for previous match
  2. If miss: Claude navigates Tesco → searches item → picks best match
  3. Claude adds to basket with correct quantity
  4. → Shopping.updateItemTescoMatch(item, product, price)
  5. Cache the match in tesco_product_cache

After all items:
  6. Status → basket_ready
  7. User reviews in frontend (sees matches, prices, failed items)
  8. User confirms → Claude proceeds to checkout
```

## Consumed By
- **Shopping** — triggers ordering, receives product match data
- **Pantry** — receives purchased items after confirmation

## Events Emitted
- `grocery.basket_ready` — basket assembled, awaiting user review
- `grocery.order_confirmed` — checkout confirmed
- `grocery.substitution_detected` — Tesco substituted an item
