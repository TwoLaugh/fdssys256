package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-slot input to {@code prefillFromPlan}. In-process only; no HTTP endpoint accepts this. The
 * production caller is {@code PlanAcceptedPrefillListener}, which assembles one per planner
 * breakfast/lunch/dinner slot on plan acceptance. All nutrition fields are nullable: null means the
 * plan carried no computed figure, and micros a recipe did not measure are omitted from {@code
 * plannedMicros}, never written as 0.
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
