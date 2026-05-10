package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.IntakeSource;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read shape of a single snack row. */
public record IntakeSnackDto(
    UUID id,
    String ingredientMappingKey,
    String freeText,
    BigDecimal quantityG,
    int calories,
    BigDecimal proteinG,
    BigDecimal carbsG,
    BigDecimal fatG,
    BigDecimal fibreG,
    JsonNode micros,
    IntakeSource source,
    Instant loggedAt) {}
