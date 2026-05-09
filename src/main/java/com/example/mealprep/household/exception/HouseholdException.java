package com.example.mealprep.household.exception;

/**
 * Module-root exception for the household module. Per-failure subclasses extend this so the {@code
 * GlobalExceptionHandler} (or {@code HouseholdExceptionHandler}) can map either the specific
 * subtype or the root if a future subtype is added without a handler.
 */
public class HouseholdException extends RuntimeException {

  public HouseholdException(String message) {
    super(message);
  }

  public HouseholdException(String message, Throwable cause) {
    super(message, cause);
  }
}
