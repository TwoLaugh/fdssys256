package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.api.dto.UnmappedIngredientDto;

/**
 * Tagged-union return type of {@link IngredientMappingPipeline#resolve(IngredientLookupInput)}.
 * {@link Resolved} carries a populated DTO; {@link Unmapped} carries diagnostic info for the caller
 * to surface to the user / log.
 */
public sealed interface IngredientMappingResult
    permits IngredientMappingResult.Resolved, IngredientMappingResult.Unmapped {

  record Resolved(IngredientNutritionDto dto) implements IngredientMappingResult {}

  record Unmapped(UnmappedIngredientDto unmapped) implements IngredientMappingResult {}
}
