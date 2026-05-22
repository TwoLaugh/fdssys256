package com.example.mealprep.provisions.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when the expiry sweep (notification/01b scanner, or a future
 * provisions-side sweep) observes one or more inventory items approaching their expiry date for a
 * user/household. Consumed by the notification module ({@code
 * ProvisionEventListener.onItemNearingExpiry}) to raise a {@code PROVISION_ITEM_NEAR_EXPIRY}
 * notification.
 *
 * <p>The technical-architecture catalogue calls this {@code ExpiryApproachingEvent}; the
 * notification brief uses {@code ItemNearingExpiryEvent} — the names refer to the same signal and
 * the brief's name takes precedence here. The record shape is the minimal contract the notification
 * payload needs ({@code inventoryItemIds}, {@code earliestExpiry}); the producer (a scanner) ships
 * in a sibling ticket.
 *
 * <p>{@code scopeKind = "inventory-item"}, {@code scopeId = first affected item} (or {@code userId}
 * fallback when the list is empty).
 */
public record ItemNearingExpiryEvent(
    UUID userId,
    UUID householdId,
    List<UUID> inventoryItemIds,
    LocalDate earliestExpiry,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "inventory-item";
  }

  @Override
  public UUID scopeId() {
    return inventoryItemIds == null || inventoryItemIds.isEmpty()
        ? userId
        : inventoryItemIds.get(0);
  }
}
