package com.example.mealprep.household.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a household invite is accepted and the accepter is seated as
 * a {@code HouseholdMember} of the inviting household.
 *
 * <p>01c does NOT additionally publish {@code HouseholdMemberAddedEvent} on this path — that event
 * is reserved for the dedicated member-admin endpoints that 01e introduces. Downstream listeners
 * consuming the v1 onboarding flow should subscribe to this event. 01e may consider unifying.
 *
 * <p>{@code scopeKind = "household"}, {@code scopeId = householdId}.
 */
public record HouseholdInviteAcceptedEvent(
    UUID householdId,
    UUID inviteId,
    UUID acceptedByUserId,
    HouseholdRole grantedRole,
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
