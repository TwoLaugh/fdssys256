package com.example.mealprep.core.exception;

/**
 * Thrown by {@code DecisionLogTokenBudgetGuard} when the serialised JSONB payload of a decision-log
 * write ({@code inputs} + {@code candidates} + {@code chosen} + {@code emittedDirective}) exceeds
 * the configured cap (64 KB). Guards the audit log against a runaway prompt dumping multi-MB
 * candidate detail. Per lld/core.md §Validation / §Flow 1 step 2 / §Error responses.
 *
 * <p>Mapped to HTTP 422 by {@link com.example.mealprep.config.GlobalExceptionHandler}.
 */
public class DecisionLogPayloadOversizedException extends RuntimeException {

  private final long actualBytes;
  private final long maxBytes;

  public DecisionLogPayloadOversizedException(long actualBytes, long maxBytes) {
    super(
        "Decision-log payload is "
            + actualBytes
            + " bytes, exceeding the "
            + maxBytes
            + "-byte cap");
    this.actualBytes = actualBytes;
    this.maxBytes = maxBytes;
  }

  public long getActualBytes() {
    return actualBytes;
  }

  public long getMaxBytes() {
    return maxBytes;
  }
}
