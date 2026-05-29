package com.example.mealprep.ai.exception;

/**
 * Thrown when the upstream provider returns HTTP 429 (rate limited). This is the {@code RATE_LIMIT}
 * category of {@code lld/ai.md} Flow 2 — a <em>retryable</em> transient failure, distinct from the
 * fatal caller-bug 4xx bucket.
 *
 * <p>Extends {@link AiUnavailableException} so that, once retries are exhausted, it surfaces with
 * the same graceful-degrade semantics and HTTP 503 mapping as any other transient exhaustion — the
 * subtype exists so the retry decorator can pick the longer rate-limit backoff base (see {@code
 * RetryPolicy#backoffFor}) rather than the shorter transient base.
 *
 * <p>This is the core correctness fix for finding {@code ai-2}: the old hand-rolled loop collapsed
 * 429 into the fatal {@link AiInvalidRequestException} 4xx bucket and failed fast instead of
 * backing off and retrying.
 */
public class AiRateLimitException extends AiUnavailableException {

  public AiRateLimitException(String message) {
    super(message);
  }

  public AiRateLimitException(String message, Throwable cause) {
    super(message, cause);
  }
}
