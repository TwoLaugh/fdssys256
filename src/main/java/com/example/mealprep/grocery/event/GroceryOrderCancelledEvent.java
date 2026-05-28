package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an order is cancelled (grocery-01e). Per lld/grocery.md line 824. {@code reason}
 * carries the cancel reason (user-supplied or a system reason like {@code
 * provider_unavailable_24h}).
 */
public record GroceryOrderCancelledEvent(
    UUID userId, UUID groceryOrderId, String reason, UUID traceId, Instant occurredAt)
    implements GroceryOrderLifecycleEvent {}
