package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/nutrition/ingredients/search}. {@code maxResults} is clamped to
 * [1,20]; when omitted the controller defaults to 10.
 */
public record IngredientLookupRequest(
    @NotBlank @Size(max = 255) String query, @Min(1) @Max(20) Integer maxResults) {}
