package com.example.mealprep.household.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a household invite is created. No listeners in 01c — emitted
 * for downstream consumers (e.g. a future notification module rendering "you've been invited").
 *
 * <p>{@code scopeKind = "household"}, {@code scopeId = householdId}.
 */
public record HouseholdInviteCreatedEvent(
    UUID householdId,
    UUID inviteId,
    UUID issuedByUserId,
    UUID issuedForUserId,
    HouseholdRole intendedRole,
    Instant expiresAt,
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
