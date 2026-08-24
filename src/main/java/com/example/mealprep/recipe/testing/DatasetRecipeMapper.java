package com.example.mealprep.recipe.testing;

import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps one external-dataset recipe row (name + raw ingredient lines + pre-computed per-serving
 * nutrition) onto the recipe module's import seam types. Extracted from {@code
 * E2eNutritionSeedController} so the SAME mapping — meal-type-aware prep/cook times, ingredient
 * line truncation, fingerprinting, nutrition/micro-provenance carry-through — is used by both the
 * e2e bulk-import endpoint ({@code POST /test-support/recipe/nutrition/import-pool}) and the
 * dev-profile startup seeder ({@link DevRecipePoolSeeder}). Pure static functions; no Spring bean,
 * so it is profile-agnostic and never a live surface by itself.
 */
final class DatasetRecipeMapper {

  private DatasetRecipeMapper() {}

  /**
   * One dataset recipe to import (name + raw ingredient lines + pre-computed per-serving
   * nutrition).
   */
  public record DatasetRecipe(
      String name,
      Integer servings,
      List<String> ingredients,
      DatasetNutrition nutrition,
      // optional per-micro provenance {key: "measured"|"derived"|"estimated"}; null on older
      // batches
      Map<String, String> microSources,
      // optional per-micro confidence 0..1 (carried for "estimated" values); null otherwise
      Map<String, BigDecimal> microConfidence,
      // optional per-recipe meal types (breakfast/lunch/dinner/snack); null → all kinds
      List<String> mealTypes) {}

  /** Per-serving nutrition computed offline from USDA: macros + the 28 micros (canonical keys). */
  public record DatasetNutrition(
      Integer calories,
      BigDecimal proteinG,
      BigDecimal carbsG,
      BigDecimal fatG,
      BigDecimal fibreG,
      Map<String, BigDecimal> micros) {}

  /**
   * Build the {@link ImportedRecipeData} for one dataset row. Prep/total time is sized to the
   * TIGHTEST slot the recipe is eligible for, so breakfast/snack slots (small time budgets) are
   * actually fillable — the beam's time-budget hard filter rejects any recipe whose totalTimeMins
   * exceeds budget x 1.5, and a fixed 30-min total silently excludes breakfast (15) and snack (5).
   */
  static ImportedRecipeData toImportedRecipeData(DatasetRecipe req, int idx) {
    List<ImportedRecipeData.ImportedIngredient> ings = new ArrayList<>();
    List<String> lines = req.ingredients() == null ? List.of() : req.ingredients();
    for (int i = 0; i < lines.size(); i++) {
      String display = trunc(lines.get(i), 200);
      ings.add(
          new ImportedRecipeData.ImportedIngredient(
              i, display, mappingKey(display), BigDecimal.ONE, "", null, false));
    }
    if (ings.isEmpty()) {
      ings.add(
          new ImportedRecipeData.ImportedIngredient(
              0, "ingredient", "ingredient", BigDecimal.ONE, "", null, false));
    }
    int servings = req.servings() != null && req.servings() > 0 ? req.servings() : 4;
    List<String> mealTypes =
        req.mealTypes() == null || req.mealTypes().isEmpty()
            ? List.of("breakfast", "lunch", "dinner", "snack", "snacks")
            : req.mealTypes();
    int total =
        mealTypes.contains("snack") || mealTypes.contains("snacks")
            ? 5
            : mealTypes.contains("breakfast") ? 12 : 25;
    int prep = Math.max(2, total / 3);
    int cook = total - prep;
    ImportedRecipeData.ImportedRecipeMetadata meta =
        new ImportedRecipeData.ImportedRecipeMetadata(
            servings, prep, cook, total, List.of(), null, null, false, null, mealTypes);
    ImportedRecipeData.ImportedRecipeTags tags =
        new ImportedRecipeData.ImportedRecipeTags(null, null, "easy", List.of(), List.of());
    String name = trunc(req.name() == null ? "Recipe" : req.name(), 160);
    String fp = "dataset-" + idx + "-" + Integer.toHexString((name + idx).hashCode());
    return new ImportedRecipeData(
        "dataset_import",
        "dataset://corbt/all-recipes/" + idx,
        fp,
        name,
        null,
        ings,
        List.of(new ImportedRecipeData.ImportedMethodStep(1, "Prepare and serve.", null)),
        meta,
        tags,
        "dataset",
        BigDecimal.valueOf(0.9),
        null,
        null);
  }

  /** Map the dataset row's pre-computed per-serving nutrition onto the nutrition SPI result DTO. */
  static RecipeNutritionResultDto toNutrition(DatasetRecipe req, UUID recipeId) {
    DatasetNutrition n = req.nutrition();
    Map<String, BigDecimal> micros = new LinkedHashMap<>();
    if (n != null && n.micros() != null) {
      n.micros()
          .forEach(
              (k, v) -> {
                if (v != null) {
                  micros.put(k, v);
                }
              });
    }
    Map<String, String> sources =
        req.microSources() == null ? Map.of() : new LinkedHashMap<>(req.microSources());
    Map<String, BigDecimal> confidence =
        req.microConfidence() == null ? Map.of() : new LinkedHashMap<>(req.microConfidence());
    return new RecipeNutritionResultDto(
        recipeId,
        n != null && n.calories() != null ? n.calories() : 0,
        nz(n == null ? null : n.proteinG()),
        nz(n == null ? null : n.carbsG()),
        nz(n == null ? null : n.fatG()),
        nz(n == null ? null : n.fibreG()),
        micros,
        "calculated",
        List.of(),
        sources,
        confidence);
  }

  static String trunc(String s, int max) {
    return s != null && s.length() > max ? s.substring(0, max) : s;
  }

  private static String mappingKey(String s) {
    String k = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    if (k.isEmpty()) {
      k = "ingredient";
    }
    return k.length() > 64 ? k.substring(0, 64) : k;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
