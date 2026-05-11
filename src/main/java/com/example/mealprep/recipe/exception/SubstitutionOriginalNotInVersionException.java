package com.example.mealprep.recipe.exception;

/**
 * Thrown when {@code createSubstitution} is called with an {@code original.ingredientMappingKey}
 * that doesn't appear in the version's ingredient list. Mapped to HTTP 422 by {@code
 * RecipeExceptionHandler}.
 */
public class SubstitutionOriginalNotInVersionException extends RecipeException {

  private final String mappingKey;

  public SubstitutionOriginalNotInVersionException(String mappingKey) {
    super("Original ingredient not in version: " + mappingKey);
    this.mappingKey = mappingKey;
  }

  public String mappingKey() {
    return mappingKey;
  }
}
