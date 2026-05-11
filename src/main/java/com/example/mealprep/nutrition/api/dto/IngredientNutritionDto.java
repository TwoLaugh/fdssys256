package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire DTO for an ingredient mapping row. Mirrors LLD §IngredientNutritionDto (line 479). The
 * embedded {@link IngredientNutritionDocument} carries the raw nutrient breakdown.
 */
public record IngredientNutritionDto(
    String searchTerm,
    IngredientMappingSource source,
    String externalId,
    IngredientNutritionDocument nutritionPer100g,
    Integer defaultPieceGrams,
    BigDecimal confidence,
    boolean needsReview,
    Instant lastVerifiedAt,
    long version) {}
