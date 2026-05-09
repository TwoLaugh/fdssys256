package com.example.mealprep.recipe.exception;

/**
 * Module-root exception for the recipe module. Per-failure subclasses extend this so the {@code
 * RecipeExceptionHandler} (or {@code GlobalExceptionHandler}) can map either the specific subtype
 * or the root if a future subtype is added without a handler.
 */
public class RecipeException extends RuntimeException {

  public RecipeException(String message) {
    super(message);
  }

  public RecipeException(String message, Throwable cause) {
    super(message, cause);
  }
}
