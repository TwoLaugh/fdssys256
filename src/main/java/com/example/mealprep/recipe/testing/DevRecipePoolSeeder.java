package com.example.mealprep.recipe.testing;

import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.testing.DatasetRecipeMapper.DatasetRecipe;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-profile startup seeder for the SYSTEM recipe catalogue. A fresh dev database has ZERO
 * recipes: the bulk-import endpoint ({@code POST /test-support/recipe/nutrition/import-pool}) is
 * {@code @Profile("e2e")}-gated, cold-start discovery sources need real credentials, and without a
 * pool every generated plan is meaningless. This runner gives a dev/dogfood stack a realistic
 * curated pool without exposing any HTTP surface.
 *
 * <p><b>How it works.</b> When {@code mealprep.dev.recipe-pool-path} (env {@code
 * MEALPREP_DEV_RECIPE_POOL}) points at a JSON file containing a {@code List<DatasetRecipe>} (the
 * exact shape the e2e import-pool endpoint consumes — name, servings, ingredient lines, per-serving
 * macros + 28 micros, per-recipe mealTypes), each row is imported through {@link
 * RecipeWriteApi#saveImportedRecipe} (the discovery import seam) and its nutrition written through
 * the {@link RecipeNutritionWriter} SPI — identical mapping to the e2e path via {@link
 * DatasetRecipeMapper}.
 *
 * <p><b>Idempotent.</b> If the SYSTEM catalogue is already non-empty the seeder skips entirely, so
 * restarts never duplicate the pool (the import fingerprint would dedupe anyway; the count check
 * also keeps restarts fast). Unset/blank path → no-op.
 *
 * <p><b>Why {@code recipe.testing}.</b> The package is the sanctioned ArchUnit carve-out for
 * seed/support scaffolding that may inject recipe repositories directly. The bean is
 * {@code @Profile("dev")} — it does not exist under prod/e2e/test, and the licensing posture of the
 * seed data (e.g. CC BY-NC datasets fine for personal local use) stays outside the shipped
 * artifact: the JSON lives on the developer's disk, never on the classpath.
 */
@Component
@Profile("dev")
public class DevRecipePoolSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DevRecipePoolSeeder.class);

  private final RecipeRepository recipeRepository;
  private final RecipeWriteApi recipeWriteApi;
  private final RecipeNutritionWriter nutritionWriter;
  private final ObjectMapper objectMapper;
  private final String poolPath;

  public DevRecipePoolSeeder(
      RecipeRepository recipeRepository,
      RecipeWriteApi recipeWriteApi,
      RecipeNutritionWriter nutritionWriter,
      ObjectMapper objectMapper,
      @Value("${mealprep.dev.recipe-pool-path:}") String poolPath) {
    this.recipeRepository = recipeRepository;
    this.recipeWriteApi = recipeWriteApi;
    this.nutritionWriter = nutritionWriter;
    this.objectMapper = objectMapper;
    this.poolPath = poolPath;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (poolPath == null || poolPath.isBlank()) {
      log.info("dev recipe-pool seeder: mealprep.dev.recipe-pool-path unset — skipping");
      return;
    }
    long existing = recipeRepository.countByCatalogue(Catalogue.SYSTEM);
    if (existing > 0) {
      log.info(
          "dev recipe-pool seeder: SYSTEM catalogue already has {} recipe(s) — skipping", existing);
      return;
    }
    Path path = Path.of(poolPath);
    if (!Files.isReadable(path)) {
      log.warn("dev recipe-pool seeder: pool file not readable at {} — skipping", path);
      return;
    }
    List<DatasetRecipe> batch =
        objectMapper.readValue(
            Files.readAllBytes(path), new TypeReference<List<DatasetRecipe>>() {});
    int created = 0;
    Map<String, Integer> byMealType = new LinkedHashMap<>();
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
        List<String> mealTypes = req.mealTypes() == null ? List.of() : req.mealTypes();
        for (String mt : mealTypes) {
          byMealType.merge(mt, 1, Integer::sum);
        }
      } catch (RuntimeException ex) {
        log.warn("dev recipe-pool seeder: skipped '{}' — {}", req.name(), ex.toString());
      }
    }
    log.info(
        "dev recipe-pool seeder: created {} of {} recipe(s) from {} — mealType distribution {}",
        created,
        batch.size(),
        path,
        byMealType);
  }
}
