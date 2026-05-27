package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published ONCE per Tier-2 bulk mark-bought operation (grocery-01d) — NOT per line. Per
 * lld/grocery.md line 899 ("one event per user-initiated operation", technical-architecture §Event
 * debouncing). Emitted in-transaction via {@code ApplicationEventPublisher}; consumers listen
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so it effectively fires after commit
 * (the project-wide convention — see {@code PriceObservedEvent}).
 *
 * <p>{@code lineIds} is the set of lines marked bought in the batch; {@code totalSpendPence} is the
 * user-supplied total (null when each line carried its own estimate / no price); {@code
 * householdId} may be null in single-user mode. The per-line {@code PriceObservedEvent}s are still
 * published individually by the writer (one per observation) — this event is the batch summary, not
 * a replacement for them.
 */
public record ShoppingListBulkMarkedBoughtEvent(
    UUID userId,
    UUID householdId,
    UUID shoppingListId,
    List<UUID> lineIds,
    Integer totalSpendPence,
    Instant occurredAt) {}
