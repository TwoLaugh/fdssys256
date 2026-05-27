package com.example.mealprep.grocery.exception;

import java.util.UUID;

/** Thrown when a shopping list is missing or owned by another user. Maps to HTTP 404. */
public class ShoppingListNotFoundException extends GroceryException {

  private final UUID shoppingListId;

  public ShoppingListNotFoundException(UUID shoppingListId) {
    super("Shopping list " + shoppingListId + " not found");
    this.shoppingListId = shoppingListId;
  }

  public UUID shoppingListId() {
    return shoppingListId;
  }
}
