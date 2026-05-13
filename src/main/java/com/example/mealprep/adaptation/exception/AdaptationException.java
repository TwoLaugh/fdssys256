package com.example.mealprep.adaptation.exception;

/**
 * Module-root exception for the adaptation pipeline. Per-failure subclasses extend this so the
 * project-wide {@code GlobalExceptionHandler} (or the module-local {@code
 * AdaptationExceptionHandler}) can map either the specific subtype or the root if a future subtype
 * lands without a corresponding handler.
 *
 * <p>The 01a ticket and {@code lld/adaptation-pipeline.md} (line 199-203) name {@code
 * MealPrepException} as the conceptual root, but the codebase has not (yet) introduced that shared
 * parent — every module root currently extends {@code RuntimeException} directly. 01a follows the
 * established pattern; if a {@code MealPrepException} ships later this declaration can re-parent
 * with no caller impact.
 */
public class AdaptationException extends RuntimeException {

  public AdaptationException(String message) {
    super(message);
  }

  public AdaptationException(String message, Throwable cause) {
    super(message, cause);
  }
}
