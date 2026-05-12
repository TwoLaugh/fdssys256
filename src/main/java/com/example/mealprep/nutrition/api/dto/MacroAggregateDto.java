package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;

/**
 * Per-macro aggregate breakdown used inside {@link DailyAggregateDto}. {@code plannedG} and {@code
 * actualSoFarG} are non-negative; {@code remainingG} can be negative when actuals exceed plan
 * (overeating).
 */
public record MacroAggregateDto(
    BigDecimal plannedG, BigDecimal actualSoFarG, BigDecimal remainingG) {}
