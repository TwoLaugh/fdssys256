package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Calorie sub-shape of {@link TargetsDto} / {@link UpdateTargetsRequest}. The {@code enforcement}
 * mode is a free-form string at this stage (e.g. {@code "weekly_average"}, {@code "daily_band"});
 * the validator that constrains it to a known enum lands with a later sub-ticket.
 */
public record CalorieTargetDto(
    @Min(0) int dailyTarget,
    @Min(0) int toleranceUnder,
    @Min(0) int toleranceOver,
    @NotNull @Size(max = 24) String enforcement,
    @NotNull EnforcementDirection direction) {}
