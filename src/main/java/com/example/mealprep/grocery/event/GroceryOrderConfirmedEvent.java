package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the user confirms an order in the provider UI (grocery-01e), on {@code
 * markUserConfirmed}. Lifecycle/notification signal only — there is NO provisions listener that
 * consumes it.
 *
 * <p>CROSS-MODULE CONTRACT (reconciled in the provisions conformance sweep, 2026-05): inventory is
 * NOT added on this event. Provisions inventory is added by grocery's own {@code
 * OrderReconciler.addInventory} at <b>reconcile</b> time (not confirm) via a direct, idempotent
 * {@code ProvisionUpdateService.applyGroceryOrder} call — see {@code OrderReconciler} ("inventory
 * at reconcile, not confirm"). The earlier design that had a dormant provisions {@code
 * GroceryOrderConfirmedListener} consume this event was removed in that sweep: a confirm-time
 * inventory add would double-add against the reconcile-time path. This event remains published as a
 * lifecycle/notification fan-out point (a future notification listener may subscribe); it is no
 * longer an inventory-write seam.
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
