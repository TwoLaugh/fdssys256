package com.example.mealprep.recipe.spi;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import java.util.List;
import java.util.UUID;

/**
 * Command to {@link RecipeWriteApi#saveAdaptedBranch} — fork a recipe at a given branch-point
 * version. Persists a new {@code RecipeBranch} + v1 {@code RecipeVersion}. Per LLD lines 602-622.
 */
public record SaveAdaptedBranchCommand(
    UUID recipeId,
    UUID parentBranchId,
    UUID branchPointVersionId,
    String name,
    String label,
    String reason,
    List<CreateIngredientRequest> ingredients,
    List<CreateMethodStepRequest> method,
    CreateRecipeMetadataRequest metadata,
    CreateRecipeTagsRequest tags,
    CharacterFingerprintDto characterFingerprint,
    UUID adapterTraceId) {}
