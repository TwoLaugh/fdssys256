package com.example.mealprep.recipe.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Propose a new substitution on a recipe version. Verbatim from LLD lines 413-419 ({@code
 * CreateSubstitutionRequest}).
 */
public record CreateSubstitutionRequest(
    @NotNull UUID versionId,
    @NotNull @Valid SubstitutionItemRequest original,
    @NotNull @Valid SubstitutionItemRequest substitute,
    @NotNull SubstitutionReason reason,
    @Size(max = 160) String constraintRef,
    @Valid List<MethodOverlayLineRequest> methodOverlay,
    @Size(max = 1000) String notes,
    boolean temporary) {}
