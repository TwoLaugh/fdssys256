package com.example.mealprep.household.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a member is added to a household — both via the direct-add
 * admin endpoint ({@code POST /api/v1/households/current/members}) and via the invite-accept path
 * ({@code POST /api/v1/invites/accept}). The accept path ALSO emits {@code
 * HouseholdInviteAcceptedEvent} for invite-flow consumers (household-4). The planner consumes this
 * event to re-evaluate the shared-slot eater set (household-7).
 *
 * <p>{@code scopeKind = "household"}, {@code scopeId = householdId}.
 */
public record HouseholdMemberAddedEvent(
    UUID householdId,
    UUID memberId,
    UUID userId,
    HouseholdRole role,
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
