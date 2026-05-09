package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Single micronutrient target. {@code targetValue} and {@code upperLimit} are independently
 * nullable — some nutrients carry only one bound.
 */
public record MicroTargetDto(
    @NotBlank @Size(max = 48) String nutrientKey,
    @DecimalMin("0.0") BigDecimal targetValue,
    @DecimalMin("0.0") BigDecimal upperLimit,
    @Size(max = 24) String sourcePreference,
    @Size(max = 255) String notes) {}
