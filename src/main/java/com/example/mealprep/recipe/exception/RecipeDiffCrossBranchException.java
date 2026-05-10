package com.example.mealprep.recipe.exception;

/**
 * Thrown when the diff endpoint is called for two versions on different branches. Cross-branch
 * comparison needs branch-merge semantics that don't exist until recipe-01d. Mapped to HTTP 422 by
 * {@link com.example.mealprep.recipe.api.RecipeExceptionHandler}.
 */
public class RecipeDiffCrossBranchException extends RecipeException {

  public RecipeDiffCrossBranchException() {
    super("Cross-branch diff is not supported.");
  }
}
