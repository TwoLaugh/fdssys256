package com.example.mealprep.nutrition.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Weekly intake rollup: Monday-anchored 7-day window with a per-day breakdown plus the weekly
 * total. {@code floorViolations} carries one structured {@link FloorViolationDto} per breached
 * floor: daily-enforcement floors yield one dated entry per violating tracked day, while
 * weekly-average floors yield a single {@code date == null} entry comparing the weekly total
 * against the 7-day-summed floor. Per-day floor checks for candidate plans live in {@code
 * NutritionFloorGateService} (01g).
 */
public record WeeklyAggregateDto(
    LocalDate weekStart,
    LocalDate weekEnd,
    List<DailyAggregateDto> perDay,
    DailyAggregateDto weeklyTotal,
    List<FloorViolationDto> floorViolations) {}
