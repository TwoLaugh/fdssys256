package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.validation.ValidIngredientList;
import com.example.mealprep.recipe.validation.ValidMethodSteps;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request body for {@code POST /api/v1/recipes} ({@code manual_create} trigger). */
public record CreateRecipeRequest(
    @NotBlank @Size(max = 160) String name,
    @Size(max = 2000) String description,
    @NotEmpty @Valid @ValidIngredientList List<CreateIngredientRequest> ingredients,
    @NotEmpty @Valid @ValidMethodSteps List<CreateMethodStepRequest> method,
    @NotNull @Valid CreateRecipeMetadataRequest metadata,
    @Valid CreateRecipeTagsRequest tags) {}
