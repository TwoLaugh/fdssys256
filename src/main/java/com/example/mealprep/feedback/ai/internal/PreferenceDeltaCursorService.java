package com.example.mealprep.feedback.ai.internal;

import com.example.mealprep.feedback.domain.entity.PreferenceDeltaCursor;
import com.example.mealprep.feedback.domain.repository.PreferenceDeltaCursorRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@link PreferenceDeltaCursor} read/write transactions for the preference AI
 * delta-generation pipeline (preference-01g). Extracted as its own bean so the {@code REQUIRES_NEW}
 * boundaries are honoured by Spring's proxy (self-invocation of a {@code @Transactional} method
 * within one bean silently drops the annotation).
 *
 * <p>All writes use {@code REQUIRES_NEW} so they commit independently of the caller's context — the
 * BATCH-increment path runs from the post-routing flow, and the run-reset runs around an AI call
 * that must happen <em>outside</em> any DB transaction.
 */
@Component
public class PreferenceDeltaCursorService {

  private final PreferenceDeltaCursorRepository cursorRepository;
  private final Clock clock;

  public PreferenceDeltaCursorService(
      PreferenceDeltaCursorRepository cursorRepository, Clock clock) {
    this.cursorRepository = cursorRepository;
    this.clock = clock;
  }

  /**
   * Increment the user's pending PREFERENCE-routed count and remember the most-recent processed
   * feedback. Creates the cursor on first feedback. Returns the post-increment pending count so the
   * caller can decide whether the BATCH threshold was crossed.
   *
   * <p>A concurrent first-insert can collide on the {@code user_id} unique index; that surfaces as
   * a {@link DataIntegrityViolationException} which the caller treats as "another writer created it
   * — retry the read-modify path" (the scheduled/async contexts that drive this are at-least-once,
   * so a retry is safe).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int recordPreferenceFeedback(UUID userId, UUID feedbackId) {
    Instant now = Instant.now(clock);
    PreferenceDeltaCursor cursor =
        cursorRepository
            .findByUserId(userId)
            .orElseGet(
                () ->
                    PreferenceDeltaCursor.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .pendingCount(0)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
    cursor.setPendingCount(cursor.getPendingCount() + 1);
    cursor.setLastProcessedFeedbackId(feedbackId);
    return cursorRepository.save(cursor).getPendingCount();
  }

  /** Read a snapshot of the user's cursor, if one exists. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public java.util.Optional<PreferenceDeltaCursor> find(UUID userId) {
    return cursorRepository.findByUserId(userId);
  }

  /** Every user with at least one pending PREFERENCE-routed feedback (WEEKLY-sweep candidates). */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<UUID> usersWithPendingFeedback() {
    return cursorRepository.findWithPendingAtLeast(1).stream()
        .map(PreferenceDeltaCursor::getUserId)
        .toList();
  }

  /**
   * Reset the pending count to zero and stamp the run metadata after a delta-update run completes
   * (whether or not deltas were applied — the feedback was processed either way, per the empty-AI
   * -response edge case). No-op when no cursor exists.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markRun(UUID userId, String trigger) {
    cursorRepository
        .findByUserId(userId)
        .ifPresent(
            cursor -> {
              cursor.setPendingCount(0);
              cursor.setLastRunAt(Instant.now(clock));
              cursor.setLastRunTrigger(trigger);
              cursorRepository.save(cursor);
            });
  }
}
