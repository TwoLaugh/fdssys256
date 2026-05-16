package com.example.mealprep.discovery.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Structured recipe produced by {@code DiscoverySource.fetchRecipe}. Field-compatible with the
 * recipe module's {@code CreateRecipeRequest} per LLD line 297, but a separate type — the runner
 * (01d) maps {@code ParsedRecipe} to {@code RecipeWriteApi.saveImportedRecipe}'s input shape.
 *
 * <p>Nutrition fields are intentionally absent (LLD line 299): external nutrition data is discarded
 * and recalculated by the recipe module's nutrition pipeline.
 *
 * <p>Per LLD line 283 (record + 3 nested records).
 */
public record ParsedRecipe(
    String canonicalUrl,
    String name,
    String description,
    List<ParsedIngredient> ingredients,
    List<ParsedMethodStep> method,
    ParsedRecipeMetadata metadata,
    String extractionMethod,
    BigDecimal extractionConfidence) {

  public record ParsedIngredient(
      String displayName,
      String ingredientMappingKey,
      BigDecimal quantity,
      String unit,
      String preparation,
      boolean optional) {}

  public record ParsedMethodStep(int stepNumber, String instruction, Integer durationMinutes) {}

  public record ParsedRecipeMetadata(
      Integer servings,
      Integer prepTimeMins,
      Integer cookTimeMins,
      Integer totalTimeMins,
      List<String> equipmentRequired,
      String cuisine,
      List<String> mealTypes) {}
}
