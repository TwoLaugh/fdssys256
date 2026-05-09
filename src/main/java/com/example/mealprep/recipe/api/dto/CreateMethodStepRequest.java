package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Single method step in a {@code CreateRecipeRequest}. */
public record CreateMethodStepRequest(
    @Min(1) int stepNumber, @NotBlank String instruction, Integer durationMinutes) {}
