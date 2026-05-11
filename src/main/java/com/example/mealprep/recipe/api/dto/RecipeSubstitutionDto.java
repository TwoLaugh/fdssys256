package com.example.mealprep.recipe.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read shape of a {@code RecipeSubstitution}. The {@code state} field reflects the four-state
 * machine described in {@link SubstitutionState}.
 */
public record RecipeSubstitutionDto(
    UUID id,
    UUID recipeId,
    UUID versionId,
    UUID branchId,
    SubstitutedItemDto original,
    SubstitutedItemDto substitute,
    SubstitutionReason reason,
    String constraintRef,
    List<MethodOverlayLineDto> methodOverlay,
    String notes,
    boolean temporary,
    int applicationCount,
    Instant lastAppliedAt,
    SubstitutionState state,
    UUID promotedToVersionId,
    Instant createdAt,
    String createdByActor,
    UUID adapterTraceId,
    long version) {}
