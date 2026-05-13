package com.example.mealprep.adaptation.exception;

/**
 * Thrown when accept / reject is called on a {@code PendingChange} whose status is no longer {@code
 * PENDING} (already accepted, rejected, superseded, expired, or modified). Mapped to HTTP 422.
 */
public class PendingChangeNotPendingException extends AdaptationException {

  public PendingChangeNotPendingException(String message) {
    super(message);
  }
}
