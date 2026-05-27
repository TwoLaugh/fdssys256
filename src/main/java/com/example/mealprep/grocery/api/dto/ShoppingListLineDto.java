package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.domain.entity.BoughtVia;
import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.ShoppingListLineType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read shape for a Tier-1 shopping-list line. Per lld/grocery.md lines 396-406. */
public record ShoppingListLineDto(
    UUID id,
    String ingredientMappingKey,
    String displayName,
    BigDecimal requestedQuantity,
    String requestedUnit,
    Integer suggestedPackSizeG,
    Integer suggestedPackCount,
    String suggestedPackUnit,
    ShoppingListLineType lineType,
    String qualityNotes,
    Integer estimatedUnitPence,
    Integer estimatedLinePence,
    BigDecimal estimatedConfidence,
    boolean isStaleEstimate,
    LineFulfilmentStatus fulfilmentStatus,
    BigDecimal boughtQuantity,
    String boughtUnit,
    Integer boughtPricePence,
    Instant boughtAt,
    BoughtVia boughtVia,
    UUID groceryOrderId) {}
