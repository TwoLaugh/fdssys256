package com.example.mealprep.recipe.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Read shape of a single ingredient line on a recipe version. */
public record IngredientDto(
    UUID id,
    int lineOrder,
    String ingredientMappingKey,
    String displayName,
    BigDecimal quantity,
    String unit,
    String preparation,
    boolean optional,
    boolean needsReview,
    BigDecimal mappingConfidence) {}
