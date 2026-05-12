package com.example.mealprep.provisions.exception;

/**
 * Thrown by {@code applyCookEvent} when {@code command.isBatchCook == true}. v1 stop-gap — the
 * {@code BatchCookSplitter} fridge/freezer split lands in provisions-01j. Mapped to HTTP 422 by
 * {@code ProvisionsExceptionHandler}.
 */
public class BatchCookNotSupportedException extends ProvisionsException {

  public BatchCookNotSupportedException() {
    super("Batch cook is not supported in this build; see provisions-01j.");
  }
}
