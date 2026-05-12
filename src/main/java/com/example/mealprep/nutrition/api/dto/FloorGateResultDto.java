package com.example.mealprep.nutrition.api.dto;

import java.util.List;

/**
 * Verdict from {@code NutritionFloorGateService#evaluate}. {@code passed=false} is NOT an error —
 * the planner consumes this bit as a multiplicative kill-switch on its candidate scoring. The REST
 * seam returns HTTP 200 regardless of {@code passed}.
 *
 * <p>{@code violations} lists one entry per {@code (date, macroOrMicroKey)} breach; an empty list
 * implies {@code passed=true}.
 */
public record FloorGateResultDto(
    boolean passed, List<FloorViolationDto> violations, String summary) {}
