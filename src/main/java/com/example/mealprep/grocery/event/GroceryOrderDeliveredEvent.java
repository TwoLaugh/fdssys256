package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an order is marked delivered (grocery-01e). Per lld/grocery.md line 822. {@code
 * outstandingProposalsCount} is the number of substitution proposals left {@code
 * pending_user_review} at delivery — reconciliation is blocked until they are resolved
 * (grocery-01f).
 */
public record GroceryOrderDeliveredEvent(
    UUID userId,
    UUID groceryOrderId,
    int outstandingProposalsCount,
    UUID traceId,
    Instant occurredAt)
    implements GroceryOrderLifecycleEvent {}
