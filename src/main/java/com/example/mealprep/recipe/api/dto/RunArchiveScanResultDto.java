package com.example.mealprep.recipe.api.dto;

/**
 * Response body of {@code POST /api/v1/recipes/admin/run-archive-scan}. Reports how many
 * SYSTEM-catalogue rows were transitioned to {@code archived_at != null} during the run. Per LLD
 * line 656 + recipe-01g ticket §Admin trigger.
 */
public record RunArchiveScanResultDto(int flaggedCount) {}
