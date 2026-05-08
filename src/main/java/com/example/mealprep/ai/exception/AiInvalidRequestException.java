package com.example.mealprep.ai.exception;

/**
 * Thrown when the upstream provider returns a 4xx — caller bug, never retried. Mapped to HTTP 400
 * by {@code GlobalExceptionHandler}.
 */
public class AiInvalidRequestException extends AiException {

  public AiInvalidRequestException(String message) {
    super(message);
  }

  public AiInvalidRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
