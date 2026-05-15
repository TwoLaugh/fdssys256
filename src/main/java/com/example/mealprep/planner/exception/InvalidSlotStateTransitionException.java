package com.example.mealprep.planner.exception;

import com.example.mealprep.planner.domain.entity.SlotState;

/**
 * Thrown by {@code PlanStateMachine.assertSlotTransitionAllowed} when a requested {@link SlotState}
 * transition is not in the allowed-transition matrix (LLD §Flow 5). Mapped to HTTP 409 by {@code
 * PlannerExceptionHandler}.
 */
public class InvalidSlotStateTransitionException extends PlannerException {

  private final SlotState current;
  private final SlotState next;

  public InvalidSlotStateTransitionException(SlotState current, SlotState next) {
    super("slot transition not allowed: " + current + " -> " + next);
    this.current = current;
    this.next = next;
  }

  public SlotState current() {
    return current;
  }

  public SlotState next() {
    return next;
  }
}
