package com.example.mealprep.planner.api.dto;

import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.time.Instant;
import java.util.List;

/**
 * Frozen recipe pool passed into a single beam-search run. Loaded once by the composer (01j) per
 * LLD §Concurrency line 1327 — "loaded once and pinned." Concurrent recipe edits during the search
 * are caught at slot-rendering time (planner-01j), not here.
 *
 * <p>{@code generatedAt} timestamps when the snapshot was built; the composer compares against
 * {@code Plan.createdAt} on persist to detect inflight staleness.
 */
public record RecipePoolSnapshot(List<RecipeDto> recipes, Instant generatedAt) {}
