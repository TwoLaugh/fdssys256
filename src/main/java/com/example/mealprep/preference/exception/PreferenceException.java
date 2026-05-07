package com.example.mealprep.preference.exception;

/**
 * Module-root exception for the preference module. Per-failure subclasses extend this so the {@code
 * GlobalExceptionHandler} can map either the specific subtype (preferred) or the root if a future
 * subtype is added without a corresponding handler.
 */
public class PreferenceException extends RuntimeException {

  public PreferenceException(String message) {
    super(message);
  }

  public PreferenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
