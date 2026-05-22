package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when a rating id is referenced and no row exists, or the row exists but belongs to another
 * user (the userId-scoped lookups map cross-user access to "not found" so we don't leak ids).
 * Mapped to HTTP 404 by {@link com.example.mealprep.recipe.api.RecipeExceptionHandler}.
 */
public class RecipeRatingNotFoundException extends RecipeException {

  private final UUID ratingId;

  public RecipeRatingNotFoundException(UUID ratingId) {
    super("Recipe rating not found: " + ratingId);
    this.ratingId = ratingId;
  }

  public UUID ratingId() {
    return ratingId;
  }
}
