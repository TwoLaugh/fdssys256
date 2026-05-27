package com.example.mealprep.grocery.exception;

/**
 * Thrown when the single-flight advisory lock for {@code quote} / {@code place} / {@code
 * refreshStatus} cannot be acquired (a concurrent operation holds it). Maps to HTTP 409 — guards
 * against double-tapping "place order" mid-flight.
 */
public class OrderConcurrencyConflictException extends GroceryException {

  public OrderConcurrencyConflictException(String message) {
    super(message);
  }
}
