package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-slot input to {@code prefillFromPlan}. Used in-process only by the (deferred) planner module;
 * no HTTP endpoint accepts this in 01b.
 */
public record PlannedSlotInputDto(
    @NotNull MealSlot mealSlot,
    UUID plannedRecipeId,
    @Min(0) Integer plannedCalories,
    @Min(0) BigDecimal plannedProteinG,
    @Min(0) BigDecimal plannedCarbsG,
    @Min(0) BigDecimal plannedFatG,
    @Min(0) BigDecimal plannedFibreG,
    JsonNode plannedMicros) {}
