package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.validation.ValidIngredientList;
import com.example.mealprep.recipe.validation.ValidMethodSteps;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Body sub-block of a {@link CreateBranchRequest} — the branch's v1 version body. Same shape as
 * {@link CreateRecipeRequest} minus the recipe-root fields ({@code name} / {@code description}) —
 * the branch inherits its parent recipe's name/description; per-branch differentiation lives on
 * {@code RecipeBranch.label}.
 */
public record CreateRecipeBodyRequest(
    @NotEmpty @Valid @ValidIngredientList List<CreateIngredientRequest> ingredients,
    @NotEmpty @Valid @ValidMethodSteps List<CreateMethodStepRequest> method,
    @NotNull @Valid CreateRecipeMetadataRequest metadata,
    @Valid CreateRecipeTagsRequest tags) {}
