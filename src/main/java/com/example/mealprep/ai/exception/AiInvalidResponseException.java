package com.example.mealprep.ai.exception;

/**
 * Thrown when the upstream response cannot be parsed into the task's expected output type — either
 * malformed JSON or a missing {@code tool_use} block. Mapped to HTTP 502 by {@code
 * GlobalExceptionHandler}; the upstream surfaced something we cannot consume.
 */
public class AiInvalidResponseException extends AiException {

  public AiInvalidResponseException(String message) {
    super(message);
  }

  public AiInvalidResponseException(String message, Throwable cause) {
    super(message, cause);
  }
}
