package com.example.mealprep.grocery.exception;

import java.util.UUID;

/**
 * Thrown when a mark-bought is attempted on a {@link
 * com.example.mealprep.grocery.domain.entity.ShoppingListLine} that is already {@code BOUGHT}. Maps
 * to HTTP 409 — the chosen rule per lld/grocery.md line 879 / 1023 ("no-op or 409 per chosen rule";
 * 01d picks 409 so the frontend learns the line was already bought and refreshes). Distinct from
 * the concurrent-mark race, which surfaces as {@code OptimisticLockingFailureException} (also 409)
 * via the parent {@code ShoppingList} {@code @Version}.
 */
public class LineAlreadyBoughtException extends GroceryException {

  private final UUID shoppingListLineId;

  public LineAlreadyBoughtException(UUID shoppingListLineId) {
    super("Shopping list line " + shoppingListLineId + " is already marked bought");
    this.shoppingListLineId = shoppingListLineId;
  }

  public UUID shoppingListLineId() {
    return shoppingListLineId;
  }
}
