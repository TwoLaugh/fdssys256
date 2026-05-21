package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when the calling user attempts to mutate a recipe they do not own (e.g. uploading an image
 * to someone else's recipe, or to a SYSTEM-catalogue recipe without the {@code RECIPE_ADMIN} role).
 * Mapped to HTTP 403 by {@code RecipeExceptionHandler}.
 *
 * <p>Introduced in recipe-02a alongside the image upload endpoint.
 */
public class RecipeAccessDeniedException extends RecipeException {

  private final UUID recipeId;
  private final UUID actorUserId;

  public RecipeAccessDeniedException(UUID recipeId, UUID actorUserId) {
    super("User " + actorUserId + " is not authorised to mutate recipe " + recipeId + ".");
    this.recipeId = recipeId;
    this.actorUserId = actorUserId;
  }

  public RecipeAccessDeniedException(UUID recipeId, UUID actorUserId, String message) {
    super(message);
    this.recipeId = recipeId;
    this.actorUserId = actorUserId;
  }

  public UUID recipeId() {
    return recipeId;
  }

  public UUID actorUserId() {
    return actorUserId;
  }
}
