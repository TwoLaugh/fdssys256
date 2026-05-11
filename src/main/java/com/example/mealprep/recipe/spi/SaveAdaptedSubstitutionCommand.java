package com.example.mealprep.recipe.spi;

import com.example.mealprep.recipe.api.dto.MethodOverlayLineRequest;
import com.example.mealprep.recipe.api.dto.SubstitutionItemRequest;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import java.util.List;
import java.util.UUID;

/**
 * Command to {@link RecipeWriteApi#saveAdaptedSubstitution} — persist a substitution proposed by
 * the Adaptation Pipeline. The 01e flow handles validation (original ingredient must be on the
 * version). State is set to {@code ACCEPTED} + {@code adapterTraceId} populated. Per LLD lines
 * 602-622.
 */
public record SaveAdaptedSubstitutionCommand(
    UUID recipeId,
    UUID versionId,
    SubstitutionItemRequest original,
    SubstitutionItemRequest substitute,
    SubstitutionReason reason,
    String constraintRef,
    List<MethodOverlayLineRequest> methodOverlay,
    String notes,
    boolean temporary,
    UUID adapterTraceId) {}
