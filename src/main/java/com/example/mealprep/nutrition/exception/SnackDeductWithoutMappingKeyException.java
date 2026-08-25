package com.example.mealprep.nutrition.exception;

/**
 * Thrown when {@code logSnack} is called with {@code deductFromPantry = true} but no {@code
 * ingredientMappingKey} — the pantry match runs on that key, so a deduction without one cannot do
 * anything. Mapped to HTTP 400 by {@code NutritionExceptionHandler}.
 */
public class SnackDeductWithoutMappingKeyException extends NutritionException {

  public SnackDeductWithoutMappingKeyException() {
    super("deductFromPantry requires an ingredientMappingKey to match a pantry item against.");
  }
}
