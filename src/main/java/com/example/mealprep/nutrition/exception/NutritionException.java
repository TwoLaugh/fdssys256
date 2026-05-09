package com.example.mealprep.nutrition.exception;

/**
 * Module-root exception for the nutrition module. Per-failure subclasses extend this so the {@code
 * GlobalExceptionHandler} (or {@code NutritionExceptionHandler}) can map either the specific
 * subtype or the root if a future subtype is added without a handler.
 */
public class NutritionException extends RuntimeException {

  public NutritionException(String message) {
    super(message);
  }

  public NutritionException(String message, Throwable cause) {
    super(message, cause);
  }
}
