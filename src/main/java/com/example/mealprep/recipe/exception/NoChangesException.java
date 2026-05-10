package com.example.mealprep.recipe.exception;

/**
 * Thrown when a manual edit produces no observable change (the computed {@code change_diff} is
 * empty across all four sections). The user supplied a {@code changeReason} that would be
 * meaningless to record; we reject up front rather than silently no-op. Mapped to HTTP 400 by
 * {@link com.example.mealprep.recipe.api.RecipeExceptionHandler}.
 */
public class NoChangesException extends RecipeException {

  public NoChangesException(String message) {
    super(message);
  }
}
