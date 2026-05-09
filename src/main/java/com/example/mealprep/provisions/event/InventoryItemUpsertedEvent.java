package com.example.mealprep.provisions.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when {@code createInventoryItem} or {@code updateInventoryItem}
 * succeeds. 01a has no listeners — downstream modules attach in their own tickets.
 *
 * <p>{@code scopeKind = "inventory-item"}, {@code scopeId = itemId}.
 *
 * <p>The sealed {@code ProvisionChangedEvent} base referenced in the LLD lands in 01g (cook-event
 * flow, when the hierarchy carries more than one event); this 01a-only event will be refactored to
 * extend it then. Deferring avoids over-engineering an empty hierarchy now.
 */
public record InventoryItemUpsertedEvent(
    UUID itemId, UUID userId, AuditActor actor, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "inventory-item";
  }

  @Override
  public UUID scopeId() {
    return itemId;
  }
}
