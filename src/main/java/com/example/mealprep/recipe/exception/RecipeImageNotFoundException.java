package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when {@code GET /api/v1/recipes/{recipeId}/image} is called for a recipe that has no image
 * uploaded ({@code image_url IS NULL}) or whose backing file is missing from the filesystem
 * (orphan-tolerant — a rollback-orphaned file isn't pointed at by any row, so this case is mainly a
 * safety check for restore-from-backup scenarios where the row was restored without the file).
 * Mapped to HTTP 404 by {@code RecipeExceptionHandler}.
 *
 * <p>Introduced in recipe-02a alongside the image serve endpoint.
 */
public class RecipeImageNotFoundException extends RecipeException {

  private final UUID recipeId;

  public RecipeImageNotFoundException(UUID recipeId) {
    super("No image for recipe: " + recipeId);
    this.recipeId = recipeId;
  }

  public UUID recipeId() {
    return recipeId;
  }
}
