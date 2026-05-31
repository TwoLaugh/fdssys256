package com.example.mealprep.planner.domain.service.internal.lifecycle;

import com.example.mealprep.planner.domain.service.PlanWriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Planner lifecycle sweeps driven by Spring {@code @Scheduled} crons (planner-3 + planner-10).
 * Mirrors the project pattern (e.g. {@code DispatchLogCleanupScheduler}, {@code
 * PendingChangeExpirySweepScheduler}): the bean carries no logic beyond delegating to a
 * {@code @Transactional} service method, and each cron is parameterised on a config key with a
 * default so the {@code test} / {@code e2e} profiles can push it far into the future (the sweep
 * methods are invoked directly by ITs instead of waiting for the trigger).
 * {@code @EnableScheduling} is engaged at {@code MealPrepApplication}.
 *
 * <ul>
 *   <li><b>Weekly PlanCompleted sweep</b> (planner-3) — every Monday morning, transition prior-week
 *       {@code ACTIVE} plans whose slots are all terminal to {@code COMPLETED} and publish {@code
 *       PlanCompletedEvent}. Cron {@code mealprep.planner.plan-completed-sweep-cron} (default
 *       {@code 0 0 3 * * MON}).
 *   <li><b>Daily re-opt suggestion expiry sweep</b> (planner-10) — flip stale {@code PENDING}
 *       suggestions ({@code weekStartDate + 7 days} elapsed) to {@code EXPIRED}. Cron {@code
 *       mealprep.planner.reopt-expiry-sweep-cron} (default {@code 0 0 4 * * *}).
 * </ul>
 */
@Component
public class PlannerSweepScheduler {

  private static final Logger log = LoggerFactory.getLogger(PlannerSweepScheduler.class);

  private final PlanWriteService planWriteService;

  public PlannerSweepScheduler(PlanWriteService planWriteService) {
    this.planWriteService = planWriteService;
  }

  /**
   * Weekly Monday sweep: prior-week ACTIVE plans with all-terminal slots → COMPLETED (planner-3).
   */
  @Scheduled(cron = "${mealprep.planner.plan-completed-sweep-cron:0 0 3 * * MON}")
  public void runPlanCompletedSweep() {
    int completed = planWriteService.sweepCompletedPlans();
    if (completed > 0) {
      log.info("planner weekly sweep: {} plan(s) transitioned to COMPLETED", completed);
    }
  }

  /** Daily sweep: stale PENDING re-opt suggestions → EXPIRED (planner-10). */
  @Scheduled(cron = "${mealprep.planner.reopt-expiry-sweep-cron:0 0 4 * * *}")
  public void runReoptExpirySweep() {
    int expired = planWriteService.sweepExpiredReoptSuggestions();
    if (expired > 0) {
      log.info("planner daily sweep: {} re-opt suggestion(s) flipped to EXPIRED", expired);
    }
  }
}
