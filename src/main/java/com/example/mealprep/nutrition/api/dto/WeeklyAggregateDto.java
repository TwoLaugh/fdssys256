package com.example.mealprep.nutrition.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Weekly intake rollup: Monday-anchored 7-day window with a per-day breakdown plus the weekly
 * total. {@code floorViolations} is a flat list of macro/micro keys whose weekly total fell below
 * the 7-day-summed hard floor; per-day floor checks live in {@code NutritionFloorGateService}
 * (01g).
 */
public record WeeklyAggregateDto(
    LocalDate weekStart,
    LocalDate weekEnd,
    List<DailyAggregateDto> perDay,
    DailyAggregateDto weeklyTotal,
    List<String> floorViolations) {}
