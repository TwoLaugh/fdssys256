package com.example.mealprep.feedback.exception;

import java.util.UUID;

/**
 * Thrown when a user tries to answer a clarification query that is already in {@code ANSWERED}
 * state. Mapped to HTTP 422 Unprocessable Entity by {@code FeedbackExceptionHandler} (LLD line 823
 * specifies a "generic 422"; this named type keeps the mapping explicit rather than relying on a
 * bare {@code IllegalStateException} which the project-wide catch-all would turn into a 500).
 */
public class ClarificationQueryAlreadyAnsweredException extends FeedbackException {

  private final UUID queryId;

  public ClarificationQueryAlreadyAnsweredException(UUID queryId) {
    super("Clarification query already answered: " + queryId);
    this.queryId = queryId;
  }

  public UUID queryId() {
    return queryId;
  }
}
