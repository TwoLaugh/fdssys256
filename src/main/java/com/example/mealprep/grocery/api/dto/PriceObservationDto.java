package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.domain.entity.PriceSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read shape for a Tier-4 price observation. Per lld/grocery.md lines 447-454. */
public record PriceObservationDto(
    UUID id,
    UUID userId,
    UUID householdId,
    String ingredientMappingKey,
    String store,
    String providerProductId,
    Integer packSizeG,
    Integer packCount,
    BigDecimal quantity,
    String quantityUnit,
    Integer paidUnitPence,
    Integer paidTotalPence,
    String currency,
    PriceSource source,
    BigDecimal confidenceWeight,
    UUID groceryOrderId,
    UUID shoppingListLineId,
    Instant observedAt,
    String note) {}
