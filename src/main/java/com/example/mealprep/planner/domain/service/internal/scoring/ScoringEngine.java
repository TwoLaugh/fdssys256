package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ScoreResult;

/**
 * SPI consumed by {@code BeamSearchEngine}. 01d ships the interface declaration plus a {@code
 * StubScoringEngine} (active only under the {@code test} profile) that returns
 * deterministic-by-recipe-id values so the search algorithm is testable end-to-end. The real
 * composite/sub-score implementation lands in planner-01e.
 */
public interface ScoringEngine {

  ScoreResult score(CandidatePlan plan, PlanCompositionContext context);
}
