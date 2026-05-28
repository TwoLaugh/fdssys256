package com.example.mealprep.grocery.domain.service.internal.providers;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a {@link BasketDraft}. Per lld/grocery.md line 661. {@code
 * preferredProviderProductId} is the SKU sourced from a prior order for the same {@code
 * (householdId, key)} — a hint the provider may honour when resolving the basket item.
 */
public record BasketDraftLine(
    UUID groceryOrderLineId,
    String ingredientMappingKey,
    String displayName,
    BigDecimal quantity,
    String unit,
    Integer packSizeG,
    Integer packCountRequested,
    String preferredProviderProductId) {}
