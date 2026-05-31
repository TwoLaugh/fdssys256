package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.planner.api.dto.FeasibilityCheckResultDto;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;

/**
 * Pre-A constraint feasibility check (planner-6, LLD §ConstraintFeasibilityCheck lines 628-645).
 * Catches over-restrictive constraint sets <em>before</em> the beam search runs and surfaces ranked
 * resolutions to the user rather than silently degrading to a sparse quality-warning plan.
 *
 * <p>Reached two ways: the controller exposes it via {@code GET /api/v1/plans/feasibility} (so the
 * UI can render the resolution dialog before triggering generation), and the composer MAY call it
 * inside {@code generatePlan} to flag {@code qualityWarning} early.
 */
public interface ConstraintFeasibilityCheck {

  /**
   * Run the feasibility passes over the already-built composition context. Pure / read-only — no DB
   * writes, no AI calls.
   */
  FeasibilityCheckResultDto check(PlanCompositionContext context);
}
