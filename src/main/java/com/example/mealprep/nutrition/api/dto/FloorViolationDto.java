package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One floor breach: the macro/micro key that fell below its floor, the floor target, and the actual
 * rolled-up value.
 *
 * <p>Produced by {@code NutritionFloorGateService#evaluate} (always dated — identifies the
 * candidate-plan day) and by {@code WeeklyAggregateDto#floorViolations}, where {@code date} is set
 * for daily-enforcement floors and {@code null} for weekly-average floors (the breach belongs to
 * the week as a whole, with {@code floor} being the 7-day-summed floor).
 */
public record FloorViolationDto(
    LocalDate date, String macroOrMicro, BigDecimal floor, BigDecimal actual) {}
