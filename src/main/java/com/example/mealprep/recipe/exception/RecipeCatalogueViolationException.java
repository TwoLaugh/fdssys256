package com.example.mealprep.recipe.exception;

/**
 * Thrown when a write operation targets a {@code SYSTEM}-catalogue recipe that is read-only for the
 * caller. Specifically: 01c rejects manual edit of a SYSTEM recipe — the user must promote the
 * recipe to USER catalogue first ({@code promoteToUserCatalogue}, deferred to 01g). Mapped to HTTP
 * 422 by {@link com.example.mealprep.recipe.api.RecipeExceptionHandler}.
 */
public class RecipeCatalogueViolationException extends RecipeException {

  public RecipeCatalogueViolationException(String message) {
    super(message);
  }
}
