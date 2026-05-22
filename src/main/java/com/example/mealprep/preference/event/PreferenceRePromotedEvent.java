package com.example.mealprep.preference.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Fired {@code AFTER_COMMIT} for each successful {@code markRePromoted}. No v1 consumers; emitted
 * for future analytics tracking re-emerging preferences.
 *
 * <p>{@code scopeKind = "taste-profile-archive"}, {@code scopeId = userId}.
 */
public record PreferenceRePromotedEvent(
    UUID userId,
    UUID archiveEntryId,
    String fieldPath,
    String itemKey,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "taste-profile-archive";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
