package com.example.mealprep.nutrition.exception;

import java.util.UUID;

/**
 * Thrown by the manual-recalc endpoint when {@code RecipeQueryService} cannot find the recipe
 * version referenced in the path. Distinct from the recipe module's own {@code
 * RecipeVersionNotFoundException} so the failure carries a clear cross-module-trace message; the
 * {@code NutritionExceptionHandler} maps it to HTTP 404 with type slug {@code
 * recipe-version-lookup-failed}.
 */
public class RecipeVersionLookupFailedException extends NutritionException {

  private final UUID recipeId;
  private final UUID versionId;

  public RecipeVersionLookupFailedException(UUID recipeId, UUID versionId) {
    super("Recipe version not found for recalc: recipeId=" + recipeId + " versionId=" + versionId);
    this.recipeId = recipeId;
    this.versionId = versionId;
  }

  public RecipeVersionLookupFailedException(UUID recipeId, UUID versionId, Throwable cause) {
    super(
        "Recipe version not found for recalc: recipeId=" + recipeId + " versionId=" + versionId,
        cause);
    this.recipeId = recipeId;
    this.versionId = versionId;
  }

  public UUID recipeId() {
    return recipeId;
  }

  public UUID versionId() {
    return versionId;
  }
}
