package com.example.mealprep.recipe.testing;

import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
 * bean + its {@code /test-support/recipe/nutrition} mappings do not exist under {@code
 * prod}/{@code dev}/{@code test}. Lives in {@code recipe.testing} (the sanctioned {@code ..testing..}
 * ArchUnit carve-out) so it may inject the recipe repositories directly; the {@code
 * RecipeNutritionWriter} SPI + {@code RecipeNutritionResultDto} are already recipe-module
 * dependencies (recipe-01f implements the SPI).
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
   * listener fires on {@code RecipeUpdatedEvent} (edits) — NOT on import — so the written values are
   * the final persisted state. Per-recipe failures are logged + skipped; idempotent via the
   * fingerprint.
   *
   * @return {@code {seeded, microsPerRecipe}} — recipes created with nutrition + micro count
   */
  @PostMapping(
      path = "/import-pool",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SeedResult importPool(@RequestBody List<ImportRecipeRequest> batch) {
    int created = 0;
    for (int idx = 0; idx < batch.size(); idx++) {
      ImportRecipeRequest req = batch.get(idx);
      try {
        ImportedRecipeResultLike result = saveOne(req, idx);
        if (result == null) {
          continue;
        }
        nutritionWriter.writeNutritionPerServing(result.versionId(), toNutrition(req, result.recipeId()));
        created++;
      } catch (RuntimeException ex) {
        log.warn("import-pool: skipped '{}' — {}", req.name(), ex.toString());
      }
    }
    log.info("E2E import-pool: created {} of {} dataset recipes with nutrition", created, batch.size());
    return new SeedResult(created, MICRO_PER_SERVING_BASE.size());
  }

  private record ImportedRecipeResultLike(UUID recipeId, UUID versionId) {}

  private ImportedRecipeResultLike saveOne(ImportRecipeRequest req, int idx) {
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
    ImportedRecipeData.ImportedRecipeMetadata meta =
        new ImportedRecipeData.ImportedRecipeMetadata(
            servings, 10, 20, 30, List.of(), null, null, false, null,
            List.of("breakfast", "lunch", "dinner", "snack", "snacks"));
    ImportedRecipeData.ImportedRecipeTags tags =
        new ImportedRecipeData.ImportedRecipeTags(null, null, "easy", List.of(), List.of());
    String name = trunc(req.name() == null ? "Recipe" : req.name(), 160);
    String fp = "dataset-" + idx + "-" + Integer.toHexString((name + idx).hashCode());
    ImportedRecipeData data =
        new ImportedRecipeData(
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
    var r = recipeWriteApi.saveImportedRecipe(data);
    return r == null || r.versionId() == null ? null : new ImportedRecipeResultLike(r.recipeId(), r.versionId());
  }

  private static RecipeNutritionResultDto toNutrition(ImportRecipeRequest req, UUID recipeId) {
    NutritionInput n = req.nutrition();
    Map<String, BigDecimal> micros = new LinkedHashMap<>();
    if (n != null && n.micros() != null) {
      n.micros().forEach((k, v) -> {
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

  private static String mappingKey(String s) {
    String k = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    if (k.isEmpty()) {
      k = "ingredient";
    }
    return k.length() > 64 ? k.substring(0, 64) : k;
  }

  private static String trunc(String s, int max) {
    return s != null && s.length() > max ? s.substring(0, max) : s;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /** One dataset recipe to import (name + raw ingredient lines + pre-computed per-serving nutrition). */
  public record ImportRecipeRequest(
      String name,
      Integer servings,
      List<String> ingredients,
      NutritionInput nutrition,
      // optional per-micro provenance {key: "measured"|"derived"|"estimated"}; null on older batches
      Map<String, String> microSources,
      // optional per-micro confidence 0..1 (carried for "estimated" values); null otherwise
      Map<String, BigDecimal> microConfidence) {}

  /** Per-serving nutrition computed offline from USDA: macros + the 28 micros (canonical keys). */
  public record NutritionInput(
      Integer calories,
      BigDecimal proteinG,
      BigDecimal carbsG,
      BigDecimal fatG,
      BigDecimal fibreG,
      Map<String, BigDecimal> micros) {}

  /**
   * Write deterministic per-serving nutrition onto every SYSTEM recipe's current version. Idempotent
   * — same recipe id always yields the same numbers, and the SPI write is idempotent.
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
