package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.validation.ValidIngredientList;
import com.example.mealprep.recipe.validation.ValidMethodSteps;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code PUT /api/v1/recipes/{recipeId}} (manual edit). Server side: writes a new
 * {@code RecipeVersion} on the recipe's current branch with {@code trigger = MANUAL_EDIT} and the
 * computed {@code change_diff}.
 *
 * <p>{@code expectedOptimisticVersion} guards against silently overwriting an in-flight pipeline
 * write. {@code tags} is optional (record component is nullable; absent maps to "no tags supplied"
 * and Jackson tolerates either absence or {@code null}).
 */
public record UpdateRecipeManualEditRequest(
    @NotBlank @Size(max = 160) String name,
    @Size(max = 2000) String description,
    @NotEmpty @Valid @ValidIngredientList List<CreateIngredientRequest> ingredients,
    @NotEmpty @Valid @ValidMethodSteps List<CreateMethodStepRequest> method,
    @NotNull @Valid CreateRecipeMetadataRequest metadata,
    @Valid CreateRecipeTagsRequest tags,
    @NotBlank @Size(max = 2000) String changeReason,
    @Min(0) long expectedOptimisticVersion) {}
