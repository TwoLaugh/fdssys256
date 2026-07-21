package com.example.mealprep.recipe.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of a bulk archive/unarchive keyed on {@code recipe_imports.job_id} (G11 graph-batch
 * withdraw seam, {@code RecipeUpdateService.archiveByImportJobId} / {@code
 * unarchiveByImportJobId}).
 *
 * <ul>
 *   <li>{@code matchedRecipeIds} — every recipe id whose import row matched the (jobId, sourceType)
 *       pair, regardless of state. Empty means the job id is unknown for that provenance class.
 *   <li>{@code changedRecipeIds} — rows actually transitioned by this call. A repeat call returns
 *       the same {@code matchedRecipeIds} with an empty {@code changedRecipeIds} — the idempotency
 *       signal.
 *   <li>{@code skippedRecipeIds} — matched rows the operation deliberately left alone: soft-deleted
 *       recipes (archive state is meaningless on them) and rows no longer in the SYSTEM catalogue
 *       (a user promoted the dish into their own library — withdrawing future exposure is the goal,
 *       clawing back an explicit adoption is not).
 * </ul>
 */
public record ImportJobArchiveResult(
    List<UUID> matchedRecipeIds, List<UUID> changedRecipeIds, List<UUID> skippedRecipeIds) {}
