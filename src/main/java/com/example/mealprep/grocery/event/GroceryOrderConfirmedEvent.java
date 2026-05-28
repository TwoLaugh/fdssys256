package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the user confirms an order in the provider UI (grocery-01e). Per lld/grocery.md
 * lines 821 / 910 — <b>the event Provisions consumes</b> to add items to inventory.
 *
 * <p>CROSS-MODULE CONTRACT: shipping this record activates the dormant provisions {@code
 * GroceryOrderConfirmedListener} ({@code @ConditionalOnClass(name =
 * "...grocery.event.GroceryOrderConfirmedEvent")}, shipped dormant in provisions-01h). The listener
 * fetches the order detail via {@code GroceryOrderService.getById} to build its import command;
 * whether inventory is added on confirm vs reconcile is grocery-01f's decision. 01e's only job is
 * to publish this record on {@code markUserConfirmed}.
 */
public record GroceryOrderConfirmedEvent(
    UUID userId,
    UUID groceryOrderId,
    Integer confirmedTotalPence,
    Instant deliverySlotStart,
    Instant deliverySlotEnd,
    UUID traceId,
    Instant occurredAt)
    implements GroceryOrderLifecycleEvent {}
