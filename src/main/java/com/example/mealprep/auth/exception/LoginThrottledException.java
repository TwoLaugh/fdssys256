package com.example.mealprep.auth.exception;

import java.time.Duration;

/**
 * Thrown when per-username or per-IP login-attempt throttle thresholds are crossed. Carries the
 * {@code Duration} until the oldest counted attempt exits the rolling window so {@code
 * GlobalExceptionHandler} can render {@code Retry-After}. Mapped to HTTP 429 Too Many Requests.
 */
public class LoginThrottledException extends RuntimeException {

  private final Duration retryAfter;

  public LoginThrottledException(Duration retryAfter) {
    super("Login throttled");
    this.retryAfter = retryAfter;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
