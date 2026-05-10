package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import java.util.List;

/**
 * Internal carrier for the post-validation new version body, fed to {@link VersionDiffer}. Mirrors
 * the four sub-blocks of {@code CreateRecipeRequest}/{@code UpdateRecipeManualEditRequest}; we
 * accept the request DTOs verbatim so the differ doesn't depend on JPA entities.
 */
public record NewVersionInput(
    List<CreateIngredientRequest> ingredients,
    List<CreateMethodStepRequest> method,
    CreateRecipeMetadataRequest metadata,
    CreateRecipeTagsRequest tags) {}
