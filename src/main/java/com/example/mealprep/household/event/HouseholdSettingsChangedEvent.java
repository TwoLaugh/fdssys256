package com.example.mealprep.household.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a household's settings document is replaced via {@code PUT
 * /settings}. {@code changedFieldPaths} carries the same set of dotted paths that were written to
 * the audit log in the same transaction.
 *
 * <p>Skipped entirely when no field-paths actually changed (a re-submit of an identical document
 * emits no event and writes no audit row).
 *
 * <p>{@code scopeKind = "household"}, {@code scopeId = householdId}.
 */
public record HouseholdSettingsChangedEvent(
    UUID householdId,
    UUID settingsId,
    Set<String> changedFieldPaths,
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
