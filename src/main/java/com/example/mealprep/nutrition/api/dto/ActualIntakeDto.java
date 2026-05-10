package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

/** Actual-side of an {@link IntakeSlotDto}. {@code status} drives interpretation of the rest. */
public record ActualIntakeDto(
    IntakeSlotStatus status,
    Integer calories,
    BigDecimal proteinG,
    BigDecimal carbsG,
    BigDecimal fatG,
    BigDecimal fibreG,
    JsonNode micros,
    String overrideFreeText,
    Instant overriddenAt,
    boolean needsAiParse) {}
