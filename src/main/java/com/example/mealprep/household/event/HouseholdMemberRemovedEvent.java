package com.example.mealprep.household.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a member is removed from a household via the member-admin
 * delete endpoint ({@code DELETE /api/v1/households/current/members/{memberId}}). {@code
 * roleAtRemoval} captures the member's role just before deletion (so listeners can branch on
 * primary-vs-member removal without rehydrating the row).
 *
 * <p>{@code scopeKind = "household"}, {@code scopeId = householdId}.
 */
public record HouseholdMemberRemovedEvent(
    UUID householdId,
    UUID memberId,
    UUID userId,
    HouseholdRole roleAtRemoval,
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
