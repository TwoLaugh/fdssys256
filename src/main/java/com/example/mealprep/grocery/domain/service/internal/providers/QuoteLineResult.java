package com.example.mealprep.grocery.domain.service.internal.providers;

import com.example.mealprep.grocery.domain.entity.OrderLineStatus;

/**
 * Per-line outcome of {@link GroceryProvider#quote}. Per lld/grocery.md line 663. {@code
 * quotedUnitPence} is per pack; {@code packCountResolved} is the count the provider could resolve.
 */
public record QuoteLineResult(
    OrderLineStatus status,
    String resolvedProviderProductId,
    Integer quotedUnitPence,
    Integer packCountResolved,
    String note) {}
