package com.example.mealprep.core.origin.exception;

import java.math.BigDecimal;

/**
 * Thrown by {@link com.example.mealprep.core.origin.OriginFilter} when an AI-origin request carries
 * a {@code confidence} field below {@code mealprep.origin.ai-confidence-floor}. Per {@code
 * design/origin-tracking-pattern.md} §Authorization differential (1).
 *
 * <p>Mapped to HTTP 422 by {@link com.example.mealprep.config.GlobalExceptionHandler}.
 */
public class ConfidenceBelowThresholdException extends RuntimeException {

  private final BigDecimal threshold;
  private final BigDecimal actual;

  public ConfidenceBelowThresholdException(BigDecimal threshold, BigDecimal actual) {
    super(
        "AI-origin call confidence "
            + actual
            + " is below the configured threshold "
            + threshold
            + ".");
    this.threshold = threshold;
    this.actual = actual;
  }

  public BigDecimal getThreshold() {
    return threshold;
  }

  public BigDecimal getActual() {
    return actual;
  }
}
