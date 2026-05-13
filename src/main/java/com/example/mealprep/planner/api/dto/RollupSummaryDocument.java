package com.example.mealprep.planner.api.dto;

import java.util.List;

/**
 * JSONB carrier on {@link com.example.mealprep.planner.domain.entity.Plan#getRollupSummary()}.
 * Daily entries + the weekly aggregate. Populated by planner-01f ({@code RollupBuilder}); 01a only
 * exercises Jackson round-trip via the {@code @Type(JsonBinaryType.class)} mapping.
 */
public record RollupSummaryDocument(List<DailyRollupDocument> daily, WeeklyRollupDocument weekly) {}
