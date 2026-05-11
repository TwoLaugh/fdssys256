package com.example.mealprep.nutrition.exception;

/**
 * Thrown when a {@code GET /lookup?term=…} cache + USDA + OFF lookup yields no match, or when a
 * correction PUT targets a {@code searchTerm} that does not exist in the cache. Mapped to HTTP 404
 * by {@code NutritionExceptionHandler}.
 */
public class IngredientMappingNotFoundException extends NutritionException {

  private final String searchTerm;

  public IngredientMappingNotFoundException(String searchTerm) {
    super("Ingredient mapping not found: " + searchTerm);
    this.searchTerm = searchTerm;
  }

  public String searchTerm() {
    return searchTerm;
  }
}
