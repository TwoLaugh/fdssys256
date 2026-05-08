package com.example.mealprep.ai.exception;

/**
 * Thrown when the upstream provider returns a 5xx, the network errors out, or retries are exhausted
 * on a transient failure. Mapped to HTTP 503 by {@code GlobalExceptionHandler} when it surfaces
 * through an admin endpoint; calling modules treat it as a graceful-degrade signal per their LLD's
 * failure-modes section.
 */
public class AiUnavailableException extends AiException {

  public AiUnavailableException(String message) {
    super(message);
  }

  public AiUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
