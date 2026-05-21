package com.example.mealprep.preference.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a user's lifestyle config is replaced via {@code PUT
 * /lifestyle-config}. {@code changedSections} carries the same set of top-level section names that
 * were written to the audit log in the same transaction — listeners (planner re-opt, future
 * "lifestyle update" notification surfacing) inspect this set to decide whether to react.
 *
 * <p>Skipped entirely when no section actually changed (a re-submit of an identical document emits
 * no event and writes no audit row).
 *
 * <p>{@code scopeKind = "lifestyle-config"}, {@code scopeId = userId}.
 */
public record LifestyleConfigChangedEvent(
    UUID userId,
    UUID lifestyleConfigId,
    Set<String> changedSections,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "lifestyle-config";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
