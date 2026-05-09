package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.Goal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Full-replacement update for a user's nutrition targets. {@code expectedVersion} is matched
 * against the row's current {@code @Version}; mismatch → 409. Children are replaced wholesale
 * (cascade + orphanRemoval handle delete + insert).
 *
 * <p>{@code userOverriddenDirections} is server-managed (01b's goal-defaults logic mutates it) and
 * is intentionally absent from the request shape; sending it would have no effect today.
 *
 * <p>The four cross-field validators ({@code @ValidEatingWindow},
 * {@code @ValidPerMealDistribution}, {@code @ValidActivityProfile},
 * {@code @ValidDirectiveInstruction}) land with their respective sub-tickets — basic Jakarta
 * annotations only in 01a.
 */
public record UpdateTargetsRequest(
    @NotNull Goal goal,
    @NotNull @Valid CalorieTargetDto calories,
    @NotNull @Valid MacroTargetDto protein,
    @NotNull @Valid MacroTargetDto carbs,
    @NotNull @Valid MacroTargetDto fat,
    @NotNull @Valid MacroTargetDto fibre,
    @NotNull @Valid MacroTargetDto satFat,
    @Size(max = 512) String notes,
    @NotNull @Valid @Size(max = 4) List<PerMealDistributionDto> perMealDistribution,
    @NotNull @Valid @Size(max = 30) List<MicroTargetDto> microTargets,
    @Valid EatingWindowDto eatingWindow,
    @NotNull @Valid @Size(max = 4) List<ActivityAdjustmentDto> activityAdjustments,
    @Min(0) long expectedVersion) {}
