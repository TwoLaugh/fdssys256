package com.example.mealprep.recipe.spi;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * Command to {@link RecipeWriteApi#saveAdaptedVersion} — persist a new {@code RecipeVersion} on the
 * supplied branch, race-checked against the supplied parent version expectations. Per LLD lines
 * 602-622.
 */
public record SaveAdaptedVersionCommand(
    UUID recipeId,
    UUID branchId,
    int expectedParentVersionNumber,
    UUID expectedParentVersionId,
    List<CreateIngredientRequest> ingredients,
    List<CreateMethodStepRequest> method,
    CreateRecipeMetadataRequest metadata,
    CreateRecipeTagsRequest tags,
    CharacterFingerprintDto characterFingerprint,
    JsonNode changeDiff,
    String changeReason,
    UUID adapterTraceId) {}
