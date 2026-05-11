package com.example.mealprep.recipe.api.dto;

import java.math.BigDecimal;

/**
 * One side of a substitution (original or substitute) as returned on a {@link
 * RecipeSubstitutionDto}.
 */
public record SubstitutedItemDto(String ingredientMappingKey, BigDecimal quantity, String unit) {}
