package com.example.mealprep.feedback.exception;

/**
 * Module-root exception for the feedback module. Per-failure subclasses extend this so the future
 * {@code FeedbackExceptionHandler} (landing in feedback-01b alongside the first controller) can
 * dispatch on either a specific subtype or the root.
 *
 * <p>No project-wide {@code MealPrepException} parent exists in the wave-2 modules (cf. {@code
 * NutritionException} / {@code RecipeException}); extend {@code RuntimeException} to stay
 * consistent.
 */
public class FeedbackException extends RuntimeException {

  public FeedbackException(String message) {
    super(message);
  }

  public FeedbackException(String message, Throwable cause) {
    super(message, cause);
  }
}
