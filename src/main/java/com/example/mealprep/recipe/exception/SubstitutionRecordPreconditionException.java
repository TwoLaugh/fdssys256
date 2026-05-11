package com.example.mealprep.recipe.exception;

import com.example.mealprep.recipe.api.dto.SubstitutionState;

/**
 * Thrown when {@code RecipeSubstitutionRecorder.recordSubstitution} is called on a substitution
 * that isn't in the {@code ACCEPTED} state. Only accepted substitutions are eligible for plan
 * attachment. Mapped to HTTP 422 by {@code RecipeExceptionHandler}.
 */
public class SubstitutionRecordPreconditionException extends RecipeException {

  private final SubstitutionState state;

  public SubstitutionRecordPreconditionException(SubstitutionState state) {
    super("Substitution must be ACCEPTED to record a plan application; current state: " + state);
    this.state = state;
  }

  public SubstitutionState state() {
    return state;
  }
}
