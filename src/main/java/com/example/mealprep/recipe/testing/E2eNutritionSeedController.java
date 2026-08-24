package com.example.mealprep.recipe.testing;

import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.testing.DatasetRecipeMapper.DatasetRecipe;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E-only: seed deterministic per-serving nutrition (macros + the 28 tracked micronutrients) onto
 * every SYSTEM-catalogue recipe, so the nutrition-driven planner has real numbers — and a varied
 * spread — to optimise against without a live USDA/AI ingredient-mapping pipeline.
 *
 * <p><b>Why a direct SPI write.</b> The recipe pool the e2e stack serves ({@code
 * E2eSeedDiscoverySource}) imports recipes with {@code nutritionPerServing = null}; the planner's
 * nutrition scoring/gaps/floor-gate therefore see zeros. Rather than seed ingredient mappings and
 * run the recalc pipeline (couples the fixture to USDA/OFF + AI parse/match), this writes computed
 * per-serving results straight through the {@link RecipeNutritionWriter} SPI — the same seam the
 * real recalc uses. Values are derived deterministically from the recipe id, so re-running is
 * idempotent (and the writer itself is idempotent).
 *
 * <p><b>Key alignment.</b> The micro map keys are exactly the target {@code nutrientKey}s seeded in
 * {@code R__nutrition_seed_dri_defaults.sql}, so the planner's per-micro matching resolves. The
 * per-serving baselines are ≈ one third of the adult DRI (three servings/day ≈ daily target) before
 * a per-recipe ±factor, giving a spread where most micros are coverable over a week and a few fall
 * short — a realistic signal rather than a rigged pass.
 *
 * <p><b>Strictly {@code e2e}-profile-gated</b> (mirrors {@link E2eRecipeCatalogueController}): the
 * bean + its {@code /test-support/recipe/nutrition} mappings do not exist under {@code prod}/{@code
 * dev}/{@code test}. Lives in {@code recipe.testing} (the sanctioned {@code ..testing..} ArchUnit
 * carve-out) so it may inject the recipe repositories directly; the {@code RecipeNutritionWriter}
 * SPI + {@code RecipeNutritionResultDto} are already recipe-module dependencies (recipe-01f
 * implements the SPI).
 */
@RestController
@RequestMapping("/test-support/recipe/nutrition")
@Profile("e2e")
@Tag(name = "E2E Test Support")
public class E2eNutritionSeedController {

  private static final Logger log = LoggerFactory.getLogger(E2eNutritionSeedController.class);

  /**
   * Per-serving baseline per tracked micro ≈ one third of the adult DRI. Keys MUST match the target
   * {@code nutrientKey}s in {@code R__nutrition_seed_dri_defaults.sql} (28 micros).
   */
  private static final Map<String, Double> MICRO_PER_SERVING_BASE = microBaseline();

  private final RecipeRepository recipeRepository;
  private final RecipeVersionRepository recipeVersionRepository;
  private final RecipeNutritionWriter nutritionWriter;
  private final RecipeWriteApi recipeWriteApi;

  public E2eNutritionSeedController(
      RecipeRepository recipeRepository,
      RecipeVersionRepository recipeVersionRepository,
      RecipeNutritionWriter nutritionWriter,
      RecipeWriteApi recipeWriteApi) {
    this.recipeRepository = recipeRepository;
    this.recipeVersionRepository = recipeVersionRepository;
    this.nutritionWriter = nutritionWriter;
    this.recipeWriteApi = recipeWriteApi;
  }

  /**
   * Bulk-create SYSTEM recipes from an external dataset, each carrying pre-computed per-serving
   * nutrition (macros + the 28 micros). Each is created via {@link
   * RecipeWriteApi#saveImportedRecipe} (the discovery import seam) tagged with all four meal types
   * so it is plannable in every slot, then its nutrition is written via the SPI. The nutrition
   * listener fires on {@code RecipeUpdatedEvent} (edits) — NOT on import — so the written values
   * are the final persisted state. Per-recipe failures are logged + skipped; idempotent via the
   * fingerprint.
   *
   * @return {@code {seeded, microsPerRecipe}} — recipes created with nutrition + micro count
   */
  @PostMapping(
      path = "/import-pool",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SeedResult importPool(@RequestBody List<DatasetRecipe> batch) {
    int created = 0;
    for (int idx = 0; idx < batch.size(); idx++) {
      DatasetRecipe req = batch.get(idx);
      try {
        var result =
            recipeWriteApi.saveImportedRecipe(DatasetRecipeMapper.toImportedRecipeData(req, idx));
        if (result == null || result.versionId() == null) {
          continue;
        }
        nutritionWriter.writeNutritionPerServing(
            result.versionId(), DatasetRecipeMapper.toNutrition(req, result.recipeId()));
        created++;
      } catch (RuntimeException ex) {
        log.warn("import-pool: skipped '{}' — {}", req.name(), ex.toString());
      }
    }
    log.info(
        "E2E import-pool: created {} of {} dataset recipes with nutrition", created, batch.size());
    return new SeedResult(created, MICRO_PER_SERVING_BASE.size());
  }

  /**
   * Backfill REAL per-serving nutrition onto EXISTING SYSTEM recipes, matched by name — without
   * recreating them (so their embeddings, branches and ids survive). Used to add the USDA-derived
   * fatty-acid breakdown (saturated/mono/poly) to a pool that was imported before those keys
   * existed: re-deriving offline produces {name → full nutrition incl. the new micros}, and this
   * writes it through the same {@link RecipeNutritionWriter} SPI onto each matched recipe's current
   * version. Name-matched (not the import fingerprint) so it is chunk-safe and never duplicates.
   * Unmatched names are skipped. First recipe wins on duplicate names.
   *
   * @return {@code {written, microsPerRecipe}} — recipes whose nutrition was rewritten
   */
  @PostMapping(
      path = "/write-by-name",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SeedResult writeByName(@RequestBody List<DatasetRecipe> batch) {
    Map<String, Recipe> byName = new java.util.HashMap<>();
    for (Recipe r : recipeRepository.findByCatalogue(Catalogue.SYSTEM)) {
      if (r.getName() != null) {
        byName.putIfAbsent(r.getName(), r);
      }
    }
    int written = 0;
    for (DatasetRecipe req : batch) {
      Recipe recipe =
          req.name() == null ? null : byName.get(DatasetRecipeMapper.trunc(req.name(), 160));
      if (recipe == null || recipe.getCurrentBranchId() == null) {
        continue;
      }
      UUID versionId =
          recipeVersionRepository
              .findCurrentVersionId(
                  recipe.getId(), recipe.getCurrentBranchId(), recipe.getCurrentVersion())
              .orElse(null);
      if (versionId == null) {
        continue;
      }
      nutritionWriter.writeNutritionPerServing(
          versionId, DatasetRecipeMapper.toNutrition(req, recipe.getId()));
      written++;
    }
    log.info(
        "E2E write-by-name: rewrote nutrition for {} of {} batch recipes", written, batch.size());
    return new SeedResult(written, MICRO_PER_SERVING_BASE.size());
  }

  /**
   * Write deterministic per-serving nutrition onto every SYSTEM recipe's current version.
   * Idempotent — same recipe id always yields the same numbers, and the SPI write is idempotent.
   *
   * @return {@code {seeded, microsPerRecipe}} — recipes written + micros attached to each
   */
  @PostMapping(path = "/seed-pool", produces = MediaType.APPLICATION_JSON_VALUE)
  public SeedResult seedPoolNutrition() {
    List<Recipe> systemRecipes = recipeRepository.findByCatalogue(Catalogue.SYSTEM);
    int seeded = 0;
    for (Recipe recipe : systemRecipes) {
      if (recipe.getCurrentBranchId() == null) {
        continue;
      }
      UUID versionId =
          recipeVersionRepository
              .findCurrentVersionId(
                  recipe.getId(), recipe.getCurrentBranchId(), recipe.getCurrentVersion())
              .orElse(null);
      if (versionId == null) {
        continue;
      }
      nutritionWriter.writeNutritionPerServing(versionId, generate(recipe.getId()));
      seeded++;
    }
    log.info(
        "E2E nutrition seed: wrote per-serving nutrition for {} of {} SYSTEM recipe(s)",
        seeded,
        systemRecipes.size());
    return new SeedResult(seeded, MICRO_PER_SERVING_BASE.size());
  }

  /** Deterministic per-serving nutrition from the recipe id — stable across re-runs. */
  private RecipeNutritionResultDto generate(UUID recipeId) {
    int h = Math.abs(recipeId.hashCode());
    int calories = 800 + (h % 700); // 800–1500 kcal/serving → 3 meals/day can reach ~3.6k
    BigDecimal protein = bd(35 + (h % 35)); // 35–70 g → 3 meals/day can reach ~150 g
    BigDecimal carbs = bd(60 + ((h >> 3) % 90));
    BigDecimal fat = bd(20 + ((h >> 5) % 35));
    BigDecimal fibre = bd(5 + ((h >> 7) % 12));
    Map<String, BigDecimal> micros = new LinkedHashMap<>();
    for (Map.Entry<String, Double> entry : MICRO_PER_SERVING_BASE.entrySet()) {
      int mh = Math.abs((recipeId + "|" + entry.getKey()).hashCode());
      // variation in [0.40, 1.40]: over a week some micros clear the floor, some fall short.
      double factor = 0.40 + (mh % 101) / 100.0;
      micros.put(entry.getKey(), bd(entry.getValue() * factor));
    }
    return new RecipeNutritionResultDto(
        recipeId, calories, protein, carbs, fat, fibre, micros, "calculated", List.of());
  }

  private static BigDecimal bd(double value) {
    return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
  }

  private static Map<String, Double> microBaseline() {
    Map<String, Double> m = new LinkedHashMap<>();
    // ≈ DRI / 3 so three servings/day land near the daily target before per-recipe variation.
    m.put("calcium_mg", 333.0);
    m.put("iron_mg", 4.0);
    m.put("magnesium_mg", 130.0);
    m.put("zinc_mg", 3.5);
    m.put("vitamin_c_mg", 30.0);
    m.put("vitamin_b12_mcg", 0.8);
    m.put("folate_mcg", 135.0);
    m.put("vitamin_a_mcg", 270.0);
    m.put("vitamin_d_mcg", 5.0);
    m.put("vitamin_e_mg", 5.0);
    m.put("vitamin_k_mcg", 40.0);
    m.put("thiamin_mg", 0.4);
    m.put("riboflavin_mg", 0.43);
    m.put("niacin_mg", 5.0);
    m.put("vitamin_b6_mg", 0.45);
    m.put("pantothenic_acid_mg", 1.7);
    m.put("biotin_mcg", 10.0);
    m.put("choline_mg", 165.0);
    m.put("phosphorus_mg", 235.0);
    m.put("potassium_mg", 1000.0);
    m.put("sodium_mg", 500.0);
    m.put("chloride_mg", 770.0);
    m.put("copper_mg", 0.3);
    m.put("manganese_mg", 0.7);
    m.put("selenium_mcg", 18.0);
    m.put("iodine_mcg", 50.0);
    m.put("chromium_mcg", 11.0);
    m.put("molybdenum_mcg", 15.0);
    return m;
  }

  /** Response body for {@link #seedPoolNutrition()}. */
  public record SeedResult(int seeded, int microsPerRecipe) {}
}
