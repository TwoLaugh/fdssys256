package com.example.mealprep.planner.exception;

import java.util.UUID;

/**
 * Thrown when a revert flow targets a plan that does not belong to the caller's household history.
 * Defined in 01b; actually thrown when the revert flow lands in 01j. Mapped to HTTP 422 by {@code
 * PlannerExceptionHandler}.
 */
public class RevertTargetNotInHistoryException extends PlannerException {

  private final UUID targetPlanId;

  public RevertTargetNotInHistoryException(UUID targetPlanId) {
    super("target plan " + targetPlanId + " is not in the caller's household history");
    this.targetPlanId = targetPlanId;
  }

  public UUID targetPlanId() {
    return targetPlanId;
  }
}
