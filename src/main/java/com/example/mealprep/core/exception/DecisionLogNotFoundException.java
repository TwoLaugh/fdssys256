package com.example.mealprep.core.exception;

import java.util.UUID;

/**
 * Thrown when a decision-log row is referenced by id but does not exist — currently the {@code
 * parentDecisionId} existence check on the write path (a clearer upfront error than the deferred FK
 * constraint violation). Per lld/core.md §Validation / §Flow 1 step 4.
 *
 * <p>Mapped to HTTP 404 by {@link com.example.mealprep.config.GlobalExceptionHandler}.
 */
public class DecisionLogNotFoundException extends RuntimeException {

  private final UUID decisionId;

  public DecisionLogNotFoundException(UUID decisionId) {
    super("Decision log entry not found: " + decisionId);
    this.decisionId = decisionId;
  }

  public UUID getDecisionId() {
    return decisionId;
  }
}
