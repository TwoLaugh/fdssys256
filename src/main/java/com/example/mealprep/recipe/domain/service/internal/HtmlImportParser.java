package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.exception.RecipeImportFailureException;
import com.example.mealprep.recipe.extraction.ExtractionInput;
import com.example.mealprep.recipe.extraction.RecipeExtractionService;
import com.example.mealprep.recipe.extraction.RecipeExtractionServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Recipe URL-import adapter over the shared {@link RecipeExtractionService} (the 5-layer pipeline
 * from {@code recipe-extraction-pipeline.md}). This class is the recipe module's view of the shared
 * engine: it runs the stack against the fetched HTML and translates the canonical {@code
 * recipe.extraction.ParsedRecipe} into the import path's existing {@link ParsedRecipe} carrier (the
 * one {@code ParsedRecipeToCreateRequestMapper} maps from), preserving every import outcome the
 * pre-refactor parser produced.
 *
 * <p><b>Behaviour preserved across the refactor:</b>
 *
 * <ul>
 *   <li>Strategies tried, in order: JSON-LD → microdata → common selectors (now Layers 1-2 of the
 *       shared stack).
 *   <li>The {@code extractionMethod} string the {@code recipe_imports} provenance row stores —
 *       {@code "json_ld"}, {@code "microdata"}, or {@code "common_selectors"} — mapped from the
 *       winning {@link ExtractionLayer}.
 *   <li>The hard-fail contract: when no layer yields a recipe with {@code name + ≥1 ingredient + ≥1
 *       method step}, throws {@link RecipeImportFailureException} with {@code failureReason =
 *       "no_extractor_matched"}.
 *   <li>The {@code rawPayload} provenance shape ({@code {"jsonLd": …}} JSON node).
 * </ul>
 */
@Component
public class HtmlImportParser {

  private final ObjectMapper objectMapper;
  private final RecipeExtractionService extractionService;

  @Autowired
  public HtmlImportParser(ObjectMapper objectMapper, RecipeExtractionService extractionService) {
    this.objectMapper = objectMapper;
    this.extractionService = extractionService;
  }

  /**
   * Convenience constructor that builds the shared {@link RecipeExtractionService} from default
   * layer instances. Used by unit tests that previously instantiated the parser directly with just
   * an {@code ObjectMapper}; Spring uses the two-arg constructor with the autowired service bean.
   */
  public HtmlImportParser(ObjectMapper objectMapper) {
    this(objectMapper, RecipeExtractionServices.defaultService(objectMapper));
  }

  public ParsedRecipe parse(String html, String url) {
    Optional<com.example.mealprep.recipe.extraction.ParsedRecipe> extracted =
        extractionService.extract(new ExtractionInput.FromHtml(url, html));
    if (extracted.isEmpty()) {
      throw new RecipeImportFailureException("no_extractor_matched");
    }
    return toImportParsed(extracted.get());
  }

  /** Map the canonical extraction shape onto the import path's {@link ParsedRecipe} carrier. */
  private ParsedRecipe toImportParsed(com.example.mealprep.recipe.extraction.ParsedRecipe r) {
    List<String> ingredientLines = new ArrayList<>();
    for (var ingredient : r.ingredients()) {
      ingredientLines.add(ingredient.rawLine());
    }
    List<String> methodSteps = new ArrayList<>();
    for (var step : r.methodSteps()) {
      methodSteps.add(step.instruction());
    }
    String extractionMethod = r.provenance() == null ? null : methodLabel(r.provenance());

    ObjectNode raw = objectMapper.createObjectNode();
    raw.put("extractionMethod", extractionMethod == null ? "" : extractionMethod);

    return new ParsedRecipe(
        r.name(),
        r.description(),
        ingredientLines,
        methodSteps,
        r.prepTimeMinutes(),
        r.cookTimeMinutes(),
        r.totalTimeMinutes(),
        r.servings(),
        r.cuisine(),
        extractionMethod,
        raw);
  }

  /**
   * The provenance {@code extraction_method} string each winning layer maps to — preserving the
   * exact values the import path stored pre-refactor ({@code json_ld} / {@code microdata} / {@code
   * common_selectors}). Layer 2 carries its sub-strategy in {@code layerDetail}.
   */
  private static String methodLabel(
      com.example.mealprep.recipe.extraction.ExtractionProvenance provenance) {
    return switch (provenance.winningLayer()) {
      case JSON_LD -> "json_ld";
      case MICRODATA -> provenance.layerDetail() != null ? provenance.layerDetail() : "microdata";
      case PER_SITE -> "per_site";
      case AI_HTML -> "ai_html";
    };
  }

  /** Strategy-agnostic parsed-recipe shape returned by the import adapter. */
  public record ParsedRecipe(
      String name,
      String description,
      List<String> ingredientLines,
      List<String> methodSteps,
      Integer prepMinutes,
      Integer cookMinutes,
      Integer totalMinutes,
      Integer servings,
      String cuisine,
      String extractionMethod,
      JsonNode rawPayload) {

    public ParsedRecipe withExtractionMethod(String m) {
      return new ParsedRecipe(
          name,
          description,
          ingredientLines,
          methodSteps,
          prepMinutes,
          cookMinutes,
          totalMinutes,
          servings,
          cuisine,
          m,
          rawPayload);
    }
  }
}
