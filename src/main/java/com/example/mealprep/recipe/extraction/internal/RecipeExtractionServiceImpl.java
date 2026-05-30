package com.example.mealprep.recipe.extraction.internal;

import com.example.mealprep.recipe.extraction.ExtractionInput;
import com.example.mealprep.recipe.extraction.ExtractionLayer;
import com.example.mealprep.recipe.extraction.ExtractionProvenance;
import com.example.mealprep.recipe.extraction.ParsedRecipe;
import com.example.mealprep.recipe.extraction.RecipeExtractionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the five-layer extraction stack, per {@code recipe-extraction-pipeline.md} §"The
 * five-layer extraction stack". Each layer is a small, independently-unit-testable collaborator;
 * this class only sequences them and stamps {@link ExtractionProvenance}.
 *
 * <p>Order (first sufficient result wins):
 *
 * <ol>
 *   <li><b>Layer 3 pre-extract</b> — a registered per-site extractor may short-circuit Layers 1-2.
 *   <li><b>Layer 1</b> — JSON-LD ({@code schema.org/Recipe}).
 *   <li><b>Layer 2</b> — microdata / common-selectors.
 *   <li><b>Layer 3 post-process</b> — a registered per-site extractor may clean the Layer 1/2
 *       result.
 *   <li><b>Layer 4</b> — AI HTML extraction. Reserved; no AI call in v1 (prompt content deferred).
 *   <li><b>Layer 5</b> — validator gates the result (name + ≥1 ingredient + ≥1 step).
 * </ol>
 *
 * <p>v1 ships no registered {@link com.example.mealprep.recipe.extraction.RecipeSiteExtractor}, so
 * Layer 3 is a pass-through and the realised winning layers are JSON_LD or MICRODATA — exactly the
 * strategies the two pre-refactor extractors used, so import outcomes are preserved.
 */
@Service
public class RecipeExtractionServiceImpl implements RecipeExtractionService {

  private final JsonLdExtractionLayer jsonLdLayer;
  private final MicrodataExtractionLayer microdataLayer;
  private final PerSiteExtractorRegistry perSiteRegistry;
  private final ExtractionValidator validator;

  public RecipeExtractionServiceImpl(
      JsonLdExtractionLayer jsonLdLayer,
      MicrodataExtractionLayer microdataLayer,
      PerSiteExtractorRegistry perSiteRegistry,
      ExtractionValidator validator) {
    this.jsonLdLayer = jsonLdLayer;
    this.microdataLayer = microdataLayer;
    this.perSiteRegistry = perSiteRegistry;
    this.validator = validator;
  }

  @Override
  public Optional<ParsedRecipe> extract(ExtractionInput input) {
    if (input == null) {
      return Optional.empty();
    }
    String url = input.sourceUrl();
    String html = htmlOf(input);
    if (html == null || html.isBlank()) {
      return Optional.empty();
    }
    Document doc = Jsoup.parse(html, url == null ? "" : url);
    List<ExtractionLayer> tried = new ArrayList<>();

    // Layer 3 (pre-extract): a per-site extractor may bypass Layers 1-2 entirely.
    if (perSiteRegistry.hasMatch(url)) {
      tried.add(ExtractionLayer.PER_SITE);
      Optional<ParsedRecipe> pre = perSiteRegistry.preExtract(url, html);
      if (pre.isPresent()) {
        return finish(pre.get(), ExtractionLayer.PER_SITE, null, tried);
      }
    }

    // Layer 1: JSON-LD.
    tried.add(ExtractionLayer.JSON_LD);
    Optional<ParsedRecipe> jsonLd = jsonLdLayer.extract(doc, url);
    if (jsonLd.isPresent()) {
      ParsedRecipe processed = perSiteRegistry.postProcess(jsonLd.get(), url, html);
      Optional<ParsedRecipe> result = finish(processed, ExtractionLayer.JSON_LD, null, tried);
      if (result.isPresent()) {
        return result;
      }
    }

    // Layer 2: microdata / common-selectors.
    tried.add(ExtractionLayer.MICRODATA);
    Optional<MicrodataExtractionLayer.Result> microdata = microdataLayer.extract(doc, url);
    if (microdata.isPresent()) {
      ParsedRecipe processed = perSiteRegistry.postProcess(microdata.get().recipe(), url, html);
      Optional<ParsedRecipe> result =
          finish(processed, ExtractionLayer.MICRODATA, microdata.get().detail(), tried);
      if (result.isPresent()) {
        return result;
      }
    }

    // Layer 4 (AI) is reserved — no call in v1. Layer 5: nothing sufficient was produced.
    return Optional.empty();
  }

  @Override
  public Optional<ParsedRecipe> extractJsonLdOnly(ExtractionInput input) {
    if (input == null) {
      return Optional.empty();
    }
    String html = htmlOf(input);
    if (html == null || html.isBlank()) {
      return Optional.empty();
    }
    String url = input.sourceUrl();
    Document doc = Jsoup.parse(html, url == null ? "" : url);
    return jsonLdLayer
        .extract(doc, url)
        .map(
            r ->
                new ParsedRecipe(
                    r.sourceUrl(),
                    r.name(),
                    r.description(),
                    r.ingredients(),
                    r.methodSteps(),
                    r.prepTimeMinutes(),
                    r.cookTimeMinutes(),
                    r.totalTimeMinutes(),
                    r.servings(),
                    r.cuisine(),
                    new ExtractionProvenance(
                        ExtractionLayer.JSON_LD, List.of(ExtractionLayer.JSON_LD))));
  }

  /** Layer 5 gate + provenance stamp. Returns empty when the candidate fails validation. */
  private Optional<ParsedRecipe> finish(
      ParsedRecipe candidate,
      ExtractionLayer winning,
      String layerDetail,
      List<ExtractionLayer> tried) {
    if (!validator.isSufficient(candidate)) {
      return Optional.empty();
    }
    ParsedRecipe stamped =
        new ParsedRecipe(
            candidate.sourceUrl(),
            candidate.name(),
            candidate.description(),
            candidate.ingredients(),
            candidate.methodSteps(),
            candidate.prepTimeMinutes(),
            candidate.cookTimeMinutes(),
            candidate.totalTimeMinutes(),
            candidate.servings(),
            candidate.cuisine(),
            new ExtractionProvenance(winning, List.copyOf(tried), layerDetail));
    return Optional.of(stamped);
  }

  private static String htmlOf(ExtractionInput input) {
    if (input instanceof ExtractionInput.FromHtml fromHtml) {
      return fromHtml.html();
    }
    // FromUrl: v1 routes both live consumers through FromHtml (each owns its fetcher), so a bare
    // FromUrl carries no body for the layer stack to read. Documented in ExtractionInput.
    return null;
  }
}
