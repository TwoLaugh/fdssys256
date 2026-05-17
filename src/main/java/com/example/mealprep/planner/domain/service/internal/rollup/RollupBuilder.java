package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;

/**
 * Stage B: builds the per-candidate flat {@link RollupSummaryDocument} (daily + weekly) consumed by
 * Stage C's LLM prompt (planner-01g) and persisted on the {@code Plan} row (planner-01j).
 *
 * <p>Deterministic, no I/O, no DB — a pure function over an already-loaded {@link CandidatePlan}
 * and {@link PlanCompositionContext}. Same input → byte-identical output.
 */
public interface RollupBuilder {

  RollupSummaryDocument build(CandidatePlan plan, PlanCompositionContext context);
}
