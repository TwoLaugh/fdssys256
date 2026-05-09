package com.example.mealprep.provisions.exception;

/**
 * Thrown when a request violates the tracking-mode invariant or the non-negative-quantity
 * constraint. Mapped to HTTP 400 — semantically a client validation error.
 */
public class InvalidInventoryQuantityException extends ProvisionsException {

  public InvalidInventoryQuantityException(String message) {
    super(message);
  }
}
