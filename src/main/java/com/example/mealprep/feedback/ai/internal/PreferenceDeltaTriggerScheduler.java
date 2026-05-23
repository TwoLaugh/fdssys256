package com.example.mealprep.feedback.ai.internal;

import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator.RunResult;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WEEKLY trigger (preference-01g §6): a scheduled sweep that runs a delta-update for every user
 * with pending PREFERENCE-routed feedback since their last run. Default cron Sundays 03:00.
 *
 * <p>{@code @ConditionalOnProperty(matchIfMissing = true)} keeps the bean registered in prod/dev
 * but lets the test profile disable it (the test profile also sets a never-matching cron as a
 * belt-and-braces guard, mirroring the notification scanners).
 *
 * <p>Runs from the scheduler thread — NOT an AFTER_COMMIT phase — so the orchestrator's downstream
 * {@code applyDeltas} commits under its own plain {@code @Transactional}. Each user is handled
 * independently; one user's failure is caught + logged and the sweep continues.
 */
@Component
@ConditionalOnProperty(
    prefix = "mealprep.feedback.preference-delta",
    name = "weekly-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PreferenceDeltaTriggerScheduler {

  private static final Logger log = LoggerFactory.getLogger(PreferenceDeltaTriggerScheduler.class);

  private final TasteProfileDeltaOrchestrator orchestrator;
  private final PreferenceDeltaCursorService cursorService;

  public PreferenceDeltaTriggerScheduler(
      TasteProfileDeltaOrchestrator orchestrator, PreferenceDeltaCursorService cursorService) {
    this.orchestrator = orchestrator;
    this.cursorService = cursorService;
  }

  /** Weekly sweep trigger. The cron is far-future in the test profile so it never auto-fires. */
  @Scheduled(cron = "${mealprep.feedback.preference-delta.weekly-cron:0 0 3 * * SUN}")
  public int runScheduled() {
    return sweep();
  }

  /**
   * Single synchronous sweep over all users with pending PREFERENCE-routed feedback. Returns the
   * number of users for whom deltas were actually applied. Invoked directly by tests with a
   * controllable Clock.
   */
  public int sweep() {
    List<UUID> users = cursorService.usersWithPendingFeedback();
    int applied = 0;
    for (UUID userId : users) {
      try {
        RunResult result = orchestrator.run(userId, TasteProfileTrigger.WEEKLY, null, null, null);
        // Reset the pending count whenever the batch was processed (applied or conservatively
        // empty)
        // — leave it intact only when the run could not proceed (AI down / no profile / empty
        // batch)
        // so the next sweep retries.
        if (result == RunResult.APPLIED
            || result == RunResult.NO_DELTAS
            || result == RunResult.DELTA_INVALID) {
          cursorService.markRun(userId, TasteProfileTrigger.WEEKLY.name());
        }
        if (result == RunResult.APPLIED) {
          applied++;
        }
      } catch (RuntimeException e) {
        log.error("preference delta weekly sweep failed for userId={}", userId, e);
      }
    }
    log.info(
        "preference delta weekly sweep complete: appliedForUsers={} candidates={}",
        applied,
        users.size());
    return applied;
  }
}
