package com.example.mealprep.provisions.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Catch-all variant of {@link ProvisionChangedEvent} per LLD line 569. Reserved for flows that
 * don't fit the other named variants — admin imports, retention sweeps, manual ops tooling. Carries
 * a {@code changeType} string so listeners can route on its value when needed.
 *
 * <p>{@code scopeKind = "inventory-item"}, {@code scopeId = affectedItemIds.get(0)}.
 */
public record GenericProvisionChangedEvent(
    UUID userId, List<UUID> affectedItemIds, String changeType, UUID traceId, Instant occurredAt)
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
