package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.IntakeSource;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/nutrition/intake/{date}/snacks}.
 *
 * <p>{@code deductFromPantry} is reserved for nutrition-01l (cross-module pantry-deduct hook). 01b
 * accepts the flag in the contract but treats it as a no-op (see ticket §LLD divergence #2).
 */
public record LogSnackRequest(
    @NotBlank @Size(min = 1, max = 255) String freeText,
    @Size(max = 255) String ingredientMappingKey,
    @NotNull @Min(0) BigDecimal quantityG,
    @NotNull @Min(0) Integer calories,
    @NotNull @Min(0) BigDecimal proteinG,
    @NotNull @Min(0) BigDecimal carbsG,
    @NotNull @Min(0) BigDecimal fatG,
    @Min(0) BigDecimal fibreG,
    JsonNode micros,
    @NotNull IntakeSource source,
    Boolean deductFromPantry) {}
