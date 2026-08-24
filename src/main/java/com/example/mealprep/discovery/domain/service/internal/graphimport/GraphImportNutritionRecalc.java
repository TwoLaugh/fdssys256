package com.example.mealprep.discovery.domain.service.internal.graphimport;

import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.RecipeIngredientLineDto;
import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.api.dto.UnmappedIngredientDto;
import com.example.mealprep.nutrition.domain.service.NutritionCalculationService;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * G07 — explicit post-import nutrition recompute for graph-batch dishes (design doc §6, component
 * #12; ticket {@code tickets/engine-integration/G07-nutrition-recompute.md}). Called by the G06
 * runner per imported dish so a freshly imported recipe reaches an honest {@code CALCULATED} with
 * per-serving macros + canonical-key micros, computed by the ENGINE from the artifact's ingredient
 * lines × the G05-seeded {@code IngredientMapping} rows. Spike numbers are never persisted
 * (standing law #2) — the artifact's {@code nutrition_expected.json} exists only for G08's
 * comparison and is never read here.
 *
 * <p>Lines are built FROM THE BATCH ARTIFACT (not a DB read-back): the export contract guarantees
 * exact grams ({@code unit == "g"}), so {@code gramsEstimate = quantity} verbatim — no unit
 * estimation, no {@code IngredientUnitConverter} involvement, and the finding-3 zero-grams failure
 * mode is structurally impossible on this path.
 *
 * <p>Honesty gates (fail the dish, never write junk): any non-{@code calculated} status or a
 * zero-kcal result throws {@link GraphImportNutritionRecalcException} BEFORE the writer is invoked
 * — the dish stays honestly {@code PENDING} and G06 counts it rejected with the reason. With G05
 * seeded and G06's pre-flight (all keys resolve), either gate firing means seed drift.
 *
 * <p>The calc runs read-only and the writer opens its own write tx — they are invoked sequentially,
 * never wrapped in one transaction (writer contract owns its tx).
 */
@Component
public class GraphImportNutritionRecalc {

  private static final Logger log = LoggerFactory.getLogger(GraphImportNutritionRecalc.class);

  private final NutritionCalculationService calculationService;
  private final RecipeNutritionWriter writer;

  public GraphImportNutritionRecalc(
      NutritionCalculationService calculationService, RecipeNutritionWriter writer) {
    this.calculationService = calculationService;
    this.writer = writer;
  }

  /** Per-dish outcome reported back into G06's {@code IngestedDish} entry. */
  public record Outcome(String nutritionStatus, int microCount) {}

  /** Gate failure: the dish must be counted rejected; nothing was persisted by this class. */
  public static class GraphImportNutritionRecalcException extends RuntimeException {
    public GraphImportNutritionRecalcException(String message) {
      super(message);
    }
  }

  /**
   * Recompute + persist nutrition for one imported dish. Throws {@link
   * GraphImportNutritionRecalcException} (dish FAILED, writer untouched) on any guard or honesty
   * gate; returns the persisted status + micro count on success. Idempotent end-to-end: the writer
   * contract makes a dedup-path re-run rewrite identical values.
   */
  public Outcome recompute(ImportedRecipeData data, UUID recipeId, UUID versionId) {
    List<RecipeIngredientLineDto> lines = toLines(data);

    // Servings passed through from the artifact (== 1 per D2) — passing rather than hardcoding
    // keeps a D2 flip from silently halving numbers.
    Integer servings = data.metadata() == null ? null : data.metadata().servings();
    if (servings == null || servings < 1) {
      throw new GraphImportNutritionRecalcException(
          "G07 guard: artifact metadata.servings missing/invalid (" + servings + ")");
    }

    RecipeNutritionResultDto result =
        calculationService.recalculateForEvolvedRecipe(
            new CalculateRecipeNutritionRequest(recipeId, lines, servings));

    // Honesty gate 1: with G05 seeded and G06 pre-flight passed, anything but "calculated" means
    // seed drift — name the unmapped lines in the failure.
    if (!"calculated".equals(result.nutritionStatus())) {
      throw new GraphImportNutritionRecalcException(
          "G07 honesty gate: recompute status '"
              + result.nutritionStatus()
              + "' != calculated (seed drift?) — unmapped: "
              + result.unmapped().stream().map(UnmappedIngredientDto::name).toList());
    }
    // Honesty gate 2: a real dish is never 0 kcal — a zero-kcal write is exactly the finding-3
    // failure mode this ticket exists to prevent.
    if (result.caloriesPerServing() <= 0) {
      throw new GraphImportNutritionRecalcException(
          "G07 honesty gate: recompute produced " + result.caloriesPerServing() + " kcal/serving");
    }

    writer.writeNutritionPerServing(versionId, result);
    log.info(
        "graph recompute recipeId={} versionId={} kcal={} micros={} status=CALCULATED",
        recipeId,
        versionId,
        result.caloriesPerServing(),
        result.microsPerServing().size());
    return new Outcome("CALCULATED", result.microsPerServing().size());
  }

  /**
   * Artifact lines → calc lines, grams verbatim. Defence-in-depth guard (G06's validator already
   * rejects these shapes): any non-"g" unit or non-positive quantity throws — the export contract
   * is exact grams, and estimating here would silently reintroduce the unit-conversion ambiguity
   * this path is designed not to have.
   */
  private static List<RecipeIngredientLineDto> toLines(ImportedRecipeData data) {
    List<ImportedRecipeData.ImportedIngredient> ingredients =
        data.ingredients() == null ? List.of() : data.ingredients();
    if (ingredients.isEmpty()) {
      throw new GraphImportNutritionRecalcException("G07 guard: artifact has no ingredient lines");
    }
    List<RecipeIngredientLineDto> lines = new ArrayList<>(ingredients.size());
    for (ImportedRecipeData.ImportedIngredient in : ingredients) {
      BigDecimal quantity = in.quantity();
      if (!"g".equals(in.unit()) || quantity == null || quantity.signum() <= 0) {
        throw new GraphImportNutritionRecalcException(
            "G07 guard: line '"
                + in.displayName()
                + "' violates the exact-grams export contract (unit="
                + in.unit()
                + ", quantity="
                + quantity
                + ")");
      }
      // isCooked is dead on the compute path (verified in the ticket) — pass null, no semantics.
      lines.add(
          new RecipeIngredientLineDto(
              in.displayName(), in.ingredientMappingKey(), quantity, "g", quantity, null));
    }
    return lines;
  }
}
