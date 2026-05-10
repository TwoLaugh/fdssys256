package com.example.mealprep.recipe.exception;

/**
 * Thrown when a URL import fails — either the fetch leg (timeout / 4xx / 5xx / oversize body) or
 * the extraction leg (no parser strategy matched). Mapped to HTTP 422 by {@code
 * RecipeExceptionHandler} with {@code type = .../recipe-import-failure} and a {@code failureReason}
 * extension on the ProblemDetail.
 */
public class RecipeImportFailureException extends RecipeException {

  private final String failureReason;

  public RecipeImportFailureException(String failureReason) {
    super("Recipe import failed: " + failureReason);
    this.failureReason = failureReason;
  }

  public RecipeImportFailureException(String failureReason, Throwable cause) {
    super("Recipe import failed: " + failureReason, cause);
    this.failureReason = failureReason;
  }

  public String failureReason() {
    return failureReason;
  }
}
