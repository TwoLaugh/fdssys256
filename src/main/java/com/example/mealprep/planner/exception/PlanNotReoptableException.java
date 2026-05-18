package com.example.mealprep.planner.exception;

import com.example.mealprep.planner.domain.entity.PlanStatus;
import java.util.UUID;

/**
 * Thrown by {@code MidWeekReoptCoordinator.requestReopt(...)} when the target plan is in a terminal
 * status that cannot be re-optimised (planner-01i invariant #3). A plan is re-optable only while
 * {@link PlanStatus#GENERATED} or {@link PlanStatus#ACTIVE}; {@code SUPERSEDED / REJECTED /
 * ABANDONED / COMPLETED / DRAFT} are rejected. Mapped to HTTP 400 by {@code
 * PlannerExceptionHandler}.
 *
 * <p>(The 01i ticket names the re-optable set {@code ACCEPTED / GENERATED / IN_PROGRESS}; this
 * codebase's {@code PlanStatus} enum has no {@code ACCEPTED} / {@code IN_PROGRESS} values — the
 * equivalent live state is {@code ACTIVE}. The mapping is documented here so 01j/01k callers see
 * the resolved contract.)
 */
public class PlanNotReoptableException extends PlannerException {

  private final UUID planId;
  private final PlanStatus status;

  public PlanNotReoptableException(UUID planId, PlanStatus status) {
    super("Plan " + planId + " is not re-optable in status " + status);
    this.planId = planId;
    this.status = status;
  }

  public UUID planId() {
    return planId;
  }

  public PlanStatus status() {
    return status;
  }
}
