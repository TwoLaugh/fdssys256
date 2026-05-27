package com.example.mealprep.grocery.exception;

import java.util.UUID;

/** Thrown when a shopping-list line is missing or owned by another user. Maps to HTTP 404. */
public class ShoppingListLineNotFoundException extends GroceryException {

  private final UUID shoppingListLineId;

  public ShoppingListLineNotFoundException(UUID shoppingListLineId) {
    super("Shopping list line " + shoppingListLineId + " not found");
    this.shoppingListLineId = shoppingListLineId;
  }

  public UUID shoppingListLineId() {
    return shoppingListLineId;
  }
}
