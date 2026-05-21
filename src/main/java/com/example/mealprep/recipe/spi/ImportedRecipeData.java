package com.example.mealprep.recipe.spi;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Cross-module SPI payload handed from the discovery pipeline to {@link
 * RecipeWriteApi#saveImportedRecipe} per discovery-01g. Field-shaped to be compatible with the
 * recipe module's create path but kept as a separate record so the recipe module's public DTOs do
 * not become discovery's wire contract.
 *
 * <p>Nutrition fields are intentionally absent — the recipe nutrition pipeline recomputes
 * per-serving values from the ingredient list. Embeddings are deferred to the async listener
 * triggered by {@code RecipeVersionCreatedEvent}.
 *
 * <p>Per ticket discovery-01g §`ImportedRecipeData` shape.
 */
public record ImportedRecipeData(
    String sourceKey,
    String canonicalUrl,
    String contentFingerprint,
    String name,
    String description,
    List<ImportedIngredient> ingredients,
    List<ImportedMethodStep> method,
    ImportedRecipeMetadata metadata,
    ImportedRecipeTags tags,
    String extractionMethod,
    BigDecimal extractionConfidence,
    UUID jobId,
    UUID traceId) {

  public record ImportedIngredient(
      int lineOrder,
      String displayName,
      BigDecimal quantity,
      String unit,
      String preparation,
      boolean optional) {}

  public record ImportedMethodStep(int stepNumber, String instruction, Integer durationMinutes) {}

  public record ImportedRecipeMetadata(
      Integer servings,
      Integer prepTimeMins,
      Integer cookTimeMins,
      Integer totalTimeMins,
      List<String> equipmentRequired,
      Integer fridgeDays,
      Integer freezerWeeks,
      Boolean packable,
      String cuisine,
      List<String> mealTypes) {}

  public record ImportedRecipeTags(
      String protein,
      String cookingMethod,
      String complexity,
      List<String> flavourProfile,
      List<String> dietaryFlags) {}
}
