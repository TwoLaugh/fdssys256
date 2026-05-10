package com.example.mealprep.provisions.exception;

/**
 * Thrown when {@code PUT /provisions/budget} attempts to change the currency of an existing budget.
 * Mapped to HTTP 422. Currency-change semantics are deliberately locked-out in 01c — silently
 * changing the unit would invalidate spend-tracking history (when 01f/01h lands). Users wanting to
 * switch currencies must wait for an explicit reset endpoint that wipes derived data; tightening
 * this later is breaking, so 01c picks the conservative path.
 */
public class BudgetCurrencyChangeException extends ProvisionsException {

  public BudgetCurrencyChangeException(String message) {
    super(message);
  }
}
