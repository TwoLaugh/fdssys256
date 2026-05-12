package com.example.mealprep.provisions.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a grocery-import flow adds inventory rows on the user's
 * behalf. Shipped in 01g for sealed-hierarchy completeness; the publishing flow lands with {@code
 * GroceryImportProcessor} in 01h.
 *
 * <p>{@code scopeKind = "inventory-item"}, {@code scopeId = affectedItemIds.get(0)}.
 */
public record ItemAddedFromGroceryEvent(
    UUID userId,
    List<UUID> affectedItemIds,
    String supplier,
    String orderRef,
    UUID traceId,
    Instant occurredAt)
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
