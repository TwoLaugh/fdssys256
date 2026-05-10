package com.example.mealprep.nutrition.exception;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Thrown when a slot-targeted intake write hits an existing day but no slot row for the requested
 * meal slot. Mapped to HTTP 404 by {@code NutritionExceptionHandler}.
 */
public class IntakeSlotNotFoundException extends NutritionException {

  private final UUID userId;
  private final LocalDate onDate;
  private final MealSlot mealSlot;

  public IntakeSlotNotFoundException(UUID userId, LocalDate onDate, MealSlot mealSlot) {
    super("No intake slot found for user " + userId + " on " + onDate + " slot=" + mealSlot);
    this.userId = userId;
    this.onDate = onDate;
    this.mealSlot = mealSlot;
  }

  public UUID userId() {
    return userId;
  }

  public LocalDate onDate() {
    return onDate;
  }

  public MealSlot mealSlot() {
    return mealSlot;
  }
}
