package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One hard-floor breach as reported by {@code NutritionFloorGateService#evaluate}: identifies the
 * day, the macro/micro key that fell below its floor, the floor target, and the actual rolled-up
 * value on that day.
 */
public record FloorViolationDto(
    LocalDate date, String macroOrMicro, BigDecimal floor, BigDecimal actual) {}
