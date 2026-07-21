package com.example.mealprep.discovery.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response of the G11 graph-batch withdraw/restore admin endpoints ({@code POST
 * /api/v1/discovery/admin/graph-batches/{jobId}/withdraw|restore}).
 *
 * <ul>
 *   <li>{@code action} — {@code WITHDRAWN} or {@code RESTORED}.
 *   <li>{@code matched} — recipes whose AI_GENERATED import row carries the jobId (batch size as
 *       the engine sees it). Zero matches never reaches this DTO — the endpoint 404s instead.
 *   <li>{@code changedRecipeIds} — rows actually transitioned by this call; empty on a repeat call
 *       (idempotency signal).
 *   <li>{@code skippedRecipeIds} — matched rows deliberately left alone (soft-deleted, or promoted
 *       into a user's own catalogue — an explicit adoption is not clawed back).
 * </ul>
 */
public record GraphBatchWithdrawReport(
    UUID jobId,
    String action,
    int matched,
    List<UUID> changedRecipeIds,
    List<UUID> skippedRecipeIds) {

  public static final String ACTION_WITHDRAWN = "WITHDRAWN";
  public static final String ACTION_RESTORED = "RESTORED";
}
