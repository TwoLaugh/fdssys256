package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code PUT /api/v1/nutrition/ingredients/{searchTerm}/correction}. Upgrades the row's
 * source to {@code MANUAL}, sets {@code confidence = 1.0}, clears {@code needsReview}, and stamps
 * {@code lastVerifiedAt}.
 */
public record CorrectIngredientMappingRequest(
    @NotNull @Valid IngredientNutritionDocument override, @Min(0) long expectedVersion) {}
