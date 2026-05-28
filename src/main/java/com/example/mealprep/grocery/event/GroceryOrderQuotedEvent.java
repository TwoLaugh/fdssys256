package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a successful {@code quote} (grocery-01e). Per lld/grocery.md line 819. {@code
 * observationsWritten} is the count of {@code QUOTE} price observations written (one per quoted
 * line) — feeds Tier-4 freshness signals.
 */
public record GroceryOrderQuotedEvent(
    UUID userId,
    UUID groceryOrderId,
    Integer quotedTotalPence,
    int observationsWritten,
    UUID traceId,
    Instant occurredAt)
    implements GroceryOrderLifecycleEvent {}
