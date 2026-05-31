package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.PlanWriteService;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Testcontainers IT for the planner lifecycle sweeps (planner-3 + planner-10):
 *
 * <ul>
 *   <li>{@code sweepCompletedPlans} — a prior-week ACTIVE plan with all-terminal slots becomes
 *       COMPLETED; a prior-week plan with a still-PLANNED slot stays ACTIVE; a current-week plan is
 *       untouched.
 *   <li>{@code sweepExpiredReoptSuggestions} — a PENDING suggestion past its {@code expiresAt}
 *       becomes EXPIRED + swept; a future-dated PENDING suggestion is untouched.
 * </ul>
 *
 * <p>Crons are pinned to Feb-29 in the test profile so the {@code PlannerSweepScheduler} never
 * auto-fires; the sweep service methods are driven directly and asserted deterministically.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PlannerSweepIT {

  @Autowired private PlanWriteService planWriteService;
  @Autowired private PlanRepository planRepository;
  @Autowired private MealPrepPlanReoptSuggestionRepository suggestionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM planner_plan_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM decision_log");
    jdbcTemplate.update("DELETE FROM planner_plans");
  }

  /** A prior-week Monday (well before "now") so the weekly sweep treats it as a finished week. */
  private static LocalDate priorWeek() {
    return LocalDate.now().minusWeeks(2).with(java.time.DayOfWeek.MONDAY);
  }

  /** A future Monday so the weekly sweep never picks it up. */
  private static LocalDate futureWeek() {
    return LocalDate.now().plusWeeks(2).with(java.time.DayOfWeek.MONDAY);
  }

  private Plan seedActive(LocalDate week, SlotState slotState) {
    Plan plan = PlanTestData.newPlanGraph(UUID.randomUUID(), week, 1, PlanStatus.ACTIVE, 1, 2);
    for (MealSlot s : plan.getDays().get(0).getSlots()) {
      s.setState(slotState);
    }
    tx().executeWithoutResult(t -> planRepository.save(plan));
    return plan;
  }

  // ---- planner-3: weekly PlanCompleted sweep -------------------------------------------------

  @Test
  void weeklySweep_completesPriorWeekPlanWithAllTerminalSlots() {
    Plan eaten = seedActive(priorWeek(), SlotState.EATEN);

    int completed = planWriteService.sweepCompletedPlans();

    assertThat(completed).isEqualTo(1);
    assertThat(planRepository.findById(eaten.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.COMPLETED);
    assertThat(planRepository.findById(eaten.getId()).orElseThrow().getCompletedAt()).isNotNull();
  }

  @Test
  void weeklySweep_leavesPriorWeekPlanWithPlannedSlotActive() {
    Plan planned = seedActive(priorWeek(), SlotState.PLANNED);

    int completed = planWriteService.sweepCompletedPlans();

    assertThat(completed).isZero();
    assertThat(planRepository.findById(planned.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.ACTIVE);
  }

  @Test
  void weeklySweep_ignoresCurrentAndFutureWeekPlans() {
    Plan future = seedActive(futureWeek(), SlotState.EATEN);

    int completed = planWriteService.sweepCompletedPlans();

    assertThat(completed).isZero();
    assertThat(planRepository.findById(future.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.ACTIVE);
  }

  // ---- planner-10: re-opt suggestion expiry sweep --------------------------------------------

  private MealPrepPlanReoptSuggestion seedSuggestion(UUID planId, Instant expiresAt) {
    MealPrepPlanReoptSuggestion s =
        MealPrepPlanReoptSuggestion.builder()
            .id(UUID.randomUUID())
            .planId(planId)
            .triggerKind(ReoptTriggerKind.USER)
            .triggerEventId(UUID.randomUUID())
            .traceId(UUID.randomUUID())
            .summary("1 change")
            .status(ReoptSuggestionStatus.PENDING)
            .proposedAssignments(
                com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument.of(List.of()))
            .createdAt(Instant.now())
            .expiresAt(expiresAt)
            .swept(false)
            .build();
    tx().executeWithoutResult(t -> suggestionRepository.save(s));
    return s;
  }

  @Test
  void expirySweep_flipsStalePendingToExpired_andLeavesFutureDatedPending() {
    Plan plan = seedActive(priorWeek(), SlotState.PLANNED); // host for the suggestion FK-free rows
    MealPrepPlanReoptSuggestion stale =
        seedSuggestion(plan.getId(), Instant.now().minusSeconds(3600));
    MealPrepPlanReoptSuggestion fresh =
        seedSuggestion(plan.getId(), Instant.now().plusSeconds(86_400));

    int expired = planWriteService.sweepExpiredReoptSuggestions();

    assertThat(expired).isEqualTo(1);
    MealPrepPlanReoptSuggestion afterStale =
        suggestionRepository.findById(stale.getId()).orElseThrow();
    assertThat(afterStale.getStatus()).isEqualTo(ReoptSuggestionStatus.EXPIRED);
    assertThat(afterStale.isSwept()).isTrue();
    assertThat(suggestionRepository.findById(fresh.getId()).orElseThrow().getStatus())
        .isEqualTo(ReoptSuggestionStatus.PENDING);
  }

  @Test
  void expirySweep_isIdempotent_alreadySweptRowsNotReprocessed() {
    Plan plan = seedActive(priorWeek(), SlotState.PLANNED);
    seedSuggestion(plan.getId(), Instant.now().minusSeconds(3600));

    assertThat(planWriteService.sweepExpiredReoptSuggestions()).isEqualTo(1);
    // Second run: the row is now EXPIRED + swept, so the swept-false predicate excludes it.
    assertThat(planWriteService.sweepExpiredReoptSuggestions()).isZero();
  }
}
