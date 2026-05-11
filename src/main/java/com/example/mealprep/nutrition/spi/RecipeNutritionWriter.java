package com.example.mealprep.nutrition.spi;

import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import java.util.UUID;

/**
 * Outbound SPI: persist per-serving nutrition to a recipe version. Defined by the nutrition module;
 * implemented by the recipe module (recipe-01f wires the real impl that delegates to {@code
 * RecipeWriteApi.updateNutritionStatus}). Until that impl ships, {@code
 * NoopRecipeNutritionWriterConfiguration} provides a logging fallback so the calc service runs
 * cleanly.
 *
 * <p>Implementations open their own write transaction — the calc service is read-only and does not
 * enrol the writer in any pre-existing tx.
 */
public interface RecipeNutritionWriter {

  /**
   * Persist computed per-serving nutrition to the named recipe version. Implementations MUST be
   * idempotent — re-invoking with the same {@code (versionId, result)} pair MUST produce no
   * observable side effect beyond the first call.
   */
  void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result);
}
