package com.example.mealprep.feedback.exception;

/**
 * Raised by the Noop {@link com.example.mealprep.feedback.spi.RecipeFeedbackHandler} when the
 * adaptation-pipeline module isn't on the classpath. The router classifies this as {@code
 * AI_UNAVAILABLE} so the 01g transient-retry sweep will replay once the real handler lands.
 */
public class RecipeFeedbackHandlerUnavailableException extends FeedbackException {

  public RecipeFeedbackHandlerUnavailableException(String message) {
    super(message);
  }
}
