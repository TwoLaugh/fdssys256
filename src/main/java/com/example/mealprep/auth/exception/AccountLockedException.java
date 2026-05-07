package com.example.mealprep.auth.exception;

import java.time.Instant;

/**
 * Thrown when a login is attempted against a user whose {@code lockedUntil} is in the future.
 * Carries the unlock instant so {@code GlobalExceptionHandler} can compute the {@code Retry-After}
 * header. Mapped to HTTP 423 Locked.
 */
public class AccountLockedException extends RuntimeException {

  private final Instant lockedUntil;

  public AccountLockedException(Instant lockedUntil) {
    super("Account locked");
    this.lockedUntil = lockedUntil;
  }

  public Instant lockedUntil() {
    return lockedUntil;
  }
}
