package com.example.mealprep.recipe.exception;

/**
 * Thrown by the adaptation-pipeline {@code RecipeWriteApi.saveAdaptedVersion} race-check when the
 * caller's expected parent version number / version id does NOT match the recipe's current head
 * (i.e. another writer landed a new version while the pipeline was preparing this one). Maps to 409
 * {@code recipe-version-conflict}. Per LLD line 667 + line 786 — the pipeline is responsible for
 * rebasing up to 3 times before surfacing the conflict to the caller.
 */
public class RecipeVersionConflictException extends RecipeException {

  public RecipeVersionConflictException(String message) {
    super(message);
  }
}
