package com.example.mealprep.discovery.source.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedIngredient;
import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedMethodStep;
import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedRecipeMetadata;
import com.example.mealprep.recipe.extraction.ExtractionInput;
import com.example.mealprep.recipe.extraction.RecipeExtractionService;
import com.example.mealprep.recipe.extraction.RecipeExtractionServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Discovery's JSON-LD extraction adapter over the shared {@link RecipeExtractionService} (the
 * 5-layer pipeline from {@code recipe-extraction-pipeline.md}). This is the discovery module's view
 * of the shared engine — Pattern B, the migration the pre-refactor code documented as pending.
 *
 * <p>Both discovery sources ({@code ReferenceCuratedSource}, {@code GoogleCustomSearchAdapter})
 * fetch the page HTML and hand it here; this adapter runs the stack and translates the canonical
 * {@code recipe.extraction.ParsedRecipe} into discovery's {@link ParsedRecipe} DTO.
 *
 * <p><b>Behaviour preserved across the refactor (discovery-1 contract):</b>
 *
 * <ul>
 *   <li><b>JSON-LD only.</b> Calls {@code RecipeExtractionService.extractJsonLdOnly} — the legacy
 *       {@code JsonLdRecipeExtractor} read no microdata / selectors, and the runner relies on
 *       JSON-LD's high reliability for unattended ingest. A page with no JSON-LD Recipe is a miss
 *       ({@link Optional#empty()}).
 *   <li><b>{@code ingredientMappingKey}</b> is the canonical normalised display line ({@code
 *       IngredientMappingKeys.normalise}) — never null, satisfying the NOT-NULL column + nutrition
 *       mapping.
 *   <li><b>Fixed {@code extractionMethod = "json_ld"}</b> and <b>{@code extractionConfidence =
 *       0.85}</b>.
 *   <li>Quantity / unit / preparation are not parsed in v1 (display line only), matching the
 *       as-built extractor.
 * </ul>
 */
@Component
public class JsonLdRecipeExtractor {

  private static final String EXTRACTION_METHOD = "json_ld";
  private static final BigDecimal CONFIDENCE = BigDecimal.valueOf(0.85);

  private final RecipeExtractionService extractionService;

  @Autowired
  public JsonLdRecipeExtractor(RecipeExtractionService extractionService) {
    this.extractionService = extractionService;
  }

  /**
   * Convenience constructor that builds the shared {@link RecipeExtractionService} from default
   * layer instances. Used by unit/IT tests that previously instantiated the extractor with just an
   * {@code ObjectMapper}; Spring uses the {@code RecipeExtractionService}-arg constructor with the
   * autowired bean.
   */
  public JsonLdRecipeExtractor(ObjectMapper objectMapper) {
    this(RecipeExtractionServices.defaultService(objectMapper));
  }

  /**
   * Extract a {@code schema.org/Recipe} from the page's JSON-LD via the shared service.
   *
   * @return the mapped {@link ParsedRecipe}, or {@link Optional#empty()} when no JSON-LD Recipe was
   *     found (the caller throws {@code ExtractionFailedException}).
   */
  public Optional<ParsedRecipe> extract(String html, String url) {
    if (html == null || html.isBlank()) {
      return Optional.empty();
    }
    // JSON-LD-only, no completeness gate — the discovery-1 contract (tolerates a Recipe missing
    // ingredients / instructions; the runner's confidence + hard-constraint checks gate quality).
    Optional<com.example.mealprep.recipe.extraction.ParsedRecipe> extracted =
        extractionService.extractJsonLdOnly(new ExtractionInput.FromHtml(url, html));
    return extracted.map(r -> toDiscoveryParsed(r, url));
  }

  private ParsedRecipe toDiscoveryParsed(
      com.example.mealprep.recipe.extraction.ParsedRecipe r, String url) {
    List<ParsedIngredient> ingredients = new ArrayList<>();
    for (var ingredient : r.ingredients()) {
      String line = ingredient.rawLine();
      // The ingredient_mapping_key MUST be non-null (NOT-NULL column + nutrition mapping), so
      // derive
      // the deterministic v1 fallback from the display line via the canonical cross-module
      // normaliser. The extraction layer already trims + drops blanks, so `line` is non-blank.
      ingredients.add(
          new ParsedIngredient(
              line, IngredientMappingKeys.normalise(line), null, null, null, false));
    }

    List<ParsedMethodStep> method = new ArrayList<>();
    for (var step : r.methodSteps()) {
      method.add(new ParsedMethodStep(step.stepNumber(), step.instruction(), null));
    }

    // recipeCategory/keywords ignored in v1; nutrition discarded (LLD §Layer 1).
    ParsedRecipeMetadata metadata =
        new ParsedRecipeMetadata(
            r.servings(),
            r.prepTimeMinutes(),
            r.cookTimeMinutes(),
            r.totalTimeMinutes(),
            List.of(),
            r.cuisine(),
            List.of());

    return new ParsedRecipe(
        url,
        r.name(),
        r.description(),
        ingredients,
        method,
        metadata,
        EXTRACTION_METHOD,
        CONFIDENCE);
  }
}
