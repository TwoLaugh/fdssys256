package com.example.mealprep.recipe.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of a {@code RecipeBranch}. 01b ships the simplified record (no {@code
 * characterFingerprint}); recipe-01d will reintroduce that field when fingerprint population lands
 * alongside non-main branch creation.
 */
public record RecipeBranchDto(
    UUID id,
    UUID recipeId,
    UUID parentBranchId,
    UUID branchPointVersionId,
    String name,
    String label,
    String reason,
    int currentVersion,
    BigDecimal divergenceScore,
    Instant createdAt,
    String createdByActor,
    UUID adapterTraceId,
    long version) {}
