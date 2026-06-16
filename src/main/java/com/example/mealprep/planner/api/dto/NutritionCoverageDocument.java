package com.example.mealprep.planner.api.dto;

import java.util.List;

/**
 * The generated plan's projected nutrition vs the primary user's targets — calories + macros and
 * each configured micronutrient — so the UI can show "this plan delivers N of M targets" and which
 * fall short. Projected values are the plan's daily averages for the primary eater (one serving per
 * slot, per-person). A JSON-only carrier inside {@link RollupSummaryDocument}; {@code null} on the
 * rollup when the user has no nutrition targets configured.
 */
public record NutritionCoverageDocument(
    List<NutritionTargetCoverageDocument> macros,
    List<NutritionTargetCoverageDocument> micros,
    int macrosMet,
    int macrosTotal,
    int microsMet,
    int microsTotal) {}
