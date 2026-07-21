package com.example.mealprep.recipe.spi;

import com.example.mealprep.core.types.DataQuality;
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
 * <p>{@code dataQuality} (G10, additive): the trust tier the import should persist with. {@code
 * null} → {@code WEB_DISCOVERED} (the pre-G10 hardcode, preserved for discovery-crawl callers);
 * graph-batch ingest passes {@code AI_GENERATED} so generated dishes stop masquerading as scraped
 * ones (the honesty rule, design doc §6 #15). Uses the {@code core.types} enum — {@code recipe.spi}
 * may depend on core.
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
    UUID traceId,
    DataQuality dataQuality) {

  /**
   * Back-compat convenience constructor preserving the pre-G10 13-arg signature; {@code
   * dataQuality} defaults to {@code null} (→ {@code WEB_DISCOVERED} in {@code saveImportedRecipe})
   * so existing construction sites compile and behave unchanged.
   */
  public ImportedRecipeData(
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
    this(
        sourceKey,
        canonicalUrl,
        contentFingerprint,
        name,
        description,
        ingredients,
        method,
        metadata,
        tags,
        extractionMethod,
        extractionConfidence,
        jobId,
        traceId,
        null);
  }

  /** Copy with {@code dataQuality} replaced — used by the graph ingest runner (G10 item 3). */
  public ImportedRecipeData withDataQuality(DataQuality quality) {
    return new ImportedRecipeData(
        sourceKey,
        canonicalUrl,
        contentFingerprint,
        name,
        description,
        ingredients,
        method,
        metadata,
        tags,
        extractionMethod,
        extractionConfidence,
        jobId,
        traceId,
        quality);
  }

  /**
   * One imported ingredient line. {@code ingredientMappingKey} is the normalised key used by the
   * nutrition pipeline to resolve USDA mappings (per {@code design/recipe-system.md} §Ingredients);
   * the discovery runner populates it (deriving the v1 fallback from {@code displayName} via {@code
   * IngredientMappingKeys.normalise} when the source supplies none) so it is never {@code null} —
   * {@code recipe_ingredients.ingredient_mapping_key} is {@code NOT NULL}.
   */
  public record ImportedIngredient(
      int lineOrder,
      String displayName,
      String ingredientMappingKey,
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
