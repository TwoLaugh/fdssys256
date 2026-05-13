package com.example.mealprep.feedback.api.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire shape of the UI context that accompanies a feedback submission. The {@code @ValidUiContext}
 * class-level validator (which enforces "{@code recipeId} present when {@code screen} is {@code
 * RECIPE_DETAIL}", etc.) lands in feedback-01b alongside the submission flow; 01a ships the DTO
 * without the validator annotation.
 */
public record UiContextDto(
    Screen screen,
    UUID recipeId,
    Integer recipeVersion,
    UUID mealSlotId,
    UUID planId,
    LocalDate referenceDate) {}
