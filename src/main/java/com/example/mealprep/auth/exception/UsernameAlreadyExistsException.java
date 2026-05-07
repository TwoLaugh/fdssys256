package com.example.mealprep.auth.exception;

/**
 * Thrown when a registration attempt collides with an existing user (matched by {@code
 * username_normalised}). Mapped to HTTP 409 by {@code GlobalExceptionHandler}.
 */
public class UsernameAlreadyExistsException extends RuntimeException {

  public UsernameAlreadyExistsException(String message) {
    super(message);
  }

  public UsernameAlreadyExistsException(String message, Throwable cause) {
    super(message, cause);
  }
}
