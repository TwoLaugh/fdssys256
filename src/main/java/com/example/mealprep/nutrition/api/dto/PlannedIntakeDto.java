package com.example.mealprep.nutrition.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;

/** Planned-side of an {@link IntakeSlotDto}. All fields nullable when no plan was pre-filled. */
public record PlannedIntakeDto(
    UUID recipeId,
    Integer calories,
    BigDecimal proteinG,
    BigDecimal carbsG,
    BigDecimal fatG,
    BigDecimal fibreG,
    JsonNode micros) {}
