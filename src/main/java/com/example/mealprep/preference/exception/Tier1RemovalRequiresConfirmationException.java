package com.example.mealprep.preference.exception;

import com.example.mealprep.preference.api.dto.RemovedTier1Constraint;
import java.util.List;

/**
 * Thrown when an {@code UpdateHardConstraintsRequest} would remove one or more safety-critical
 * Tier-1 hard constraints (allergy, medical diet, severe intolerance, or a dietary-identity base
 * narrowing) <em>without</em> the explicit {@code confirmTier1Removals} confirmation flag — the
 * GAP-04 safety interstitial.
 *
 * <p>Mapped by {@code PreferenceExceptionHandler} to HTTP 409 Conflict (the request conflicts with
 * the safety policy until confirmed) with a {@code ProblemDetail} carrying a machine-readable
 * {@code reason = TIER1_REMOVAL_REQUIRES_CONFIRMATION} and the {@code removedConstraints} the UI
 * must name in the confirmation prompt. The client re-submits the same payload with {@code
 * confirmTier1Removals = true} to proceed.
 *
 * <p>The deterministic {@code HardConstraintFilterService} is the system's only allergy guardrail,
 * so a silent one-step removal of one of these is a real safety hole; this gate makes the removal a
 * deliberate two-step action.
 */
public class Tier1RemovalRequiresConfirmationException extends PreferenceException {

  /** Machine-readable slug surfaced as the {@code reason} extension on the ProblemDetail. */
  public static final String REASON = "TIER1_REMOVAL_REQUIRES_CONFIRMATION";

  private final List<RemovedTier1Constraint> removedConstraints;

  public Tier1RemovalRequiresConfirmationException(
      List<RemovedTier1Constraint> removedConstraints) {
    super(buildMessage(removedConstraints));
    this.removedConstraints = List.copyOf(removedConstraints);
  }

  public List<RemovedTier1Constraint> removedConstraints() {
    return removedConstraints;
  }

  private static String buildMessage(List<RemovedTier1Constraint> removed) {
    StringBuilder sb =
        new StringBuilder(
            "Removing "
                + removed.size()
                + " safety-critical Tier-1 hard constraint(s) requires explicit confirmation"
                + " (set confirmTier1Removals=true to proceed): ");
    for (int i = 0; i < removed.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      RemovedTier1Constraint c = removed.get(i);
      sb.append(c.category()).append(':').append(c.value());
    }
    return sb.toString();
  }
}
