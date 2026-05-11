package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * JSONB document persisted alongside {@code IngredientMapping}. Mirrors LLD §IngredientNutritionDto
 * / IngredientNutritionDocument (lines 479-490): all scalar fields nullable because USDA / OFF
 * returns are sparse, {@code micros} / {@code vitamins} keys come through verbatim from the source
 * until the AI module's nutrient-name normaliser lands.
 */
public record IngredientNutritionDocument(
    Integer calories,
    BigDecimal proteinG,
    BigDecimal carbsG,
    BigDecimal fatG,
    BigDecimal fibreG,
    BigDecimal saturatedFatG,
    BigDecimal sugarG,
    Map<String, BigDecimal> micros,
    Map<String, BigDecimal> vitamins) {}
