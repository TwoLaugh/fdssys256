package com.example.mealprep.recipe.api.dto;

import java.util.UUID;

/** Read shape of a single instruction step on a recipe version. */
public record MethodStepDto(UUID id, int stepNumber, String instruction, Integer durationMinutes) {}
