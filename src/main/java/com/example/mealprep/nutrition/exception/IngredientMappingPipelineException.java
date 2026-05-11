package com.example.mealprep.nutrition.exception;

/**
 * Unrecoverable pipeline failure (e.g. both USDA and OFF return malformed JSON repeatedly). Mapped
 * to HTTP 422 by {@code NutritionExceptionHandler}. Not raised on the happy paths in 01d; the class
 * ships so it's available for nutrition-01l's snack-log integration.
 */
public class IngredientMappingPipelineException extends NutritionException {

  public IngredientMappingPipelineException(String message) {
    super(message);
  }

  public IngredientMappingPipelineException(String message, Throwable cause) {
    super(message, cause);
  }
}
