package com.example.mealprep.nutrition.exception;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Thrown when a write or read targets an intake day with no row in {@code nutrition_intake_day}.
 * Mapped to HTTP 404 by {@code NutritionExceptionHandler}.
 */
public class IntakeDayNotFoundException extends NutritionException {

  private final UUID userId;
  private final LocalDate onDate;

  public IntakeDayNotFoundException(UUID userId, LocalDate onDate) {
    super("No intake day found for user " + userId + " on " + onDate);
    this.userId = userId;
    this.onDate = onDate;
  }

  public UUID userId() {
    return userId;
  }

  public LocalDate onDate() {
    return onDate;
  }
}
