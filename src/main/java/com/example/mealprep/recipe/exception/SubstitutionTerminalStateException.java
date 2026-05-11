package com.example.mealprep.recipe.exception;

import com.example.mealprep.recipe.api.dto.SubstitutionState;

/**
 * Thrown when an accept/reject/promote action is attempted against a substitution that is in a
 * terminal state ({@code SUPERSEDED}). Mapped to HTTP 422 by {@code RecipeExceptionHandler}.
 */
public class SubstitutionTerminalStateException extends RecipeException {

  private final SubstitutionState state;

  public SubstitutionTerminalStateException(SubstitutionState state) {
    super("Substitution is in terminal state and cannot be modified: " + state);
    this.state = state;
  }

  public SubstitutionState state() {
    return state;
  }
}
