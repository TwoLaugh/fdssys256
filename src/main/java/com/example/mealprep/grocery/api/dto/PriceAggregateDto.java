package com.example.mealprep.grocery.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tier-4 aggregate read — the {@code (estimate, confidence)} contract the planner's cost sub-score
 * and Tier-1 cost projection consume. Per lld/grocery.md lines 456-461. {@code store == null} → a
 * cross-store aggregate.
 */
public record PriceAggregateDto(
    String ingredientMappingKey,
    String store,
    Integer pointEstimatePence,
    BigDecimal confidence,
    Integer minPence,
    Integer maxPence,
    Instant minObservedAt,
    Instant maxObservedAt,
    Instant lastSeenAt,
    int sampleCount,
    boolean isStale) {}
