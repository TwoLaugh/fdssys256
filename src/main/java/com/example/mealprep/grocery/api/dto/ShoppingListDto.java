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
 *
 * <p>{@code estimatedTotalMinPence}/{@code estimatedTotalMaxPence} carry the list-level cost band
 * (grocery-cost-variance ticket): summed per-line min/max from the price-history aggregates, with
 * point estimates standing in for lines whose aggregate has no range. All three totals are null
 * together when no line has any price data (cold start). Invariant: {@code min ≤ estimatedTotal ≤
 * max} whenever non-null.
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
    Integer estimatedTotalMinPence,
    Integer estimatedTotalMaxPence,
    String estimatedTotalCurrency,
    BigDecimal costConfidence,
    int staleIngredientCount,
    boolean pantryTrackingEnabled,
    String notes,
    List<ShoppingListLineDto> lines,
    long version) {}
