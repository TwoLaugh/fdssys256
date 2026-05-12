package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * A candidate plan's per-day rollup envelope: a date range plus one {@link CandidateDailyRollupDto}
 * per day in that range.
 *
 * <p>Consumed by {@code NutritionFloorGateService#evaluate}. The service runs the floor-gate check
 * for each day in {@code perDay}, returning a {@link FloorGateResultDto} that aggregates the
 * verdict across the whole range.
 */
public record CandidatePlanRollupDto(
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @NotNull @Size(min = 1) @Valid List<CandidateDailyRollupDto> perDay) {}
