package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /substitutions/{subId}/reject}. The optional {@code reason} is
 * audit-logged but not persisted on the substitution row (no {@code rejection_reason} column).
 */
public record RejectSubstitutionRequest(
    @Min(0) long expectedVersion, @Size(max = 255) String reason) {}
