package com.example.mealprep.grocery.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read shape for a Tier-1 shopping list. Per lld/grocery.md lines 387-394.
 *
 * <p>DIVERGENCE (ticket 01a, locked): {@code planGeneration} (int) — renamed from the LLD's {@code
 * planRevision} to track the planner's {@code generation} counter.
 */
public record ShoppingListDto(
    UUID id,
    UUID userId,
    UUID householdId,
    UUID planId,
    int planGeneration,
    Instant generatedAt,
    Instant supersededAt,
    Integer estimatedTotalPence,
    String estimatedTotalCurrency,
    BigDecimal costConfidence,
    int staleIngredientCount,
    boolean pantryTrackingEnabled,
    String notes,
    List<ShoppingListLineDto> lines,
    long version) {}
