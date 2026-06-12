package com.example.mealprep.nutrition.exception;

import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Thrown when {@code POST .../slots/{mealSlot}/edit} targets a slot with no legal edit transition.
 * Edit is legal from {@code PENDING} and — as the repair path for a parse-failed override — from
 * {@code OVERRIDDEN} with {@code needsAiParse = true}. Any other decided state ({@code CONFIRMED} /
 * {@code EDITED} / {@code SKIPPED}, or {@code OVERRIDDEN} whose parse succeeded) is terminal for
 * edit. Mapped to HTTP 422 by {@code NutritionExceptionHandler}.
 */
public class IntakeSlotNotEditableException extends NutritionException {

  private final UUID userId;
  private final LocalDate onDate;
  private final MealSlot mealSlot;
  private final IntakeSlotStatus currentStatus;
  private final boolean needsAiParse;

  public IntakeSlotNotEditableException(
      UUID userId,
      LocalDate onDate,
      MealSlot mealSlot,
      IntakeSlotStatus currentStatus,
      boolean needsAiParse) {
    super(
        "Intake slot "
            + mealSlot
            + " on "
            + onDate
            + " is "
            + currentStatus
            + (currentStatus == IntakeSlotStatus.OVERRIDDEN && !needsAiParse
                ? " with a successful parse"
                : "")
            + " — edit is only legal from PENDING, or from OVERRIDDEN with needsAiParse=true"
            + " (repair of a parse-failed override).");
    this.userId = userId;
    this.onDate = onDate;
    this.mealSlot = mealSlot;
    this.currentStatus = currentStatus;
    this.needsAiParse = needsAiParse;
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

  public IntakeSlotStatus currentStatus() {
    return currentStatus;
  }

  public boolean needsAiParse() {
    return needsAiParse;
  }
}
