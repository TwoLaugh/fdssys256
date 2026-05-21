package com.example.mealprep.preference.exception;

/**
 * Thrown when a taste-profile update would exceed the per-user token budget enforced by {@code
 * TasteProfileBudgetGuard}. Mapped to HTTP 422 by {@code PreferenceExceptionHandler}.
 *
 * <p>01c ships the class only; the guard implementation is deferred to the follow-up {@code
 * 01c-delta-applier} ticket. The exception type exists now so the deferred ticket can throw it
 * without back-touching this module's surface area.
 */
public class TasteProfileBudgetExceededException extends PreferenceException {

  public TasteProfileBudgetExceededException(String message) {
    super(message);
  }
}
