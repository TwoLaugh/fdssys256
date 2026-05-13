package com.example.mealprep.planner.exception;

import java.util.UUID;

/**
 * Thrown when a re-opt suggestion lookup misses. Defined in 01a so 01c (read API) and 01k
 * (listeners) can throw without amending the exception package later. Mapped to HTTP 404.
 */
public class ReoptSuggestionNotFoundException extends PlannerException {

  private final UUID suggestionId;

  public ReoptSuggestionNotFoundException(UUID suggestionId) {
    super("Re-opt suggestion not found: " + suggestionId);
    this.suggestionId = suggestionId;
  }

  public UUID suggestionId() {
    return suggestionId;
  }
}
