package com.example.mealprep.provisions.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when an equipment row is upserted or deleted. {@code nowAvailable
 * = false} for deletes (the row has been removed). 01b ships no listeners — downstream modules
 * attach in their own tickets (e.g. planner bundle in 01f).
 *
 * <p>{@code scopeKind = "equipment"}, {@code scopeId} hashes the {@code (userId, equipmentName)}
 * tuple via deterministic UUID — stable across publications for the same row.
 */
public record EquipmentChangedEvent(
    UUID userId, String equipmentName, boolean nowAvailable, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "equipment";
  }

  @Override
  public UUID scopeId() {
    return UUID.nameUUIDFromBytes((userId + ":" + equipmentName).getBytes());
  }
}
