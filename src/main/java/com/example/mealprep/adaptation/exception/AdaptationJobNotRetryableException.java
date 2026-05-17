package com.example.mealprep.adaptation.exception;

/**
 * Thrown when an admin retry-failed-job request targets a job that is not in {@code FAILED} state.
 * Mapped to HTTP 409 by {@link com.example.mealprep.adaptation.api.AdaptationExceptionHandler}.
 */
public class AdaptationJobNotRetryableException extends AdaptationException {

  public AdaptationJobNotRetryableException(String message) {
    super(message);
  }
}
