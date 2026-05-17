package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import java.math.BigDecimal;

/**
 * One of the 7 weighted contributors to the composite score (preference, nutrition, cost, variety,
 * time, batch, provisions). 01d ships the interface declaration only; the 7 implementations land in
 * planner-01e where the {@code ScoringEngine} real impl collects them via constructor injection.
 *
 * <p>Package-private — sub-scores are an internal contract; the only outside surface is the
 * composite returned by {@code ScoringEngine}.
 */
interface SubScoreCalculator {

  /** Matches the corresponding key in {@code PlannerProperties} weight scheme. */
  String name();

  /** Returns a normalised contribution in {@code [0, 1]}. */
  BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx);
}
