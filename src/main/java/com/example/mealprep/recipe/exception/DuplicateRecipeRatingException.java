package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when a user POSTs a rating for a version they have already rated. The contract is
 * explicit: a user has at most one rating per version, and updates go through PUT. Mapped to HTTP
 * 409 by {@link com.example.mealprep.recipe.api.RecipeExceptionHandler}.
 */
public class DuplicateRecipeRatingException extends RecipeException {

  private final UUID versionId;

  public DuplicateRecipeRatingException(UUID versionId) {
    super("Conflict: rating already exists for this version, use PUT");
    this.versionId = versionId;
  }

  public UUID versionId() {
    return versionId;
  }
}
