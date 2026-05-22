package com.example.mealprep.core.origin.exception;

import com.example.mealprep.core.origin.Origin;
import java.time.Duration;

/**
 * Thrown by {@link com.example.mealprep.core.origin.OriginFilter} when an origin-scoped token
 * bucket is exhausted for the calling identity (PER_USER scope) or globally (GLOBAL scope). Per
 * {@code design/origin-tracking-pattern.md} §Authorization differential (2).
 *
 * <p>Mapped to HTTP 429 by {@link com.example.mealprep.config.GlobalExceptionHandler}. The
 * exception handler reads {@link #getRetryAfter()} to populate the {@code Retry-After} header.
 */
public class OriginRateLimitExceededException extends RuntimeException {

  private final Origin origin;
  private final Duration retryAfter;

  public OriginRateLimitExceededException(Origin origin, Duration retryAfter) {
    super("Rate limit exceeded for origin " + origin + ". Retry after " + retryAfter + ".");
    this.origin = origin;
    this.retryAfter = retryAfter;
  }

  public Origin getOrigin() {
    return origin;
  }

  public Duration getRetryAfter() {
    return retryAfter;
  }
}
