package com.example.mealprep.provisions.exception;

/**
 * Module-root exception for the provisions module. Per-failure subclasses extend this so the {@code
 * ProvisionsExceptionHandler} (or the {@code GlobalExceptionHandler} catch-all) can map either the
 * specific subtype or the root if a future subtype lands without a dedicated handler.
 */
public class ProvisionsException extends RuntimeException {

  public ProvisionsException(String message) {
    super(message);
  }

  public ProvisionsException(String message, Throwable cause) {
    super(message, cause);
  }
}
