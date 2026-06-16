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
    Map<String, BigDecimal> micros,
    // Per-micro provenance ("measured"/"derived"/"estimated") + confidence (0..1, for estimated),
    // mirrored from the persisted RecipeNutritionResultDto so the UI can show how each value was
    // sourced. Empty when the stored result predates provenance.
    Map<String, String> microSources,
    Map<String, BigDecimal> microConfidence) {

  /** Back-compat ctor (no provenance) — empty maps. */
  public NutritionPerServingDto(
      int calories,
      BigDecimal proteinG,
      BigDecimal carbsG,
      BigDecimal fatG,
      BigDecimal fibreG,
      Map<String, BigDecimal> micros) {
    this(calories, proteinG, carbsG, fatG, fibreG, micros, Map.of(), Map.of());
  }
}
