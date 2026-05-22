package com.example.mealprep.core.origin.exception;

/**
 * Thrown by {@link com.example.mealprep.core.origin.OriginFilter} for malformed origin headers —
 * unknown {@code X-Origin} value, or non-USER origin missing the required {@code X-Origin-Trace}.
 *
 * <p>Mapped to HTTP 400 by {@link com.example.mealprep.config.GlobalExceptionHandler}.
 */
public class OriginValidationException extends RuntimeException {

  private final String problemSlug;

  public OriginValidationException(String problemSlug, String message) {
    super(message);
    this.problemSlug = problemSlug;
  }

  public String getProblemSlug() {
    return problemSlug;
  }
}
