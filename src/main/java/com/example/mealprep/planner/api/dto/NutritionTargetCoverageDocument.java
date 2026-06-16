package com.example.mealprep.planner.api.dto;

import java.math.BigDecimal;

/**
 * One nutrition target's projected coverage in a generated plan: the target value, the plan's
 * projected daily average for the primary user, the enforcement direction, and whether the average
 * satisfies it. A JSON-only carrier inside {@link NutritionCoverageDocument} on the plan rollup.
 *
 * <p>{@code unit} is a display hint ({@code kcal}/{@code g}/{@code mg}/{@code mcg}); for micros it is
 * derived from the {@code key} suffix. {@code direction} is {@code LOWER_FLOOR} / {@code
 * UPPER_LIMIT} / {@code BOTH_BOUNDED}.
 */
public record NutritionTargetCoverageDocument(
    String key,
    String unit,
    BigDecimal target,
    BigDecimal projectedDailyAvg,
    String direction,
    boolean met,
    // MET | SHORT | NO_DATA. NO_DATA = no recipe in the plan carried this nutrient, so intake is
    // UNKNOWN, not zero — projectedDailyAvg is null and the nutrient is excluded from the "short"
    // count (measurement honesty: never score an absent data source as a measured zero).
    String status) {}
