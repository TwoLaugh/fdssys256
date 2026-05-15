package com.example.mealprep.planner.exception;

import com.example.mealprep.planner.domain.entity.PlanStatus;

/**
 * Thrown by {@code PlanStateMachine.assertPlanTransitionAllowed} when a requested {@link
 * PlanStatus} transition is not in the allowed-transition matrix (LLD §Entities + §Flow 3). Mapped
 * to HTTP 409 by {@code PlannerExceptionHandler}.
 */
public class InvalidPlanStateTransitionException extends PlannerException {

  private final PlanStatus current;
  private final PlanStatus next;

  public InvalidPlanStateTransitionException(PlanStatus current, PlanStatus next) {
    super("plan transition not allowed: " + current + " -> " + next);
    this.current = current;
    this.next = next;
  }

  public PlanStatus current() {
    return current;
  }

  public PlanStatus next() {
    return next;
  }
}
