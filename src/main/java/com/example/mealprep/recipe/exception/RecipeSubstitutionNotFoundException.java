package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when a substitution lookup misses, or the substitution belongs to a different recipe than
 * the one in the URL path. Mapped to HTTP 404 by {@code RecipeExceptionHandler}.
 */
public class RecipeSubstitutionNotFoundException extends RecipeException {

  private final UUID substitutionId;

  public RecipeSubstitutionNotFoundException(UUID substitutionId) {
    super("Recipe substitution not found: " + substitutionId);
    this.substitutionId = substitutionId;
  }

  public UUID substitutionId() {
    return substitutionId;
  }
}
