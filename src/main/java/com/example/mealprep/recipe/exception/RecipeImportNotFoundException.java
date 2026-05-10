package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when {@code GET /api/v1/recipes/{recipeId}/import-provenance} is called for a recipe that
 * exists but has no {@code recipe_imports} row (manually-created recipes from 01a never have one).
 * Mapped to HTTP 404 with {@code type = .../recipe-import-not-found} to disambiguate from {@link
 * RecipeNotFoundException} which fires when the recipe itself is missing.
 */
public class RecipeImportNotFoundException extends RecipeException {

  private final UUID recipeId;

  public RecipeImportNotFoundException(UUID recipeId) {
    super("Recipe import provenance not found for recipe: " + recipeId);
    this.recipeId = recipeId;
  }

  public UUID recipeId() {
    return recipeId;
  }
}
