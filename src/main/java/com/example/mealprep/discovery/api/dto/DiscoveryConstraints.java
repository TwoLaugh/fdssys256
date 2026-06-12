package com.example.mealprep.discovery.api.dto;

import java.util.List;

/**
 * Schema-versioned JSONB document snapshotted at job-enqueue time. {@code schemaVersion} starts at
 * 1 and is bumped when the shape changes non-additively (style-guide §JSONB §Required discipline).
 * Frozen at enqueue so a constraint change mid-job does not retroactively alter the search.
 *
 * <p>{@code mustExcludeIngredientMappingKeys} carries the hard-constraint snapshot — never
 * softened, applied as a deterministic second-pass filter after extraction. <b>Server-unioned at
 * enqueue (ticket discovery-server-side-exclusions):</b> the caller's keys are additive only; the
 * enqueue path derives the user's hard-constraint exclusion set via the preference module's
 * published seam ({@code HardConstraintFilterService.exclusionKeySnapshot}, allergen derivatives
 * included) and persists {@code serverSnapshot ∪ clientKeys}, normalised per core-03. A client
 * omitting its user's allergens can therefore never widen results — only narrow them further.
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
