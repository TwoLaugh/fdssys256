package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Single micronutrient target. {@code targetValue} and {@code upperLimit} are independently
 * nullable — some nutrients carry only one bound.
 *
 * <p>{@code isHardFloor} (LLD lines 774-776) controls whether this micro participates in the
 * planner's multiplicative hard-floor gate. Micros default to {@code false} (warning-only); the
 * user can toggle a specific micro (iron in pregnancy, B12 for vegans, vitamin D in winter) to
 * {@code true} so it kills a candidate plan that breaches its {@code targetValue}.
 */
public record MicroTargetDto(
    @NotBlank @Size(max = 48) String nutrientKey,
    @DecimalMin("0.0") BigDecimal targetValue,
    @DecimalMin("0.0") BigDecimal upperLimit,
    @Size(max = 24) String sourcePreference,
    @Size(max = 255) String notes,
    boolean isHardFloor) {}
