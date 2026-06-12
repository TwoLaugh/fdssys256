package com.example.mealprep.recipe.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Per-serving nutrition figures surfaced on {@link RecipeVersionDto} (ticket
 * recipe-version-nutrition-per-serving). Read-side projection of the {@code nutrition_per_serving}
 * JSONB the nutrition module persists onto the version row via the {@code RecipeNutritionWriter}
 * SPI bridge — the recipe module never computes nutrition, it only re-shapes the stored result
 * ({@code RecipeNutritionResultDto} field names {@code caloriesPerServing} / {@code
 * proteinPerServingG} / … become the contract's {@code calories} / {@code proteinG} / …).
 *
 * <p>{@code null} on the version DTO until the nutrition module has computed (stored status still
 * {@code pending} or nothing persisted); populated for {@code calculated} and {@code partial}
 * results (partial = best available numbers alongside the needs-review badge).
 */
public record NutritionPerServingDto(
    int calories,
    BigDecimal proteinG,
    BigDecimal carbsG,
    BigDecimal fatG,
    BigDecimal fibreG,
    Map<String, BigDecimal> micros) {}
