package com.example.mealprep.planner.exception;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Thrown when a plan generation attempt loses the single-flight advisory lock for {@code
 * (householdId, weekStartDate)} per LLD §Concurrency. Defined in 01b; actually thrown when the lock
 * acquisition lands in 01j. Mapped to HTTP 409 by {@code PlannerExceptionHandler}.
 */
public class ConcurrentGenerationInProgressException extends PlannerException {

  private final UUID householdId;
  private final LocalDate weekStartDate;

  public ConcurrentGenerationInProgressException(UUID householdId, LocalDate weekStartDate) {
    super(
        "plan generation already in progress for household "
            + householdId
            + " week "
            + weekStartDate);
    this.householdId = householdId;
    this.weekStartDate = weekStartDate;
  }

  public UUID householdId() {
    return householdId;
  }

  public LocalDate weekStartDate() {
    return weekStartDate;
  }
}
