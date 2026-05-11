package com.example.mealprep.household.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a member is added to a household via the direct-add admin
 * endpoint ({@code POST /api/v1/households/current/members}). The invite-accept path emits {@code
 * HouseholdInviteAcceptedEvent} instead (01c-locked decision) and does NOT additionally emit this
 * event.
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
