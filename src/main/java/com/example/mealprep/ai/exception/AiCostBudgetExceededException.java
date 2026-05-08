package com.example.mealprep.ai.exception;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * Thrown when the per-user rolling-window cost cap would be (or has been) crossed by the call
 * currently being dispatched. Carries the spend / cap snapshot and the {@link Duration} until the
 * oldest counted call exits the window so {@code GlobalExceptionHandler} can render a {@code
 * Retry-After} header. Mapped to HTTP 429 Too Many Requests — the cap is a rate concept (cost per
 * unit time), not a permanent failure.
 */
public class AiCostBudgetExceededException extends AiException {

  private final UUID userId;
  private final BigDecimal spentPence;
  private final BigDecimal limitPence;
  private final Duration window;
  private final Duration retryAfter;

  public AiCostBudgetExceededException(
      UUID userId,
      BigDecimal spentPence,
      BigDecimal limitPence,
      Duration window,
      Duration retryAfter) {
    super(
        "AI cost budget exceeded for user "
            + userId
            + " (spent="
            + spentPence
            + "p, limit="
            + limitPence
            + "p, window="
            + window
            + ")");
    this.userId = userId;
    this.spentPence = spentPence;
    this.limitPence = limitPence;
    this.window = window;
    this.retryAfter = retryAfter;
  }

  public UUID userId() {
    return userId;
  }

  public BigDecimal spentPence() {
    return spentPence;
  }

  public BigDecimal limitPence() {
    return limitPence;
  }

  public Duration window() {
    return window;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
