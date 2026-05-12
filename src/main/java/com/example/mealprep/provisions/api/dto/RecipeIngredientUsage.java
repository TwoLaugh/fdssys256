package com.example.mealprep.provisions.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One ingredient line on a {@link CookEventCommand}. {@code ingredientMappingKey} keys the
 * inventory rows the deduction engine targets; {@code quantity} + {@code unit} declare how much to
 * subtract (in canonical units — see LLD line 609; the {@code UnitConverter} helper ships in a
 * follow-up).
 */
public record RecipeIngredientUsage(
    @NotBlank @Size(max = 128) String ingredientMappingKey,
    @NotNull @PositiveOrZero BigDecimal quantity,
    @NotBlank @Size(max = 16) String unit) {}
