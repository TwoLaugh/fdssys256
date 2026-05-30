package com.example.mealprep.recipe.extraction;

/**
 * Which layer of the extraction stack produced (or attempted) a result. Recorded in {@link
 * ExtractionProvenance} for debugging and per-domain reliability tracking, per {@code
 * recipe-extraction-pipeline.md} §"The five-layer extraction stack".
 *
 * <p>{@link #AI_HTML} is reserved: the AI fallback's prompt content is deferred to the
 * prompt-engineering track (LLD §"Out of Scope"), so v1 ships no AI extractor and this constant is
 * never the winning layer today. It is enumerated so provenance and the layer-order contract are
 * forward-compatible when that track lands.
 */
public enum ExtractionLayer {
  /** Layer 1 — {@code schema.org/Recipe} JSON-LD. The dominant format (~80% of major sites). */
  JSON_LD,
  /** Layer 2 — h-recipe / schema.org microdata. The legacy alternative. */
  MICRODATA,
  /** Layer 3 — per-site registered extractor / post-processor. Registry ships empty in v1. */
  PER_SITE,
  /** Layer 4 — cheap-tier LLM HTML extraction. Reserved; prompt content deferred. */
  AI_HTML
}
