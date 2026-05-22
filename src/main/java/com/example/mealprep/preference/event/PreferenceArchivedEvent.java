package com.example.mealprep.preference.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import java.time.Instant;
import java.util.UUID;

/**
 * Fired {@code AFTER_COMMIT} for each successful {@code archiveItem}. No v1 consumers; emitted for
 * future "your profile got smaller" analytics or notification. Carries the originating trace id
 * when the future delta-applier archives inside an upstream transaction.
 *
 * <p>{@code scopeKind = "taste-profile-archive"}, {@code scopeId = userId}.
 */
public record PreferenceArchivedEvent(
    UUID userId,
    UUID archiveEntryId,
    String fieldPath,
    String itemKey,
    ArchiveReason reason,
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
