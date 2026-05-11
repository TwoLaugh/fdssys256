package com.example.mealprep.recipe.exception;

/**
 * Thrown by branch-creation when the requested branch {@code name} already exists for the recipe.
 * Pre-checked before the INSERT so the caller sees a clean 409 {@code recipe-branch-name-conflict}
 * instead of a {@code DataIntegrityViolationException}.
 */
public class RecipeBranchNameConflictException extends RecipeException {

  private final String name;

  public RecipeBranchNameConflictException(String name) {
    super("Recipe branch name already exists: " + name);
    this.name = name;
  }

  public String name() {
    return name;
  }
}
