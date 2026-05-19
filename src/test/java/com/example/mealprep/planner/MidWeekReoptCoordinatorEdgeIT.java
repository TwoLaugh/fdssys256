package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument.ProposedSlotChange;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.reopt.MidWeekReoptCoordinator;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.PlanNotReoptableException;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
 * Complements {@code MidWeekReoptFlowIT}: the {@link MidWeekReoptCoordinator} guard branches that
 * IT leaves uncovered — the not-found throw, the non-reoptable terminal-status throw, the
 * idempotent-replay {@code MID_WEEK_REOPT_RESULT}, the bounded-budget rejection, and the
 * no-material-change skip when the production {@code PlanCompositionContextBuilder} (the SOLE
 * {@code ReoptContextBuilder} bean here — deliberately no test stand-in, so its real {@code
 * buildForReopt} fan-out runs) yields an empty recipe pool via the default {@code
 * NoOpRecipePoolSource}.
 *
 * <p>Seeding is direct via {@link PlanRepository} (whole FK graph satisfied, children-before-parent
 * cleanup); the coordinator is invoked in-process so the assertion observes exactly its write.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class MidWeekReoptCoordinatorEdgeIT {

  private static final LocalDate WEEK = LocalDate.now().plusYears(55);

  @Autowired private MidWeekReoptCoordinator coordinator;
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
    jdbcTemplate.update("DELETE FROM planner_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM planner_plans");
    jdbcTemplate.update("DELETE FROM decision_log");
  }

  private Plan seedPlan(PlanStatus status) {
    Plan plan = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, status, 1, 3);
    tx().executeWithoutResult(t -> planRepository.save(plan));
    return plan;
  }

  private void seedBudgetSuggestion(UUID planId, ReoptSuggestionStatus status) {
    MealPrepPlanReoptSuggestion s =
        MealPrepPlanReoptSuggestion.builder()
            .id(UUID.randomUUID())
            .planId(planId)
            .triggerKind(ReoptTriggerKind.USER)
            .triggerEventId(UUID.randomUUID())
            .traceId(UUID.randomUUID())
            .summary("seeded")
            .status(status)
            .proposedAssignments(
                ProposedReoptAssignmentsDocument.of(
                    List.of(
                        new ProposedSlotChange(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            2,
                            "x"))))
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(86_400))
            .swept(false)
            .build();
    tx().executeWithoutResult(t -> suggestionRepository.save(s));
  }

  @Test
  void requestReopt_unknownPlan_throwsPlanNotFound() {
    assertThatThrownBy(
            () ->
                tx().execute(
                        t ->
                            coordinator.requestReopt(
                                UUID.randomUUID(),
                                ReoptTriggerKind.USER,
                                UUID.randomUUID(),
                                UUID.randomUUID())))
        .isInstanceOf(PlanNotFoundException.class);
  }

  @Test
  void requestReopt_terminalStatus_throwsPlanNotReoptable() {
    Plan plan = seedPlan(PlanStatus.REJECTED);

    assertThatThrownBy(
            () ->
                tx().execute(
                        t ->
                            coordinator.requestReopt(
                                plan.getId(),
                                ReoptTriggerKind.USER,
                                UUID.randomUUID(),
                                UUID.randomUUID())))
        .isInstanceOf(PlanNotReoptableException.class);
  }

  @Test
  void requestReopt_budgetExhausted_returnsEmpty_noNewSuggestion() {
    Plan plan = seedPlan(PlanStatus.ACTIVE);
    // Default mealprep.planner.mid-week.max-suggestions-per-plan = 3 -> seed 3 PENDING.
    seedBudgetSuggestion(plan.getId(), ReoptSuggestionStatus.PENDING);
    seedBudgetSuggestion(plan.getId(), ReoptSuggestionStatus.PENDING);
    seedBudgetSuggestion(plan.getId(), ReoptSuggestionStatus.REJECTED);
    long before = suggestionRepository.count();

    Optional<UUID> result =
        tx().execute(
                t ->
                    coordinator.requestReopt(
                        plan.getId(), ReoptTriggerKind.USER, UUID.randomUUID(), UUID.randomUUID()));

    assertThat(result).isEmpty();
    assertThat(suggestionRepository.count()).isEqualTo(before);
  }

  @Test
  void requestReopt_emptyRecipePool_yieldsNoMaterialChange_returnsEmpty() {
    // No test ReoptContextBuilder stand-in -> the production PlanCompositionContextBuilder runs.
    // Its RecipePoolSource is the default NoOpRecipePoolSource (empty pool) -> Stage-A produces no
    // candidates -> coordinator writes a no_material_change result and returns empty.
    Plan plan = seedPlan(PlanStatus.ACTIVE);

    Optional<UUID> result =
        tx().execute(
                t ->
                    coordinator.requestReopt(
                        plan.getId(), ReoptTriggerKind.USER, UUID.randomUUID(), UUID.randomUUID()));

    assertThat(result).isEmpty();
    assertThat(suggestionRepository.count()).isZero();
  }

  @Test
  void requestReopt_sameTriggerEventId_idempotentReplay_returnsExistingId() {
    Plan plan = seedPlan(PlanStatus.ACTIVE);
    UUID triggerEventId = UUID.randomUUID();
    // Pre-seed a suggestion carrying this triggerEventId so the idempotency branch hits.
    MealPrepPlanReoptSuggestion existing =
        MealPrepPlanReoptSuggestion.builder()
            .id(UUID.randomUUID())
            .planId(plan.getId())
            .triggerKind(ReoptTriggerKind.USER)
            .triggerEventId(triggerEventId)
            .traceId(UUID.randomUUID())
            .summary("pre-existing")
            .status(ReoptSuggestionStatus.PENDING)
            .proposedAssignments(
                ProposedReoptAssignmentsDocument.of(
                    List.of(
                        new ProposedSlotChange(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            2,
                            "x"))))
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(86_400))
            .swept(false)
            .build();
    tx().executeWithoutResult(t -> suggestionRepository.save(existing));

    Optional<UUID> result =
        tx().execute(
                t ->
                    coordinator.requestReopt(
                        plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID()));

    assertThat(result).contains(existing.getId());
    assertThat(suggestionRepository.count()).isEqualTo(1);
  }
}
