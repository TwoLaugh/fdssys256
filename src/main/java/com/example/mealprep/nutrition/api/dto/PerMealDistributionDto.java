package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Per-meal slice of the daily target. */
public record PerMealDistributionDto(
    @NotNull MealSlot mealSlot,
    @Min(0) int calorieTarget,
    @NotNull @DecimalMin("0.0") BigDecimal proteinTargetG) {}
