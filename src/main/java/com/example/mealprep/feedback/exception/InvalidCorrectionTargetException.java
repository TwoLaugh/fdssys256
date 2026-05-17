package com.example.mealprep.feedback.exception;

/**
 * Raised when a misclassification correction targets a structurally invalid destination
 * (lld/feedback.md §Flow 4 step 2, line 625; ticket 01f §25). Maps to HTTP 422 {@code
 * .../invalid-correction-target}. The message carries the descriptive reason; the {@code
 * FeedbackExceptionHandler} surfaces it as the ProblemDetail {@code detail} field.
 *
 * <p>Cases (all v1): new destination == original (no-op); original routing already {@code
 * CORRECTED_AWAY}/{@code REPLAYED} (chains unsupported); correcting to RECIPE with no recipe
 * attached to the feedback.
 */
public class InvalidCorrectionTargetException extends FeedbackException {

  public InvalidCorrectionTargetException(String message) {
    super(message);
  }
}
