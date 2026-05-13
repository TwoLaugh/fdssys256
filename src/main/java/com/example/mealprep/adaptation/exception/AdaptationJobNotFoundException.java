package com.example.mealprep.adaptation.exception;

/** Mapped to HTTP 404 by {@link AdaptationExceptionHandler}. */
public class AdaptationJobNotFoundException extends AdaptationException {

  public AdaptationJobNotFoundException(String message) {
    super(message);
  }
}
