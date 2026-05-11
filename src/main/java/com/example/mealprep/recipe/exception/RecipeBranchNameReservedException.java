package com.example.mealprep.recipe.exception;

/**
 * Thrown by branch-creation when the caller requests a reserved branch name (today: only {@code
 * "main"} is reserved — every recipe's auto-generated main branch holds the slot). Mapped to HTTP
 * 422 {@code recipe-branch-name-reserved}.
 */
public class RecipeBranchNameReservedException extends RecipeException {

  private final String name;

  public RecipeBranchNameReservedException(String name) {
    super("Recipe branch name is reserved: " + name);
    this.name = name;
  }

  public String name() {
    return name;
  }
}
