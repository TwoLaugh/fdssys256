package com.example.mealprep.adaptation.exception;

/**
 * Thrown when accept / reject is called on a {@code PendingChange} that's been superseded by a
 * newer proposal on the same {@code (recipe_id, change_dimension)} pair. Mapped to HTTP 409.
 */
public class PendingChangeSupersededException extends AdaptationException {

  public PendingChangeSupersededException(String message) {
    super(message);
  }
}
