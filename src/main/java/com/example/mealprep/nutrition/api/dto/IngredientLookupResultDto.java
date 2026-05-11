package com.example.mealprep.nutrition.api.dto;

import java.util.List;

/**
 * Response for {@code POST /api/v1/nutrition/ingredients/search}. {@code cacheOnly = true} in v1 —
 * the live USDA / OFF discovery path lands in nutrition-01m. The field is preserved on the wire so
 * the contract does not break later.
 */
public record IngredientLookupResultDto(List<IngredientNutritionDto> hits, boolean cacheOnly) {}
