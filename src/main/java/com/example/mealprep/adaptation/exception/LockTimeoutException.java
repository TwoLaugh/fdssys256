package com.example.mealprep.adaptation.exception;

/**
 * Trigger-2 lock-acquire failure surface per LLD line 786: feedback waits + retries once within
 * timeout; if the second attempt still can't take the recipe advisory lock, the user sees "couldn't
 * propose; please retry." Mapped to HTTP 409.
 */
public class LockTimeoutException extends AdaptationException {

  public LockTimeoutException(String message) {
    super(message);
  }
}
