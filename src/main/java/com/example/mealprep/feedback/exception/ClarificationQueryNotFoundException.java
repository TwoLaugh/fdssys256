package com.example.mealprep.feedback.exception;

import java.util.UUID;

/**
 * Thrown when a clarification-query lookup yields no row, OR when the lookup matches but the caller
 * is not the parent feedback entry's owner. Both cases collapse to 404 to avoid leaking other
 * users' clarification queue. Mapped to HTTP 404 by {@code FeedbackExceptionHandler}.
 */
public class ClarificationQueryNotFoundException extends FeedbackException {

  private final UUID queryId;

  public ClarificationQueryNotFoundException(UUID queryId) {
    super("Clarification query not found: " + queryId);
    this.queryId = queryId;
  }

  public UUID queryId() {
    return queryId;
  }
}
