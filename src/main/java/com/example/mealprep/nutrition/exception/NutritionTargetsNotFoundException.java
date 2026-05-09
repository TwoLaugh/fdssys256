package com.example.mealprep.nutrition.exception;

import java.util.UUID;

/**
 * Thrown when {@code GET} or {@code PUT /api/v1/nutrition/targets} is called for a user with no
 * {@code nutrition_targets} row. Mapped to HTTP 404 by {@code NutritionExceptionHandler}.
 */
public class NutritionTargetsNotFoundException extends NutritionException {

  private final UUID userId;

  public NutritionTargetsNotFoundException(UUID userId) {
    super("No nutrition targets found for user " + userId);
    this.userId = userId;
  }

  public UUID userId() {
    return userId;
  }
}
