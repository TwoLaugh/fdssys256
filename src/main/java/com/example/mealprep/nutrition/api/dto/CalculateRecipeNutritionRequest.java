package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Input to {@code NutritionCalculationService#calculateRecipeNutrition} / {@code
 * recalculateForEvolvedRecipe}. Verbatim from LLD line 492.
 *
 * <p>The calc service is in-process — callers (recipe module direct call, recipe-event listener,
 * manual recalc REST) build this DTO from a {@code RecipeVersion}'s ingredient list.
 */
public record CalculateRecipeNutritionRequest(
    @NotNull UUID recipeId,
    @NotNull @Size(min = 1) @Valid List<RecipeIngredientLineDto> ingredients,
    @NotNull @Min(1) Integer servings) {}
