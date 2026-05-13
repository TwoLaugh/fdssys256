package com.example.mealprep.planner.api.dto;

import java.math.BigDecimal;

/**
 * JSONB carrier on {@link com.example.mealprep.planner.domain.entity.Plan#getScoreBreakdown()}.
 * Per-sub-score breakdown + composite + gate flags. Populated by planner-01e ({@code
 * ScoringEngine}); 01a only exercises Jackson round-trip via the
 * {@code @Type(JsonBinaryType.class)} mapping. Public so Jackson can construct it via the canonical
 * constructor (a package-private record breaks JSONB round-trip per gotchas #11).
 */
public record ScoreBreakdownDocument(
    BigDecimal preference,
    BigDecimal nutrition,
    BigDecimal cost,
    BigDecimal variety,
    BigDecimal time,
    BigDecimal batch,
    BigDecimal provisions,
    BigDecimal composite,
    boolean nutritionFloorGatePassed,
    boolean varietyGatePassed,
    String weightSchemeVersion) {}
