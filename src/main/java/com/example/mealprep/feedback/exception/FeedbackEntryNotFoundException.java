package com.example.mealprep.feedback.exception;

import java.util.UUID;

/**
 * Thrown when a feedback-entry lookup yields no row, OR when the lookup matches but the caller is
 * not the entry's owner. Both cases collapse to 404 to avoid leaking other users' submissions.
 * Mapped to HTTP 404 by the future {@code FeedbackExceptionHandler}.
 */
public class FeedbackEntryNotFoundException extends FeedbackException {

  private final UUID feedbackEntryId;

  public FeedbackEntryNotFoundException(UUID feedbackEntryId) {
    super("Feedback entry not found: " + feedbackEntryId);
    this.feedbackEntryId = feedbackEntryId;
  }

  public UUID feedbackEntryId() {
    return feedbackEntryId;
  }
}
