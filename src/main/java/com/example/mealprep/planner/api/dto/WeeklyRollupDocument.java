package com.example.mealprep.planner.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated weekly figures for a plan. JSON-only carrier inside {@link RollupSummaryDocument}. 01a
 * is opaque about the contents — populated by planner-01f.
 */
public record WeeklyRollupDocument(
    int kcalTotal,
    BigDecimal proteinAvgG,
    BigDecimal fatAvgG,
    BigDecimal carbsAvgG,
    BigDecimal costEstimateGbp,
    BigDecimal costConfidence,
    int staleIngredientCount,
    BigDecimal varietyIndex,
    int batchCookSessions,
    List<String> constraintViolations) {}
