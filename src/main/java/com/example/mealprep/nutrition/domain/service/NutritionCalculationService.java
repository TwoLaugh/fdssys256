package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;

/**
 * In-process calculation surface for recipe-version nutrition. Verbatim from LLD lines 746-752.
 *
 * <p>Both methods are pure — they read the {@code IngredientMapping} cache and return a per-serving
 * {@link RecipeNutritionResultDto} without writing anywhere. The recipe-side write happens through
 * {@code RecipeNutritionWriter} (an outbound SPI) — the caller (or the {@code RecipeUpdatedEvent}
 * listener / manual-recalc controller) invokes the SPI with the returned DTO.
 *
 * <p>{@link #calculateRecipeNutrition} and {@link #recalculateForEvolvedRecipe} are functionally
 * identical; they are split per LLD for log / observability clarity — the recalc path runs through
 * the second name so listener logs can be filtered.
 */
public interface NutritionCalculationService {

  /** Save-time path: called inline by the recipe module when persisting a new version. */
  RecipeNutritionResultDto calculateRecipeNutrition(CalculateRecipeNutritionRequest request);

  /** Listener / manual-recalc path: same body, separate method name for log filtering. */
  RecipeNutritionResultDto recalculateForEvolvedRecipe(CalculateRecipeNutritionRequest request);
}
