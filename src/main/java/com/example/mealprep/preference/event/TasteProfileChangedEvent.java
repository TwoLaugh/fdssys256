package com.example.mealprep.preference.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.preference.domain.entity.ActorType;
import com.example.mealprep.preference.domain.entity.TasteProfileChangeType;
import java.time.Instant;
import java.util.UUID;

/**
 * Fired {@code AFTER_COMMIT} on every successful taste-profile write. Consumed by:
 *
 * <ul>
 *   <li>the (deferred) async embedding listener — recomputes the vector when {@code
 *       documentVersion} bumps;
 *   <li>the planner module (if it chooses) — re-opt trigger on significant preference changes.
 * </ul>
 *
 * <p>{@code scopeKind = "taste-profile"}, {@code scopeId = userId}.
 */
public record TasteProfileChangedEvent(
    UUID userId,
    UUID tasteProfileId,
    int documentVersion,
    TasteProfileChangeType changeType,
    ActorType actorType,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "taste-profile";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
