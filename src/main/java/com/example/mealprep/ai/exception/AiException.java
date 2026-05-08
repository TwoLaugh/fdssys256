package com.example.mealprep.ai.exception;

/**
 * Module-root exception for the AI module. Per-failure subclasses extend this so {@code
 * GlobalExceptionHandler} can map either the specific subtype (preferred) or the root if a future
 * subtype lands without a corresponding handler.
 */
public class AiException extends RuntimeException {

  protected AiException(String message) {
    super(message);
  }

  protected AiException(String message, Throwable cause) {
    super(message, cause);
  }
}
