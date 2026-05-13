package com.example.mealprep.adaptation.exception;

/**
 * Thrown when accept is called on a {@code PendingChange} whose {@code expiresAt} is in the past
 * (and the sweep hasn't yet flipped it to {@code EXPIRED}). Mapped to HTTP 422.
 */
public class PendingChangeExpiredException extends AdaptationException {

  public PendingChangeExpiredException(String message) {
    super(message);
  }
}
