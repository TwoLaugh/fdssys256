package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when {@code GET /api/v1/recipes/{recipeId}} is called for a recipe id that doesn't exist
 * (or has {@code deleted_at} set). Mapped to HTTP 404 by {@code RecipeExceptionHandler}.
 */
public class RecipeNotFoundException extends RecipeException {

  private final UUID recipeId;

  public RecipeNotFoundException(UUID recipeId) {
    super("Recipe not found: " + recipeId);
    this.recipeId = recipeId;
  }

  public UUID recipeId() {
    return recipeId;
  }
}
