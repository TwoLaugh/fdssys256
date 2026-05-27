package com.example.mealprep.grocery.event;

import com.example.mealprep.grocery.domain.entity.PriceSource;
import java.time.Instant;
import java.util.UUID;

/**
 * Published once per Tier-4 price observation write (01c). Per LLD lines 831 / 883. Emitted
 * in-transaction via {@code ApplicationEventPublisher}; consumers listen
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so the event effectively fires after
 * commit (the project-wide convention — see {@code IntakeLoggedEvent}). No listeners ship in 01c;
 * emitted for downstream consumers (planner cost re-score, freshness signals).
 *
 * <p>{@code observationId} is the new append-only {@code grocery_price_history} row; {@code
 * householdId} may be null in single-user mode.
 */
public record PriceObservedEvent(
    UUID observationId,
    UUID userId,
    UUID householdId,
    String ingredientMappingKey,
    String store,
    PriceSource source,
    Integer paidUnitPence,
    Instant observedAt,
    Instant occurredAt) {}
