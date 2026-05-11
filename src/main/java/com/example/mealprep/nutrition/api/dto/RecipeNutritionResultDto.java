package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-serving nutrition result, returned by {@code NutritionCalculationService} and persisted back
 * to the recipe version via the {@code RecipeNutritionWriter} SPI. Verbatim from LLD line 503.
 *
 * <p>{@code nutritionStatus} is one of {@code "calculated"} (every ingredient mapped + verified),
 * {@code "partial"} (some mapped, some unmapped, or any mapping {@code needsReview}), or {@code
 * "pending"} (no ingredient resolved). {@code unmapped} carries one entry per ingredient that the
 * cache could not resolve.
 */
public record RecipeNutritionResultDto(
    UUID recipeId,
    int caloriesPerServing,
    BigDecimal proteinPerServingG,
    BigDecimal carbsPerServingG,
    BigDecimal fatPerServingG,
    BigDecimal fibrePerServingG,
    Map<String, BigDecimal> microsPerServing,
    String nutritionStatus,
    List<UnmappedIngredientDto> unmapped) {}
