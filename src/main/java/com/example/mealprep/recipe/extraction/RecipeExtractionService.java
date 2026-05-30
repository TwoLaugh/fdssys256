package com.example.mealprep.recipe.extraction;

import java.util.Optional;

/**
 * Shared engine that turns page markup into a structured {@link ParsedRecipe}, per {@code
 * recipe-extraction-pipeline.md}. The single extraction path both consumers route through:
 *
 * <ul>
 *   <li>the recipe module's user-driven URL import ({@code RecipeServiceImpl.importFromUrl}), and
 *   <li>the discovery module's autonomous ingest ({@code DiscoverySource.fetchRecipe}
 *       implementations).
 * </ul>
 *
 * <p>Re-exported from the {@code recipe.extraction} package (public) so the discovery module can
 * inject it without depending on the recipe catalogue internals — the LLD's "facade re-exports
 * RecipeExtractionService" requirement (line 14). The {@code RecipeBoundaryTest} SPI rule does not
 * cover {@code recipe.extraction}, which is deliberately a published cross-module surface.
 *
 * <p><b>Five-layer stack.</b> {@link #extract} runs: per-site pre-extract (Layer 3) → JSON-LD
 * (Layer 1) → microdata / common-selectors (Layer 2) → per-site post-process (Layer 3) → validator
 * (Layer 5). The first layer that yields a recipe passing the validator wins. Layer 4 (AI HTML
 * extraction) is reserved — its prompt content is deferred to the prompt-engineering track (LLD
 * §"Out of Scope"), so no AI call is made in v1.
 */
public interface RecipeExtractionService {

  /**
   * Run the full layer stack against the input, gated by the Layer 5 validator.
   *
   * @return the extracted recipe (validator-sufficient: non-blank name + ≥1 ingredient + ≥1 step),
   *     or {@link Optional#empty()} when no layer produced a sufficient recipe. The recipe
   *     URL-import path uses this and translates empty into {@code RecipeImportFailureException}.
   */
  Optional<ParsedRecipe> extract(ExtractionInput input);

  /**
   * Run Layer 1 (JSON-LD) only, <b>without</b> the Layer 5 completeness gate. The discovery ingest
   * path uses this: it accepts only JSON-LD (the high-reliability format for unattended ingest) and
   * — per the discovery-1 contract — tolerates a Recipe object that is missing ingredients or
   * instructions (the runner's own confidence + hard-constraint checks handle quality downstream).
   *
   * <p>Sharing the same {@code JsonLdExtractionLayer} engine as {@link #extract} keeps the parsing
   * logic single-sourced; only the validation policy differs between the two consumers.
   *
   * @return the JSON-LD Recipe (possibly with empty ingredients / method), or {@link
   *     Optional#empty()} when the page has no JSON-LD Recipe block.
   */
  Optional<ParsedRecipe> extractJsonLdOnly(ExtractionInput input);
}
