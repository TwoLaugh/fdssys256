package com.example.mealprep.nutrition.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request body for {@code POST /api/v1/nutrition/intake/{date}/slots/{mealSlot}/edit}. */
public record IntakeEntryDto(
    @NotNull @Min(0) Integer calories,
    @NotNull @Min(0) BigDecimal proteinG,
    @NotNull @Min(0) BigDecimal carbsG,
    @NotNull @Min(0) BigDecimal fatG,
    @Min(0) BigDecimal fibreG,
    JsonNode micros) {}
