package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aggregate of one user-day's intake: planned vs actual-so-far totals across calories, the four
 * tracked macros, and any micros logged on the day. {@code caloriesRemaining} can be negative when
 * actuals exceed plan; same applies to each macro's {@code remainingG}. {@code microsActualSoFar}
 * is keyed by nutrient key (e.g. {@code "iron_mg"}).
 */
public record DailyAggregateDto(
    int caloriesPlanned,
    int caloriesActualSoFar,
    int caloriesRemaining,
    MacroAggregateDto protein,
    MacroAggregateDto carbs,
    MacroAggregateDto fat,
    MacroAggregateDto fibre,
    Map<String, BigDecimal> microsActualSoFar) {}
