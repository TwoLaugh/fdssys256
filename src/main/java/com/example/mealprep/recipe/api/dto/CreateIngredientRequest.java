package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Single ingredient line in a {@code CreateRecipeRequest}. */
public record CreateIngredientRequest(
    @Min(0) int lineOrder,
    @NotBlank @Size(max = 160) String ingredientMappingKey,
    @NotBlank @Size(max = 160) String displayName,
    BigDecimal quantity,
    @Size(max = 16) String unit,
    @Size(max = 80) String preparation,
    Boolean optional) {}
