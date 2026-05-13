package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.validation.ValidUiContext;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire shape of the UI context that accompanies a feedback submission. The {@code @ValidUiContext}
 * class-level validator enforces "{@code recipeId} present when {@code screen} is {@code
 * RECIPE_DETAIL}", "{@code planId}+{@code mealSlotId} present when {@code screen} is {@code
 * PLAN_MEAL_DETAIL}", and "{@code recipeVersion} implies {@code recipeId}" per LLD line 642.
 */
@ValidUiContext
public record UiContextDto(
    Screen screen,
    UUID recipeId,
    Integer recipeVersion,
    UUID mealSlotId,
    UUID planId,
    LocalDate referenceDate) {}
