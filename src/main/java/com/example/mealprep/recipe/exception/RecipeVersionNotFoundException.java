package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when a recipe-version id is referenced (e.g., on the diff endpoint) and no row exists, or
 * the row exists but belongs to a different recipe than the one in the path. Mapped to HTTP 404 by
 * {@link com.example.mealprep.recipe.api.RecipeExceptionHandler}.
 */
public class RecipeVersionNotFoundException extends RecipeException {

  private final UUID versionId;

  public RecipeVersionNotFoundException(UUID versionId) {
    super("Recipe version not found: " + versionId);
    this.versionId = versionId;
  }

  public UUID versionId() {
    return versionId;
  }
}
