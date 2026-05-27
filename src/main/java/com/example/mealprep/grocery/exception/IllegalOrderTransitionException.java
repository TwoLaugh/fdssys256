package com.example.mealprep.grocery.exception;

import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;

/**
 * Thrown when an order-lifecycle transition {@code from → to} is not a legal edge per {@code
 * OrderStateMachine}. Maps to HTTP 409.
 */
public class IllegalOrderTransitionException extends GroceryException {

  private final GroceryOrderStatus from;
  private final GroceryOrderStatus to;

  public IllegalOrderTransitionException(GroceryOrderStatus from, GroceryOrderStatus to) {
    super("Illegal order transition: " + from + " → " + to);
    this.from = from;
    this.to = to;
  }

  public GroceryOrderStatus from() {
    return from;
  }

  public GroceryOrderStatus to() {
    return to;
  }
}
