package com.example.mealprep.planner.exception;

import java.util.UUID;

/**
 * Thrown when {@code GET /api/v1/plans/{planId}} is called for a plan id that doesn't exist. Mapped
 * to HTTP 404 by {@code PlannerExceptionHandler}.
 */
public class PlanNotFoundException extends PlannerException {

  private final UUID planId;

  public PlanNotFoundException(UUID planId) {
    super("Plan not found: " + planId);
    this.planId = planId;
  }

  /**
   * 01c overload — the active/history/range endpoints have no plan-id to throw with; the message
   * already carries the (household, week) context. The {@link #planId()} accessor returns {@code
   * null} in that branch.
   */
  public PlanNotFoundException(String message) {
    super(message);
    this.planId = null;
  }

  public UUID planId() {
    return planId;
  }
}
