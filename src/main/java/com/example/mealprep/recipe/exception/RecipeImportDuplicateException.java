package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when a create / import would persist a recipe that duplicates one already in the caller's
 * library. Per the HLD §Recipe deduplication: a normalised ingredient-set hash collision above the
 * threshold (default 80% ingredient overlap + method length within ±20%) surfaces a "merge / import
 * as variant / import anyway" dialog instead of silently creating a duplicate.
 *
 * <p>Mapped to HTTP 422 by {@code RecipeExceptionHandler} with {@code type =
 * .../recipe-import-duplicate} and a {@code candidateRecipeId} extension (plus the {@code
 * ingredientOverlap} score) on the ProblemDetail so the UI can offer the dialog. The LLD §Error
 * responses table already lists {@code RecipeImportDuplicateException → 422}.
 */
public class RecipeImportDuplicateException extends RecipeException {

  private final UUID candidateRecipeId;
  private final double ingredientOverlap;

  public RecipeImportDuplicateException(UUID candidateRecipeId, double ingredientOverlap) {
    super(
        "Recipe duplicates an existing library recipe "
            + candidateRecipeId
            + " (ingredient overlap "
            + Math.round(ingredientOverlap * 100)
            + "%)");
    this.candidateRecipeId = candidateRecipeId;
    this.ingredientOverlap = ingredientOverlap;
  }

  public UUID candidateRecipeId() {
    return candidateRecipeId;
  }

  public double ingredientOverlap() {
    return ingredientOverlap;
  }
}
