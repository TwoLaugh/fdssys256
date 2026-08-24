package com.example.mealprep.discovery.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of a G06 graph-batch ingest run — returned as the HTTP response AND (for runs that reach
 * the import phase) written to {@code <batchPath>/ingest_report.json} so the boundary-side
 * comparison harness (G08) and the artifact registry conventions (G19) can consume it without DB
 * access.
 *
 * <p>{@code status} vocabulary:
 *
 * <ul>
 *   <li>{@code OK} — the batch was processed (individual dishes may still be rejected).
 *   <li>{@code DISABLED} — {@code mealprep.graph.import.enabled=false}; nothing read or written.
 *   <li>{@code INVALID_BATCH} — path/manifest/verdict-contract violation; zero writes. {@code
 *       errors} carries the reasons (incl. the G09 {@code manifest_sha256} replay refusal).
 *   <li>{@code ABORTED_MISSING_KEYS} — pre-flight failed: at least one {@code ingredientMappingKey}
 *       does not resolve in {@code IngredientMapping} (G05 ordering violated); zero writes; {@code
 *       missingMappingKeys} names them.
 *   <li>{@code REJECTED_RESTRICTED_DIET} — the G11 Nadia gate is closed and an approved dish
 *       certifies {@code vegan}/{@code gluten_free}; zero writes.
 * </ul>
 */
public record GraphBatchIngestReport(
    String batchId,
    String jobId,
    String status,
    int created,
    int dedupSkipped,
    int notApproved,
    List<RejectedDish> rejected,
    List<IngestedDish> recipeIds,
    List<String> missingMappingKeys,
    List<String> errors,
    String note) {

  public static final String STATUS_OK = "OK";
  public static final String STATUS_DISABLED = "DISABLED";
  public static final String STATUS_INVALID_BATCH = "INVALID_BATCH";
  public static final String STATUS_ABORTED_MISSING_KEYS = "ABORTED_MISSING_KEYS";
  public static final String STATUS_REJECTED_RESTRICTED_DIET = "REJECTED_RESTRICTED_DIET";

  /** Per-dish fail-closed rejection; the batch continues. */
  public record RejectedDish(String fp, String reason) {}

  /**
   * A dish that reached {@code saveImportedRecipe}: newly created or fingerprint-dedup resumed.
   * {@code nutritionStatus} is the recipe's status after import ({@code PENDING} until G07 wires
   * the recompute trigger).
   */
  public record IngestedDish(String fp, UUID recipeId, UUID versionId, String nutritionStatus) {}
}
