package com.example.mealprep.recipe.exception;

/**
 * Thrown when a rating request is internally inconsistent — specifically when the {@code versionId}
 * in the body does not belong to the {@code recipeId} in the path. Mapped to HTTP 400 by {@link
 * com.example.mealprep.recipe.api.RecipeExceptionHandler}.
 */
public class RecipeRatingValidationException extends RecipeException {

  public RecipeRatingValidationException(String message) {
    super(message);
  }
}
