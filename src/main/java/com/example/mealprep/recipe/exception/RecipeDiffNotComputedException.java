package com.example.mealprep.recipe.exception;

/**
 * Thrown when the diff endpoint is called for two versions on the same branch that are <em>not</em>
 * consecutive (i.e., {@code toVersion.parentVersionId != fromVersionId}). The persisted {@code
 * change_diff} is a key-value lookup, not a recompute — cross-version diffs defer to recipe-01d+.
 * Mapped to HTTP 422 by {@link com.example.mealprep.recipe.api.RecipeExceptionHandler}.
 */
public class RecipeDiffNotComputedException extends RecipeException {

  public RecipeDiffNotComputedException() {
    super(
        "Diff between non-consecutive versions is not computed; use the consecutive-version pair.");
  }
}
