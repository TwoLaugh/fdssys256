package com.example.mealprep.provisions.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when an inventory item's quantity is decremented by a
 * quantity-tracked waste log (LLD line 662). The {@code source} disambiguates which flow drove the
 * change.
 *
 * <p>LLD divergence note: LLD §Events declares this event as a variant of a sealed {@code
 * ProvisionChangedEvent} interface. 01a deferred the sealed base to 01g; 01e follows the same
 * pattern as {@code ItemSpoiledEvent}/{@code ItemRanOutEvent}/{@code SubstitutionAcceptedEvent} and
 * declares this as a plain record implementing {@link ScopeChangedEvent}. The sealed base will be
 * introduced in 01g and this event refactored to extend it.
 *
 * <p>{@code scopeKind = "inventory-item"}, {@code scopeId = affectedItemIds.get(0)}.
 */
public record ItemQuantityAdjustedEvent(
    UUID userId,
    List<UUID> affectedItemIds,
    ItemAdjustmentSource source,
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
