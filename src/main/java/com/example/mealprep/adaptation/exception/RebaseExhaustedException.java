package com.example.mealprep.adaptation.exception;

/**
 * Emitted by 01c's {@code RebaseOrchestrator} when the WriteApi conflict-retry budget (3 attempts
 * per HLD) is exhausted. Declared here in 01a so 01b's service interfaces can reference the class;
 * the impl that throws lands in 01c. Mapped to HTTP 409.
 */
public class RebaseExhaustedException extends AdaptationException {

  public RebaseExhaustedException(String message) {
    super(message);
  }

  public RebaseExhaustedException(String message, Throwable cause) {
    super(message, cause);
  }
}
