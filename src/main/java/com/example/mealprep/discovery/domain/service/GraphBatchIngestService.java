package com.example.mealprep.discovery.domain.service;

import com.example.mealprep.discovery.api.dto.GraphBatchIngestReport;

/**
 * G06 (graph integration): admin-invoked batch ingest of a spike-exported graph-batch directory
 * through the proven {@code RecipeWriteApi.saveImportedRecipe} seam (D4 option (a) — no discovery
 * source registration, no quotas; the human review queue is the throttle).
 *
 * <p>Everything is additive and fail-closed: the engine validates almost nothing on import, so
 * every graph-dish guarantee (mealTypes vocabulary, equipment catalogue membership, unit=g,
 * servings=1, key seeding, review-verdict binding, restricted-diet policy) lives in this runner —
 * or nowhere.
 */
public interface GraphBatchIngestService {

  GraphBatchIngestReport ingest(String batchPath);
}
