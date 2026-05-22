package com.example.mealprep.preference.exception;

/**
 * Thrown when a {@code TasteProfileDelta} cannot be applied — invalid {@code fieldPath}, missing
 * target item, etc. Mapped to HTTP 422 by {@code PreferenceExceptionHandler}.
 *
 * <p>01c ships the class only; the real instantiation site is the deferred {@code
 * TasteProfileDeltaApplier} implementation in the follow-up {@code 01c-delta-applier} ticket.
 */
public class InvalidTasteProfileDeltaException extends PreferenceException {

  public InvalidTasteProfileDeltaException(String message) {
    super(message);
  }

  public InvalidTasteProfileDeltaException(String message, Throwable cause) {
    super(message, cause);
  }
}
