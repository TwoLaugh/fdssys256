package com.example.mealprep.grocery.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published once per Tier-1 shopping-list (re)generation (grocery-01b). Per lld/grocery.md line 827
 * / 873. Emitted in-transaction via {@code ApplicationEventPublisher}; consumers listen
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so it effectively fires after commit
 * (the project-wide convention — see {@code PriceObservedEvent}). No listeners ship in 01b; emitted
 * for downstream consumers (notification "your shopping list is ready", planner cost surfacing).
 *
 * <p>DIVERGENCE (ticket 01a/01b, locked): {@code planGeneration} (not the LLD's {@code
 * planRevision}) tracks the planner's {@code generation} counter. {@code householdId} may be null
 * in single-user mode.
 */
public record ShoppingListGeneratedEvent(
    UUID userId,
    UUID householdId,
    UUID shoppingListId,
    UUID planId,
    int planGeneration,
    int lineCount,
    Integer estimatedTotalPence,
    Instant occurredAt) {}
