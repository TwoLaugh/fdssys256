package com.example.mealprep.recipe.extraction;

import java.util.List;

/**
 * Records which layer of the extraction stack won and which were tried, per {@code
 * recipe-extraction-pipeline.md} §"The five-layer extraction stack". Pure data.
 *
 * @param winningLayer the layer that produced the returned {@link ParsedRecipe}
 * @param layersTried the layers attempted, in order — the winning layer is last
 * @param layerDetail the concrete sub-strategy within the winning layer, when finer than the layer
 *     itself. Layer 2 spans both schema.org microdata and the common-selector legacy fallback; this
 *     records {@code "microdata"} vs {@code "common_selectors"} so the recipe-import provenance row
 *     keeps the exact {@code extraction_method} string it stored pre-refactor. {@code null} when
 *     the layer needs no finer label (e.g. JSON-LD).
 */
public record ExtractionProvenance(
    ExtractionLayer winningLayer, List<ExtractionLayer> layersTried, String layerDetail) {

  public ExtractionProvenance {
    layersTried = layersTried == null ? List.of() : List.copyOf(layersTried);
  }

  public ExtractionProvenance(ExtractionLayer winningLayer, List<ExtractionLayer> layersTried) {
    this(winningLayer, layersTried, null);
  }
}
