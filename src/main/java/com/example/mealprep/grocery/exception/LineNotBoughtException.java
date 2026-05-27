package com.example.mealprep.grocery.exception;

import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import java.util.UUID;

/**
 * Thrown when {@code undoMarkBought} is attempted on a {@link
 * com.example.mealprep.grocery.domain.entity.ShoppingListLine} that is not currently {@code BOUGHT}
 * — there is nothing to undo. Maps to HTTP 409 (lld/grocery.md §Manual-fulfilment REST line 705:
 * undo is 204 / 404 / 409).
 */
public class LineNotBoughtException extends GroceryException {

  private final UUID shoppingListLineId;
  private final LineFulfilmentStatus currentStatus;

  public LineNotBoughtException(UUID shoppingListLineId, LineFulfilmentStatus currentStatus) {
    super(
        "Shopping list line "
            + shoppingListLineId
            + " is not BOUGHT (current status "
            + currentStatus
            + "); nothing to undo");
    this.shoppingListLineId = shoppingListLineId;
    this.currentStatus = currentStatus;
  }

  public UUID shoppingListLineId() {
    return shoppingListLineId;
  }

  public LineFulfilmentStatus currentStatus() {
    return currentStatus;
  }
}
