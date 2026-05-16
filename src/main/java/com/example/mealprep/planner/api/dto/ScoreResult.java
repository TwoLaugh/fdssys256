package com.example.mealprep.planner.api.dto;

import java.math.BigDecimal;

/**
 * Output of one {@code ScoringEngine.score(...)} call — the {@code composite} score (sum of
 * weighted sub-scores, before gates) plus the full {@link ScoreBreakdownDocument} that's persisted
 * on {@code Plan#scoreBreakdown}. The composite is what {@link BeamPruner} sorts on; the breakdown
 * is what the UI surfaces to users.
 *
 * <p>The interface declaration lives in 01d so {@code BeamSearchEngine} can compile and inject the
 * scoring SPI; the real composite/sub-score maths lands in 01e.
 */
public record ScoreResult(BigDecimal composite, ScoreBreakdownDocument breakdown) {}
