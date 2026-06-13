package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a user reverts a {@code QUOTED} order back to {@code DRAFT} for re-editing
 * (grocery-provisions-p3-clarifications item 1 — the {@code back-to-draft} endpoint). The audit
 * trail for the transition: {@code discardedQuotedTotalPence} carries the stale quote total that
 * was dropped (null when the quote never priced).
 */
public record GroceryOrderRevertedToDraftEvent(
    UUID userId,
    UUID groceryOrderId,
    Integer discardedQuotedTotalPence,
    UUID traceId,
    Instant occurredAt)
    implements GroceryOrderLifecycleEvent {}
