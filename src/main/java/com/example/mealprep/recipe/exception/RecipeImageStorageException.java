package com.example.mealprep.recipe.exception;

/**
 * Thrown when a filesystem-level operation in {@code LocalFilesystemImageStore} fails (disk full,
 * permission denied at runtime, etc.). The bean-init permission check at startup catches most of
 * these; this exception covers the residual mid-runtime failure modes. Mapped to HTTP 500 by {@code
 * RecipeExceptionHandler}.
 *
 * <p>Introduced in recipe-02a alongside the image upload endpoint.
 */
public class RecipeImageStorageException extends RecipeException {

  public RecipeImageStorageException(String message) {
    super(message);
  }

  public RecipeImageStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
