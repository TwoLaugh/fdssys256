package com.example.mealprep.planner.api.dto;

import java.util.List;

/**
 * JSONB carrier on {@link com.example.mealprep.planner.domain.entity.Plan#getRollupSummary()}.
 * Daily entries + the weekly aggregate + the plan's projected nutrition coverage vs the primary
 * user's targets. Populated by planner-01f ({@code RollupBuilder}); 01a only exercises Jackson
 * round-trip via the {@code @Type(JsonBinaryType.class)} mapping.
 *
 * <p>{@code nutritionCoverage} is additive (nutrition-driven planning): {@code null} on plans
 * generated before it shipped (Jackson round-trips the missing JSON field to {@code null}) and when
 * the user has no targets configured.
 */
public record RollupSummaryDocument(
    List<DailyRollupDocument> daily,
    WeeklyRollupDocument weekly,
    NutritionCoverageDocument nutritionCoverage) {

  /** Compatibility constructor (pre nutrition-coverage call sites) — defaults coverage to null. */
  public RollupSummaryDocument(List<DailyRollupDocument> daily, WeeklyRollupDocument weekly) {
    this(daily, weekly, null);
  }
}
