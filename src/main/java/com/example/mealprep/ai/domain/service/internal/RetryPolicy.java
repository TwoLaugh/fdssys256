package com.example.mealprep.ai.domain.service.internal;

import java.time.Duration;

/**
 * Pure, side-effect-free classifier mapping an Anthropic Messages failure (HTTP status, or a
 * transport-level exception) onto a {@link Category} and a retry strategy. Per {@code lld/ai.md}
 * Flow 2 (the retry-policy table) — extracted as its own class so it stays exhaustively
 * unit-testable in isolation from the HTTP plumbing.
 *
 * <p>The key correctness fix over the old hand-rolled loop (finding {@code ai-2}): <strong>HTTP 429
 * is {@code RATE_LIMIT} and IS retried</strong> (with a longer backoff base), instead of being
 * collapsed into the fatal 4xx bucket. Genuine caller-bug 4xx (AUTH / malformed) stay fatal.
 *
 * <table>
 *   <caption>Classification (lld/ai.md Flow 2)</caption>
 *   <tr><th>Failure</th><th>Category</th><th>Retried?</th><th>Strategy</th></tr>
 *   <tr><td>HTTP 5xx, network timeout</td><td>TIMEOUT</td><td>yes</td><td>exp backoff + jitter</td></tr>
 *   <tr><td>HTTP 429</td><td>RATE_LIMIT</td><td>yes</td><td>exp backoff, longer base</td></tr>
 *   <tr><td>HTTP 401 / 403</td><td>AUTH</td><td>no</td><td>fail fast</td></tr>
 *   <tr><td>HTTP 4xx other</td><td>UNKNOWN</td><td>no</td><td>fail fast</td></tr>
 * </table>
 *
 * <p>{@code SEMANTIC} (missing / malformed {@code tool_use}, JSR-303 failure → one corrective
 * re-prompt) is a structured-output concern owned downstream of the wire call; it is enumerated
 * here for completeness but the dispatch-layer retry decorator only acts on transport categories.
 */
public final class RetryPolicy {

  /** Default base backoff for transient (TIMEOUT / 5xx) retries; doubled per attempt. */
  public static final long TRANSIENT_BASE_BACKOFF_MS = 200L;

  /**
   * Base backoff for {@code RATE_LIMIT} (429) retries — deliberately longer than the transient base
   * so a rate-limited burst actually backs off rather than hammering the upstream into a deeper
   * limit. Doubled per attempt.
   */
  public static final long RATE_LIMIT_BASE_BACKOFF_MS = 1_000L;

  /** Failure taxonomy persisted alongside the LLD's {@code FailureKind} concept. */
  public enum Category {
    /** HTTP 5xx or network timeout — transient; retry with exponential backoff + jitter. */
    TIMEOUT(true),
    /** HTTP 429 — retry with a longer backoff base. */
    RATE_LIMIT(true),
    /** Missing / malformed {@code tool_use} or JSR-303 failure — one corrective re-prompt. */
    SEMANTIC(true),
    /** HTTP 401 / 403 — fail fast, never retried. */
    AUTH(false),
    /** Anthropic {@code content_policy} violation — fail fast. */
    POLICY(false),
    /** Any other 4xx — fail fast. */
    UNKNOWN(false);

    private final boolean retryable;

    Category(boolean retryable) {
      this.retryable = retryable;
    }

    /** Whether a failure of this category should be retried by the dispatch-layer decorator. */
    public boolean retryable() {
      return retryable;
    }
  }

  private RetryPolicy() {}

  /**
   * Classify a non-2xx HTTP status returned by the Anthropic Messages API.
   *
   * @param httpStatus the upstream status code.
   * @return the failure {@link Category}.
   */
  public static Category classifyStatus(int httpStatus) {
    if (httpStatus == 429) {
      return Category.RATE_LIMIT;
    }
    if (httpStatus == 401 || httpStatus == 403) {
      return Category.AUTH;
    }
    if (httpStatus >= 400 && httpStatus < 500) {
      return Category.UNKNOWN; // genuine caller-bug 4xx — fatal.
    }
    // 5xx (and any non-success below 400, e.g. a stray 1xx/3xx we never expect) → transient.
    return Category.TIMEOUT;
  }

  /**
   * Whether a non-2xx HTTP status should be retried.
   *
   * @param httpStatus the upstream status code.
   * @return {@code true} iff the classified category is retryable.
   */
  public static boolean isRetryableStatus(int httpStatus) {
    return classifyStatus(httpStatus).retryable();
  }

  /**
   * Backoff delay before the next attempt of a retryable failure.
   *
   * @param category the failure category (must be retryable for a meaningful value).
   * @param attempt 1-based attempt number that just failed.
   * @return the wait duration before the next attempt; {@link Duration#ZERO} for non-retryable
   *     categories.
   */
  public static Duration backoffFor(Category category, int attempt) {
    int shift = Math.max(0, attempt - 1);
    return switch (category) {
      case RATE_LIMIT -> Duration.ofMillis(RATE_LIMIT_BASE_BACKOFF_MS << shift);
      case TIMEOUT, SEMANTIC -> Duration.ofMillis(TRANSIENT_BASE_BACKOFF_MS << shift);
      case AUTH, POLICY, UNKNOWN -> Duration.ZERO;
    };
  }
}
