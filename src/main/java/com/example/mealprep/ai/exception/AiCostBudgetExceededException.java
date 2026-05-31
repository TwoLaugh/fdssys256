package com.example.mealprep.ai.exception;

import com.example.mealprep.ai.event.BudgetScope;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * Thrown when a <em>hard</em> cost-budget scope would be (or has been) crossed by the call
 * currently being dispatched — either the per-user {@link BudgetScope#DAILY_USER} scope (only when
 * the daily cap is configured hard) or the system-wide {@link BudgetScope#MONTHLY_TOTAL}
 * runaway-spend ceiling (hard by default). Carries the spend / cap snapshot and the {@link
 * Duration} until the oldest counted call exits the window so {@code AiExceptionHandler} can render
 * a {@code Retry-After} header.
 *
 * <p>Mapped to HTTP 429 Too Many Requests — the cap is a rate concept (cost per unit time), not a
 * permanent failure. Calling modules treat this as an expected graceful-degrade signal (the system
 * never bricks; AI-only features surface "AI features paused").
 *
 * <p>{@code userId} is {@code null} for the {@code MONTHLY_TOTAL} scope (it bills the system, not a
 * person).
 */
public class AiCostBudgetExceededException extends AiException {

  private final UUID userId;
  private final BudgetScope scope;
  private final BigDecimal spentPence;
  private final BigDecimal limitPence;
  private final Duration window;
  private final Duration retryAfter;

  public AiCostBudgetExceededException(
      UUID userId,
      BudgetScope scope,
      BigDecimal spentPence,
      BigDecimal limitPence,
      Duration window,
      Duration retryAfter) {
    super(
        "AI cost budget exceeded ["
            + scope
            + "]"
            + (userId != null ? " for user " + userId : " (system-wide)")
            + " (spent="
            + spentPence
            + "p, limit="
            + limitPence
            + "p, window="
            + window
            + ")");
    this.userId = userId;
    this.scope = scope;
    this.spentPence = spentPence;
    this.limitPence = limitPence;
    this.window = window;
    this.retryAfter = retryAfter;
  }

  /**
   * Backward-compatible per-user constructor (pre-two-scope callers / tests). Defaults the scope to
   * {@link BudgetScope#DAILY_USER} — the historical single-window per-user shape.
   */
  public AiCostBudgetExceededException(
      UUID userId,
      BigDecimal spentPence,
      BigDecimal limitPence,
      Duration window,
      Duration retryAfter) {
    this(userId, BudgetScope.DAILY_USER, spentPence, limitPence, window, retryAfter);
  }

  public UUID userId() {
    return userId;
  }

  public BudgetScope scope() {
    return scope;
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
