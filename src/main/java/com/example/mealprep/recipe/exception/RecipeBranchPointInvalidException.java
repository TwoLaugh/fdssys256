package com.example.mealprep.recipe.exception;

import java.util.UUID;

/**
 * Thrown by branch-creation when {@code branchPointVersionId} doesn't resolve to a version
 * belonging to the parent recipe (foreign version id, soft-deleted version, etc.). Mapped to HTTP
 * 422 {@code recipe-branch-point-invalid}.
 */
public class RecipeBranchPointInvalidException extends RecipeException {

  private final UUID branchPointVersionId;

  public RecipeBranchPointInvalidException(UUID branchPointVersionId) {
    super("Invalid branch-point version: " + branchPointVersionId);
    this.branchPointVersionId = branchPointVersionId;
  }

  public UUID branchPointVersionId() {
    return branchPointVersionId;
  }
}
