package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import java.math.BigDecimal;

/**
 * Per-candidate scoring seam used by the Stage-A beam search during pruning. Production wires {@link
 * IncrementalScoringEngine} — it carries opaque running accumulators on each partial so appending
 * one slot is O(1)-ish and the composite is byte-identical to the exact {@link
 * ScoringEngine#score}{@code .composite()}. The seam keeps the beam testable with a deterministic
 * scorer (the beam-mechanics unit test) without dragging in the seven sub-score beans.
 *
 * <p>The accumulator state is opaque to the beam ({@code Object}); the beam only threads it from
 * {@link #emptyState} through {@link #append} into {@link #composite}, never inspecting it.
 *
 * <p>Pruning ONLY — {@code BeamSearchEngineImpl.finalise()} re-scores the returned top-N with the
 * exact {@link ScoringEngine}, so the persisted breakdown is always the exact engine's output.
 */
public interface BeamCandidateScorer {

  /** Seed the accumulator state for an empty plan in {@code ctx}. */
  Object emptyState(PlanCompositionContext ctx);

  /** Derive the child accumulator state from appending {@code a} to {@code parentState}. */
  Object append(Object parentState, SlotAssignment a, PlanCompositionContext ctx);

  /**
   * Finalise the composite for {@code state} — byte-identical to the exact {@link
   * ScoringEngine#score}{@code (planView, ctx).composite()} for the same plan. {@code planView} is
   * the candidate materialised for the whole-plan cost / provisions sub-scores.
   */
  BigDecimal composite(Object state, CandidatePlan planView, PlanCompositionContext ctx);
}
