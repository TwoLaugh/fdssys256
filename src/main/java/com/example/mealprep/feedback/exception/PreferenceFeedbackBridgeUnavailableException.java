package com.example.mealprep.feedback.exception;

/**
 * Raised by the Noop {@link com.example.mealprep.feedback.spi.PreferenceFeedbackBridge} when the
 * preference module hasn't supplied a real {@code applyFeedback} adapter yet. The router classifies
 * this as {@code AI_UNAVAILABLE} so the 01g transient-retry sweep replays once the real bridge is
 * wired.
 */
public class PreferenceFeedbackBridgeUnavailableException extends FeedbackException {

  public PreferenceFeedbackBridgeUnavailableException(String message) {
    super(message);
  }
}
