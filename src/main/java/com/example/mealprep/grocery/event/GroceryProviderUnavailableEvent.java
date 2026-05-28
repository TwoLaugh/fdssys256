package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Stand-alone (NOT lifecycle-family) availability event (grocery-01e). Per lld/grocery.md line 830.
 * Published when a {@code quote} / {@code place} hits {@code ProviderUnavailableException} and the
 * order moves to {@code PROVIDER_UNAVAILABLE}. The 24-hour retry-then-auto-cancel sweep that
 * consumes this is grocery-01g's scheduled concern; 01e only sets the state + publishes the event.
 */
public record GroceryProviderUnavailableEvent(
    UUID userId,
    UUID groceryOrderId,
    String providerKey,
    String reason,
    UUID traceId,
    Instant occurredAt) {}
