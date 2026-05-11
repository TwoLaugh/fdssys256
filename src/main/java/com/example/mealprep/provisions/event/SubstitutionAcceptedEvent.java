package com.example.mealprep.provisions.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when {@code recordSubstitution} succeeds (the user — or the
 * grocery-import flow once it lands in 01h — accepts or rejects a delivered substitution). One
 * event per substitution append; the row itself carries the full {@code substitutionHistory}.
 *
 * <p>{@code userId} carries the {@code actorUserId} (who recorded the decision), not an owner of
 * the row — supplier products are global reference data with no per-user ownership.
 *
 * <p>LLD divergence note: LLD §Events declares this as a sealed-variant of {@code
 * ProvisionChangedEvent}. 01a deferred the sealed base (no hierarchy exists yet); 01d follows the
 * same pattern as {@code ItemRanOutEvent}/{@code ItemSpoiledEvent}/{@code BudgetChangedEvent} and
 * declares this as a plain record implementing {@link ScopeChangedEvent}. The sealed base will be
 * introduced in 01g and this event refactored to extend it.
 *
 * <p>{@code scopeKind = "supplier-product"}, {@code scopeId = supplierProductId}.
 */
public record SubstitutionAcceptedEvent(
    UUID userId,
    UUID supplierProductId,
    String orderedProductId,
    String substitutedProductId,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "supplier-product";
  }

  @Override
  public UUID scopeId() {
    return supplierProductId;
  }
}
