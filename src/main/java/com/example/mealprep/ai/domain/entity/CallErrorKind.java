package com.example.mealprep.ai.domain.entity;

/**
 * Coarse classification of why an {@link AiCallLog} ended in {@link CallStatus#FAILED}. Persisted
 * as the column {@code error_kind}; null on success.
 */
public enum CallErrorKind {
  /** 5xx, network, or retries exhausted — the upstream is/was unreachable. */
  AI_UNAVAILABLE,
  /** 4xx — caller bug; not retried. */
  INVALID_REQUEST,
  /** Response parsed but didn't match the task's expected shape. */
  INVALID_RESPONSE,
  /**
   * Per-user rolling-window cost cap was reached before the call left the JVM. Recorded with {@code
   * status=FAILED} so ops can see the cost spike that triggered the guard.
   */
  BUDGET_EXCEEDED,
  /**
   * The rendered prompt exceeded the per-task input-token cap (Stage-C context-shape guard).
   * Recorded with {@code status=FAILED} so an oversized-prompt caller bug is visible in the audit
   * log; the call never left the JVM.
   */
  TOKEN_CAP_EXCEEDED
}
