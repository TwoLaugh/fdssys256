package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;

/**
 * Per-ingredient input line to {@code NutritionCalculationService}. Verbatim from LLD line 498 —
 * the caller (recipe module, listener path) maps its own internal {@code RecipeIngredientDto} onto
 * this record so the calc service stays decoupled from recipe entity types.
 *
 * <p>{@code ingredientMappingKey} is the normalised cache key (preferred); when absent, {@code
 * name} is normalised via {@code IntakeKeyNormaliser} and looked up. {@code gramsEstimate} is the
 * authoritative per-line gram weight — when null, the line contributes zero nutrition and is
 * treated as unmapped.
 */
public record RecipeIngredientLineDto(
    String name,
    String ingredientMappingKey,
    BigDecimal quantity,
    String unit,
    BigDecimal gramsEstimate,
    Boolean isCooked) {}
