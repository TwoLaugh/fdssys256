package com.example.mealprep.feedback.exception;

/**
 * Raised by the Noop {@link com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge} when the
 * provisions module hasn't supplied a real {@code applyFeedback} adapter yet. Classified as {@code
 * AI_UNAVAILABLE} for the same reason as the peer Noop bridges.
 */
public class ProvisionsFeedbackBridgeUnavailableException extends FeedbackException {

  public ProvisionsFeedbackBridgeUnavailableException(String message) {
    super(message);
  }
}
