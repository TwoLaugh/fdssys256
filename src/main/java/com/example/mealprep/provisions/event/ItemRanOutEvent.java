package com.example.mealprep.provisions.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when an inventory item is marked exhausted (manually by the user,
 * or by the cook-event deduction engine in 01g).
 *
 * <p>LLD divergence note: LLD §Events declares both {@code ItemSpoiledEvent} and {@code
 * ItemRanOutEvent} as variants of a sealed {@code ProvisionChangedEvent} interface. 01a deferred
 * the sealed base to 01g (cook-event flow, when the hierarchy carries more than one variant). 01b
 * therefore declares this event as a plain record (not a sealed-interface variant); 01g will
 * refactor it to extend the sealed base.
 *
 * <p>{@code wasStaple} mirrors the row's {@code isStaple} flag at the time of the transition — the
 * 01i staple-replenishment-list endpoint listens on this event to drive shopping-list updates.
 *
 * <p>{@code scopeKind = "inventory-item"}, {@code scopeId = affectedItemIds.get(0)} when a single
 * item is in scope.
 */
public record ItemRanOutEvent(
    UUID userId,
    List<UUID> affectedItemIds,
    String ingredientMappingKey,
    boolean wasStaple,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "inventory-item";
  }

  @Override
  public UUID scopeId() {
    return affectedItemIds == null || affectedItemIds.isEmpty() ? null : affectedItemIds.get(0);
  }
}
