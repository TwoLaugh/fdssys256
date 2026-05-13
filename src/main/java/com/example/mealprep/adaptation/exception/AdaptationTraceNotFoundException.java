package com.example.mealprep.adaptation.exception;

/** Mapped to HTTP 404 by {@link AdaptationExceptionHandler}. */
public class AdaptationTraceNotFoundException extends AdaptationException {

  public AdaptationTraceNotFoundException(String message) {
    super(message);
  }
}
