package com.example.mealprep.provisions.api.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/provisions/standalone-consumption}. Nutrition Logger path —
 * matches an active inventory row by {@code (userId, ingredientMappingKey)}. When {@code
 * userConfirmedDeduction == true}, decrements the oldest-expiry row by {@code quantity}. See LLD
 * line 425.
 */
public record StandaloneConsumptionCommand(
    @NotBlank @Size(max = 128) String ingredientMappingKey,
    @NotNull @PositiveOrZero BigDecimal quantity,
    @NotBlank @Size(max = 16) String unit,
    @NotNull Boolean userConfirmedDeduction,
    @Nullable UUID traceId) {}
