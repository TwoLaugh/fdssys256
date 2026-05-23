package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.AdjustmentDirection;
import com.example.mealprep.nutrition.domain.entity.AdjustmentMagnitude;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Single-field, relative feedback-driven target adjustment (nutrition-01i). Carries the shape the
 * feedback classifier emits ({@code {target, direction, magnitude, absoluteValue, reason}}) and is
 * consumed in-process by {@code NutritionUpdateService.applyFeedbackAdjustment} — there is no REST
 * surface (mirrors the preference {@code applyDeltas} path).
 *
 * <p>{@code target} is a dotted path into the targets aggregate:
 *
 * <ul>
 *   <li>{@code calorie_target} — the daily calorie target
 *   <li>{@code protein_target_g} / {@code carbs_target_g} / {@code fat_target_g} / {@code
 *       fibre_target_g} / {@code sat_fat_target_g} — a macro target
 *   <li>{@code micro.<nutrient_key>} — an existing micronutrient target row (no-op if absent)
 *   <li>{@code per_meal.<slot>.calorie_target} / {@code per_meal.<slot>.protein_target_g} — a
 *       per-meal distribution slice
 * </ul>
 *
 * <p>{@code absoluteValue} (optional) takes precedence over the relative {@code magnitude} when the
 * classifier extracted an explicit target ("increase my calorie target to 2200"). {@code reason} is
 * free text recorded for the quality-monitoring path; it does not affect the computed value.
 */
public record FeedbackTargetAdjustment(
    @NotBlank @Size(max = 96) String target,
    @NotNull AdjustmentDirection direction,
    @NotNull AdjustmentMagnitude magnitude,
    @Nullable BigDecimal absoluteValue,
    @Size(max = 256) String reason) {}
