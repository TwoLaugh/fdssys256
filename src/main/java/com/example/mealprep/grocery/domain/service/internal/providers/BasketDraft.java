package com.example.mealprep.grocery.domain.service.internal.providers;

import java.util.List;
import java.util.UUID;

/**
 * Input to {@link GroceryProvider#quote} / {@link GroceryProvider#placeOrder}. Per lld/grocery.md
 * line 660. Built from a {@code GroceryOrder} + lifestyle preferences by {@code
 * BasketDraftAssembler}; the lines are a 1:1 snapshot of the order lines.
 */
public record BasketDraft(
    UUID groceryOrderId,
    UUID userId,
    List<BasketDraftLine> lines,
    BasketDraftPreferences preferences) {}
