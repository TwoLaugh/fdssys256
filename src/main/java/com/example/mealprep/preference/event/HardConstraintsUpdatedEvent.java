package com.example.mealprep.preference.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} on a successful hard-constraints update that mutated at least one
 * field. Listeners (recipe filter cache invalidation in 01b, planner re-opt trigger in a later
 * ticket) inspect {@link #fieldsChanged()} to decide whether to react.
 *
 * <p>{@code scopeKind = "hard-constraints"}, {@code scopeId = userId}.
 */
public record HardConstraintsUpdatedEvent(
    UUID userId, Set<String> fieldsChanged, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "hard-constraints";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
