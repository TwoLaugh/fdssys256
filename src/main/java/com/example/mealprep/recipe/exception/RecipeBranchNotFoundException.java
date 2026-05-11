package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown when a branch lookup misses (unknown id, or branch belongs to a different recipe — we map
 * cross-recipe lookups to "not found" rather than 422 so we don't leak the existence of other
 * users' branches). Mapped to HTTP 404 by {@code RecipeExceptionHandler}.
 */
public class RecipeBranchNotFoundException extends RecipeException {

  private final UUID branchId;

  public RecipeBranchNotFoundException(UUID branchId) {
    super("Recipe branch not found: " + branchId);
    this.branchId = branchId;
  }

  public UUID branchId() {
    return branchId;
  }
}
