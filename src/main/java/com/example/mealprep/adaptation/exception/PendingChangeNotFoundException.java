package com.example.mealprep.adaptation.exception;

/** Mapped to HTTP 404 by {@link AdaptationExceptionHandler}. */
public class PendingChangeNotFoundException extends AdaptationException {

  public PendingChangeNotFoundException(String message) {
    super(message);
  }
}
