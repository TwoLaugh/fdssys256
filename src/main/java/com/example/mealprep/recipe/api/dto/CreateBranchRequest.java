package com.example.mealprep.recipe.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/recipes/{recipeId}/branches}. Per LLD §CreateBranchRequest
 * (lines 406-411).
 *
 * <p>{@code name} is restricted to {@code [a-z0-9-]+} so it can also serve as a slug; uniqueness is
 * scoped to the recipe (DB constraint from 01a's migration). {@code branchPointVersionId} must
 * resolve to a version belonging to the parent recipe; cross-recipe references reject with 422
 * {@code recipe-branch-point-invalid}. {@code fingerprintOverride} is nullable — when null, the
 * server derives a minimal fingerprint from the v1 body.
 */
public record CreateBranchRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9-]+") String name,
    @Size(max = 120) String label,
    @NotBlank @Size(max = 2000) String reason,
    @NotNull UUID branchPointVersionId,
    @NotNull @Valid CreateRecipeBodyRequest body,
    @Valid CharacterFingerprintDto fingerprintOverride) {}
