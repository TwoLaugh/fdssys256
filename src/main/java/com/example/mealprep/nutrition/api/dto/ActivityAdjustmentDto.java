package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import jakarta.validation.constraints.NotNull;

/**
 * Per-activity-level calorie / carb modifier. Modifiers can be negative (rest day) or positive
 * (training day); the planner sums these onto the base targets when an activity is logged.
 */
public record ActivityAdjustmentDto(
    @NotNull ActivityLevel activityLevel, int calorieModifier, int carbModifierG) {}
