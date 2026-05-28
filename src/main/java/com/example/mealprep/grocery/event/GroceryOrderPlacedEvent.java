package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after {@code placeOrder} drives the basket to checkout (grocery-01e). Per
 * lld/grocery.md line 820. {@code partial} is {@code true} for a {@code PLACED_PARTIAL} outcome;
 * {@code confirmLink} is the URL the user clicks to confirm in the provider's own UI.
 */
public record GroceryOrderPlacedEvent(
    UUID userId,
    UUID groceryOrderId,
    String confirmLink,
    boolean partial,
    UUID traceId,
    Instant occurredAt)
    implements GroceryOrderLifecycleEvent {}
