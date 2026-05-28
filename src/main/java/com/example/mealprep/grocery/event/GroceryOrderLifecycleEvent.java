package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sealed lifecycle-event family for a Tier-3 grocery order (grocery-01e). Per lld/grocery.md lines
 * 808-815. The "single-kind plain, multi-kind sealed" style rule: the lifecycle has many kinds, so
 * it is a sealed hierarchy; listeners receive subtype-specific records and may pattern-match.
 *
 * <p>Published via {@code ApplicationEventPublisher} after the relevant write transaction;
 * consumers listen {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. All events carry the
 * common accessors below.
 */
public sealed interface GroceryOrderLifecycleEvent
    permits GroceryOrderQuotedEvent,
        GroceryOrderPlacedEvent,
        GroceryOrderConfirmedEvent,
        GroceryOrderDeliveredEvent,
        GroceryOrderReconciledEvent,
        GroceryOrderCancelledEvent {

  UUID userId();

  UUID groceryOrderId();

  UUID traceId();

  Instant occurredAt();
}
