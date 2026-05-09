package com.example.mealprep.household.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a household is created. 01a has no listeners; downstream
 * modules (planner, provisions, nutrition) attach in their own tickets.
 *
 * <p>{@code scopeKind = "household"}, {@code scopeId = householdId}.
 */
public record HouseholdCreatedEvent(
    UUID householdId, UUID createdByUserId, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "household";
  }

  @Override
  public UUID scopeId() {
    return householdId;
  }
}
