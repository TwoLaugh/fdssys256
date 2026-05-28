package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published once an order reconciles (grocery-01f owns the reconcile transition). Per
 * lld/grocery.md line 823. Carried here as part of the sealed family so the hierarchy is complete;
 * 01e does NOT publish it (reconciliation is grocery-01f).
 */
public record GroceryOrderReconciledEvent(
    UUID userId, UUID groceryOrderId, Integer paidTotalPence, UUID traceId, Instant occurredAt)
    implements GroceryOrderLifecycleEvent {}
