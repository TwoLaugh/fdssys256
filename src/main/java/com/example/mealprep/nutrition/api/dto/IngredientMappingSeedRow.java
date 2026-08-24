package com.example.mealprep.nutrition.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One row of the G05 seed artifact ({@code ingredient_mapping_seed.json}, schema {@code
 * graph-mapping-seed/1}) — spike canon, consumed-basis per-100g, already translated to engine
 * nutrient keys by the spike-side generator through G04's frozen table.
 *
 * <p>{@code nutrition} mirrors the engine {@code IngredientNutritionDocument} typed fields +
 * canonical-key {@code micros} (including the {@code saturated_fat_g} bridge). The artifact never
 * carries a {@code vitamins} map — the recompute reads {@code micros} only.
 */
public record IngredientMappingSeedRow(
    String searchTerm,
    IngredientMappingSource source,
    String externalId,
    String basisNote,
    SeedNutrition nutritionPer100g) {

  /** Typed macros + canonical-key micros, per-100g. {@code calories} is rounded engine-side. */
  public record SeedNutrition(
      BigDecimal calories,
      BigDecimal proteinG,
      BigDecimal carbsG,
      BigDecimal fatG,
      BigDecimal fibreG,
      BigDecimal saturatedFatG,
      Map<String, BigDecimal> micros) {}
}
