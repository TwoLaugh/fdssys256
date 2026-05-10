package com.example.mealprep.nutrition.exception;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Thrown when a snack-delete targets a snack id not present on the addressed intake day. Also
 * thrown for cross-user / cross-date access attempts. Mapped to HTTP 404 by {@code
 * NutritionExceptionHandler}.
 */
public class IntakeSnackNotFoundException extends NutritionException {

  private final UUID userId;
  private final LocalDate onDate;
  private final UUID snackId;

  public IntakeSnackNotFoundException(UUID userId, LocalDate onDate, UUID snackId) {
    super("No intake snack " + snackId + " for user " + userId + " on " + onDate);
    this.userId = userId;
    this.onDate = onDate;
    this.snackId = snackId;
  }

  public UUID userId() {
    return userId;
  }

  public LocalDate onDate() {
    return onDate;
  }

  public UUID snackId() {
    return snackId;
  }
}
