package com.example.mealprep.grocery.exception;

import java.util.UUID;

/** Thrown when a grocery order is missing or owned by another user. Maps to HTTP 404. */
public class GroceryOrderNotFoundException extends GroceryException {

  private final UUID orderId;

  public GroceryOrderNotFoundException(UUID orderId) {
    super("Grocery order " + orderId + " not found");
    this.orderId = orderId;
  }

  public UUID orderId() {
    return orderId;
  }
}
