package com.example.mealprep.provisions.api.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/provisions/meal-consumption}. Decrements a specific
 * inventory row by {@code portions}. Floor-at-zero applies. See LLD line 424.
 */
public record MealConsumptionCommand(
    @NotNull UUID inventoryItemId,
    @NotNull @PositiveOrZero BigDecimal portions,
    @Nullable UUID traceId) {}
