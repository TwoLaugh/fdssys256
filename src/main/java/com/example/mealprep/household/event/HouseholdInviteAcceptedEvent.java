package com.example.mealprep.household.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a household invite is accepted and the accepter is seated as
 * a {@code HouseholdMember} of the inviting household.
 *
 * <p>The accept path ALSO publishes {@code HouseholdMemberAddedEvent} (household-4) so that any
 * member-add consumer — notably the planner's re-opt listener (household-7) — reacts to the new
 * eater regardless of whether the member arrived via direct-add or invite-accept. This event is
 * retained for invite-flow-specific consumers (e.g. notification of a successful join).
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
