package com.example.mealprep.provisions.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when {@code recordSubstitution} succeeds (the user — or the
 * grocery-import flow once it lands in 01h — accepts or rejects a delivered substitution). One
 * event per substitution append; the row itself carries the full {@code substitutionHistory}.
 *
 * <p>{@code userId} carries the {@code actorUserId} (who recorded the decision), not an owner of
 * the row — supplier products are global reference data with no per-user ownership.
 *
 * <p>01g refactored this record to implement the sealed {@link ProvisionChangedEvent} base. The
 * shape gained {@code affectedItemIds} (typically empty for substitution events — supplier-product
 * decisions don't directly mutate inventory rows; 01h grocery-import will populate the list when an
 * accepted substitution implicitly adds a different inventory row). {@code supplierProductId} is
 * retained alongside as the routing key for substitution-history listeners.
 *
 * <p>{@code scopeKind = "supplier-product"}, {@code scopeId = supplierProductId}.
 */
public record SubstitutionAcceptedEvent(
    UUID userId,
    List<UUID> affectedItemIds,
    UUID supplierProductId,
    String orderedProductId,
    String substitutedProductId,
    UUID traceId,
    Instant occurredAt)
    implements ProvisionChangedEvent {

  @Override
  public String scopeKind() {
    return "supplier-product";
  }

  @Override
  public UUID scopeId() {
    return supplierProductId;
  }
}
