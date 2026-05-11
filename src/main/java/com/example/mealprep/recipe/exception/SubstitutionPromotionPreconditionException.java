package com.example.mealprep.recipe.exception;

import com.example.mealprep.recipe.api.dto.SubstitutionState;

/**
 * Thrown when a promote-to-version action is attempted against a substitution that isn't in the
 * {@code ACCEPTED} state. Mapped to HTTP 422 by {@code RecipeExceptionHandler}.
 */
public class SubstitutionPromotionPreconditionException extends RecipeException {

  private final SubstitutionState state;

  public SubstitutionPromotionPreconditionException(SubstitutionState state) {
    super("Substitution must be ACCEPTED to promote to a version; current state: " + state);
    this.state = state;
  }

  public SubstitutionState state() {
    return state;
  }
}
