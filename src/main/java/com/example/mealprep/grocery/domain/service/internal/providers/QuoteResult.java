package com.example.mealprep.grocery.domain.service.internal.providers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Result of {@link GroceryProvider#quote}. Per lld/grocery.md line 663. {@code lineResults} is
 * keyed by {@code groceryOrderLineId}; {@code quotedTotalPence} is the basket total the provider
 * quoted.
 */
public record QuoteResult(
    String providerOrderId,
    Map<UUID, QuoteLineResult> lineResults,
    Integer quotedTotalPence,
    String currency,
    Instant quotedAt) {}
