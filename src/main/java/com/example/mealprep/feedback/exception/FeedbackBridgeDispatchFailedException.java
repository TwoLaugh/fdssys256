package com.example.mealprep.feedback.exception;

import com.example.mealprep.feedback.spi.Destination;
import java.util.UUID;

/**
 * Thrown internally when a destination bridge's downstream call fails. Per tickets/feedback/01g
 * §22, the bridge catches this, records a {@code FAILED} idempotency row, and does NOT propagate —
 * a post-routing bridge failure must not affect the original feedback transaction. Mapped to HTTP
 * 500 by {@code GlobalExceptionHandler} for the (rare) path where it does surface to a controller
 * (none today; the mapping is defensive so the type is fully wired).
 */
public class FeedbackBridgeDispatchFailedException extends FeedbackException {

  private final transient Destination destination;
  private final transient UUID feedbackId;

  public FeedbackBridgeDispatchFailedException(
      Destination destination, UUID feedbackId, Throwable cause) {
    super(
        "feedback bridge dispatch failed for destination="
            + destination
            + " feedbackId="
            + feedbackId,
        cause);
    this.destination = destination;
    this.feedbackId = feedbackId;
  }

  public Destination destination() {
    return destination;
  }

  public UUID feedbackId() {
    return feedbackId;
  }
}
