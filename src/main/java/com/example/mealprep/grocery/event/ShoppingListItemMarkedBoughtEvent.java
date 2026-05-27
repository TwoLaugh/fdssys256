package com.example.mealprep.grocery.event;

import com.example.mealprep.grocery.domain.entity.BoughtVia;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published once per Tier-2 single-line mark-bought (grocery-01d). Per lld/grocery.md line 883.
 * Emitted in-transaction via {@code ApplicationEventPublisher}; consumers listen
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so it effectively fires after commit
 * (the project-wide convention — see {@code PriceObservedEvent}). No listeners ship in 01d; emitted
 * for downstream consumers (notification, planner cost surfacing).
 *
 * <p>Bulk mark-bought does NOT emit this per-line — it emits one {@link
 * ShoppingListBulkMarkedBoughtEvent} for the whole operation ("one event per user-initiated
 * operation", lld/grocery.md line 899). {@code householdId} may be null in single-user mode; {@code
 * boughtPricePence} is null when no price was supplied.
 */
public record ShoppingListItemMarkedBoughtEvent(
    UUID userId,
    UUID householdId,
    UUID shoppingListId,
    UUID shoppingListLineId,
    String ingredientMappingKey,
    BigDecimal boughtQuantity,
    String boughtUnit,
    Integer boughtPricePence,
    BoughtVia boughtVia,
    Instant occurredAt) {}
