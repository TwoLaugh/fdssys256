package com.example.mealprep.discovery.api.dto;

import java.util.List;

/**
 * Schema-versioned JSONB document snapshotted at job-enqueue time. {@code schemaVersion} starts at
 * 1 and is bumped when the shape changes non-additively (style-guide §JSONB §Required discipline).
 * Frozen at enqueue so a constraint change mid-job does not retroactively alter the search.
 *
 * <p>{@code mustExcludeIngredientMappingKeys} carries the hard-constraint snapshot computed by the
 * caller (planner / pipeline) — never softened, applied as a deterministic second-pass filter after
 * extraction.
 *
 * <p>Per LLD lines 244-254.
 */
public record DiscoveryConstraints(
    int schemaVersion,
    List<String> requiredCuisines,
    List<String> requiredMealTypes,
    Integer maxTotalTimeMins,
    List<String> mustExcludeIngredientMappingKeys,
    List<String> dietaryFlags,
    List<String> preferenceHints,
    Integer maxRecipesPerSource) {}
