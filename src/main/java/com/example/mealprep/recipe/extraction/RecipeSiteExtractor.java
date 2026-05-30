package com.example.mealprep.recipe.extraction;

import java.util.Optional;

/**
 * Layer 3 SPI — a per-site extractor / post-processor, per {@code recipe-extraction-pipeline.md}
 * §"Layer 3 — Per-site extractor registry". Registered as Spring beans; {@link
 * RecipeExtractionService} indexes them by {@link #domainPattern()}.
 *
 * <p>v1 ships <b>zero</b> registered implementations — the registry mechanism is in scope, the
 * population is iterative as user reports surface site-specific bugs (LLD line 127). An empty
 * registry is the steady state today; the stack falls through Layer 3 to Layer 4/5.
 */
public interface RecipeSiteExtractor {

  /** The domain (or domain substring) this extractor handles, e.g. {@code "bbcgoodfood.com"}. */
  String domainPattern();

  /**
   * Optional pre-extraction. If this returns a present value, Layers 1-2 are skipped and the
   * returned recipe is used directly.
   */
  default Optional<ParsedRecipe> extractRaw(String url, String html) {
    return Optional.empty();
  }

  /**
   * Optional post-processing of a Layer 1/2 result — clean up known per-site issues. Default is
   * identity.
   */
  default ParsedRecipe postProcess(ParsedRecipe extracted, String url, String html) {
    return extracted;
  }
}
