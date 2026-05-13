package com.example.mealprep.planner.exception;

import java.util.UUID;

/**
 * Thrown by slot-state lookups when the slot id doesn't exist. Defined in 01a so the listener /
 * controller tickets (01j / 01k) don't add it later; not thrown by any 01a code path. Mapped to
 * HTTP 404 by {@code PlannerExceptionHandler}.
 */
public class MealSlotNotFoundException extends PlannerException {

  private final UUID slotId;

  public MealSlotNotFoundException(UUID slotId) {
    super("Meal slot not found: " + slotId);
    this.slotId = slotId;
  }

  public UUID slotId() {
    return slotId;
  }
}
