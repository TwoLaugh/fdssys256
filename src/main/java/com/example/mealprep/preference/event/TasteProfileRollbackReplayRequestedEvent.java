package com.example.mealprep.preference.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} by {@code TasteProfileServiceImpl.rollbackTasteProfile} once the
 * document has been reverted to a prior version and the {@code feedbackCursor} reset to that
 * version's anchor. Requests the feedback module to re-process (replay) the feedback in {@code
 * [fromFeedbackCursor, toCursorBefore]} forward, re-deriving the AI deltas deterministically (per
 * {@code design/preference-model.md:419-421}).
 *
 * <p>The preference module never replays feedback itself — it does not consume {@code
 * FeedbackProcessedEvent} (locked decision, {@code lld/preference.md:692}). It delegates via this
 * event, mirroring the loose-coupling direction of {@code TasteProfileRefreshRequestedEvent}
 * (preference publishes → feedback consumes), so the dependency graph stays acyclic. The feedback
 * -side listener re-runs the {@code preference-01g} delta orchestrator; because that listener fires
 * AFTER_COMMIT of the rollback tx, its write path must use {@code REQUIRES_NEW} (decision-log
 * 0010).
 *
 * <p>{@code fromFeedbackCursor} is the rolled-back-to version's {@code feedbackRangeStart} (the
 * deterministic replay anchor); {@code toCursorBefore} is the profile's {@code feedbackCursor} as
 * it stood immediately before the rollback (the forward bound of the replay window). Either may be
 * {@code null} (no cursor recorded yet) — the listener falls back to a since-cursor replay.
 *
 * <p>{@code scopeKind = "taste-profile"}, {@code scopeId = userId}.
 */
public record TasteProfileRollbackReplayRequestedEvent(
    UUID userId,
    UUID tasteProfileId,
    int restoredDocumentVersion,
    String fromFeedbackCursor,
    String toCursorBefore,
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
