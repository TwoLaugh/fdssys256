package com.example.mealprep.household.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a member's role is changed via {@code POST
 * /api/v1/households/current/members/{memberId}/role}. A no-op call (newRole == previousRole)
 * emits no event.
 *
 * <p>{@code scopeKind = "household"}, {@code scopeId = householdId}.
 */
public record HouseholdRoleChangedEvent(
    UUID householdId,
    UUID memberId,
    UUID userId,
    HouseholdRole previousRole,
    HouseholdRole newRole,
    UUID traceId,
    Instant occurredAt)
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
