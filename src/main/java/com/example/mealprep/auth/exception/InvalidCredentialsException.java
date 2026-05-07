package com.example.mealprep.auth.exception;

/**
 * Thrown for both unknown-user and wrong-password login failures. The message is intentionally
 * generic — exposing which one would create a username-enumeration oracle. Mapped to HTTP 401 with
 * a fixed "Invalid credentials" detail by {@code GlobalExceptionHandler}.
 */
public class InvalidCredentialsException extends RuntimeException {

  public InvalidCredentialsException() {
    super("Invalid credentials");
  }

  public InvalidCredentialsException(String message) {
    super(message);
  }
}
