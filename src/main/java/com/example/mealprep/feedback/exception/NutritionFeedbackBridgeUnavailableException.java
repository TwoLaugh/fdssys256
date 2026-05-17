package com.example.mealprep.feedback.exception;

/**
 * Raised by the Noop {@link com.example.mealprep.feedback.spi.NutritionFeedbackBridge} when the
 * nutrition module hasn't supplied a real {@code applyFeedback} adapter yet. Classified as {@code
 * AI_UNAVAILABLE} for the same reason as the recipe / preference / provisions counterparts.
 */
public class NutritionFeedbackBridgeUnavailableException extends FeedbackException {

  public NutritionFeedbackBridgeUnavailableException(String message) {
    super(message);
  }
}
