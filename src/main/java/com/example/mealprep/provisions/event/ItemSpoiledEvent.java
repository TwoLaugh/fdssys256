package com.example.mealprep.provisions.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when an inventory item is marked spoiled (manually by the user, or
 * by the expiry sweep in 01k).
 *
 * <p>01g refactored this record to implement the sealed {@link ProvisionChangedEvent} base.
 *
 * <p>{@code scopeKind = "inventory-item"}, {@code scopeId = affectedItemIds.get(0)} when a single
 * item is in scope (the typical case for the user-triggered mark-spoiled endpoint). For a
 * multi-item sweep, callers may inspect the {@code affectedItemIds} list.
 */
public record ItemSpoiledEvent(
    UUID userId, List<UUID> affectedItemIds, String reason, UUID traceId, Instant occurredAt)
    implements ProvisionChangedEvent {

  @Override
  public String scopeKind() {
    return "inventory-item";
  }

  @Override
  public UUID scopeId() {
    return affectedItemIds == null || affectedItemIds.isEmpty() ? null : affectedItemIds.get(0);
  }
}
