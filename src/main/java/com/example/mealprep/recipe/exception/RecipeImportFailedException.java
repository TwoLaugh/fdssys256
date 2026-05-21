package com.example.mealprep.recipe.exception;

/**
 * Thrown by {@code RecipeWriteApi.saveImportedRecipe} when a discovery-fed import cannot be
 * persisted (validation failure, downstream persistence error, etc.). Distinct from {@link
 * RecipeImportFailureException} which is for the URL-import (HTTP fetch / HTML parse) leg.
 *
 * <p>The {@code DiscoveryJobRunner} catches this and writes a {@code discovery_scrape_log} row with
 * {@code status=EXTRACTION_FAILED}; the exception is also mapped by {@code RecipeExceptionHandler}
 * for the diagnostic path (500) per ticket discovery-01g §17.
 */
public class RecipeImportFailedException extends RecipeException {

  public RecipeImportFailedException(String message) {
    super(message);
  }

  public RecipeImportFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
