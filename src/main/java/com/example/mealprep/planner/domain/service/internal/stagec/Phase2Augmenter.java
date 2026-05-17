package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.planner.api.dto.AugmentationResult;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import java.util.UUID;

/**
 * Stage-C Phase 2. Given the chosen plan from Phase 1 (Stage-C invoker, planner-01g), invokes the
 * LLM to propose small augmentations + refine-directives, verifies every augmentation against the
 * hard-constraint filter, and returns the surviving set. Per lld/planner.md §{@code
 * Phase2Augmenter} (lines 868-919) and ticket planner-01h.
 *
 * <p>Public interface (module-internal package, not re-exported via {@code PlannerModule}); the
 * composer (planner-01j) is the only caller. Single impl: {@link Phase2AugmenterImpl}.
 */
public interface Phase2Augmenter {

  /**
   * @param chosenPlan the Phase-1-selected candidate (assignments + score)
   * @param chosenRollup the chosen plan's per-day macro rollup (from planner-01f via nutrition)
   * @param context the frozen plan-composition context (recipe pool, slots, constraints)
   * @param traceId decision-log correlation id
   * @return augmentations that survived the verifier, those discarded, and emitted directives
   *     (always empty in 01h — see {@link com.example.mealprep.planner.api.dto.RefineDirectiveDto})
   */
  AugmentationResult augment(
      CandidatePlan chosenPlan,
      CandidatePlanRollupDto chosenRollup,
      PlanCompositionContext context,
      UUID traceId);
}
