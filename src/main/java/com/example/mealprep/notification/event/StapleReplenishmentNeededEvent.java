package com.example.mealprep.notification.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the {@code StapleReplenishmentScanner} (notification/01b) {@code AFTER} the weekly
 * staple sweep observes one or more staple inventory items at/below their restock threshold for a
 * user. Consumed by {@link ProvisionEventListener#onStapleReplenishmentNeeded} to raise a {@code
 * STAPLE_REPLENISHMENT_NEEDED} notification.
 *
 * <p>Unlike the other consumed events (which the producer modules own), this event is defined
 * inside the notification module: the scanner is its sole producer and the notification listener
 * its sole consumer, so there is no cross-module contract to publish from a producer module
 * (decision §10 Option A in {@code tickets/notification/01b-scanners.md}).
 *
 * <p>{@code lowestStockRatio} is the lowest observed {@code quantity / restock_threshold} across
 * the batch (0 means fully out); it lets the listener phrase severity/copy. {@code scopeKind =
 * "staple-replenishment"}, {@code scopeId = userId} (the batch is per-user).
 */
public record StapleReplenishmentNeededEvent(
    UUID userId,
    List<UUID> inventoryItemIds,
    List<String> ingredientMappingKeys,
    BigDecimal lowestStockRatio,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "staple-replenishment";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
