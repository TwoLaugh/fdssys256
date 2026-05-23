package com.example.mealprep.nutrition.exception;

/**
 * Thrown by {@code NutritionUpdateService.applyFeedbackAdjustment} when the {@code target} string
 * does not resolve to a known target path (nutrition-01i). Mapped to HTTP 422 by {@code
 * NutritionExceptionHandler}.
 *
 * <p>{@code applyFeedbackAdjustment} is in-process only (no REST surface), so in practice this
 * propagates to the feedback bridge, which catches it and books a {@code FAILED} idempotency row
 * with reason {@code unsupported-adjustment-target}. The 422 mapping is retained for completeness
 * and consistency with the rest of the module's exception surface.
 */
public class InvalidFeedbackAdjustmentException extends NutritionException {

  private final String target;

  public InvalidFeedbackAdjustmentException(String target) {
    super("Unsupported feedback adjustment target: " + target);
    this.target = target;
  }

  public String target() {
    return target;
  }
}
