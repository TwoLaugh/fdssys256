package com.example.mealprep.feedback.exception;

import java.util.UUID;

/**
 * Thrown when a user tries to answer a clarification query that has already passed its 7-day TTL
 * and been swept to {@code EXPIRED}. Mapped to HTTP 410 Gone by {@code FeedbackExceptionHandler}.
 * The {@code feedbackEntryId} is surfaced as a ProblemDetail extension field so the client can
 * offer to re-submit the original feedback.
 */
public class ClarificationQueryExpiredException extends FeedbackException {

  private final UUID queryId;
  private final UUID feedbackEntryId;

  public ClarificationQueryExpiredException(UUID queryId, UUID feedbackEntryId) {
    super("Clarification query expired: " + queryId);
    this.queryId = queryId;
    this.feedbackEntryId = feedbackEntryId;
  }

  public UUID queryId() {
    return queryId;
  }

  public UUID feedbackEntryId() {
    return feedbackEntryId;
  }
}
