package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.reopt.ReoptContextBuilder;
import com.example.mealprep.planner.domain.service.internal.reopt.ReoptStageCInvoker;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end mid-week re-opt over a real Postgres (Testcontainers): seeds a full
 * Plan&rarr;Day&rarr; MealSlot&rarr;ScheduledRecipe graph directly via {@link PlanRepository}
 * (whole FK graph satisfied), supplies an in-line {@link ReoptContextBuilder} + {@link
 * ReoptStageCInvoker} (planner-01j/01g not merged), then drives the real {@code
 * MidWeekReoptCoordinator} bean and asserts the suggestion row + idempotency + the
 * no-degrees-of-freedom skip.
 *
 * <p>Direct-repo seeding (no event, no async runner) means the assertion observes exactly the
 * coordinator's write — there is no listener/runner thread racing the row.
 */
@SpringBootTest
@Import({TestContainersConfig.class, MidWeekReoptFlowIT.ReoptTestWiring.class})
@ActiveProfiles("test")
class MidWeekReoptFlowIT {

  private static final LocalDate WEEK = LocalDate.now().plusYears(50);

  @Autowired private ApplicationContext applicationContext;
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
    // planner-01l: the coordinator now writes real decision_log rows. Single DELETE (self-FK).
    jdbcTemplate.update("DELETE FROM decision_log");
  }

  private Object coordinator() throws Exception {
    Class<?> cls =
        Class.forName(
            "com.example.mealprep.planner.domain.service.internal.reopt"
                + ".MidWeekReoptCoordinator");
    return applicationContext.getBean(cls);
  }

  @SuppressWarnings("unchecked")
  private Optional<UUID> requestReopt(
      UUID planId, ReoptTriggerKind trigger, UUID triggerEventId, UUID traceId) throws Exception {
    Object c = coordinator();
    Method m =
        c.getClass()
            .getMethod("requestReopt", UUID.class, ReoptTriggerKind.class, UUID.class, UUID.class);
    return (Optional<UUID>) m.invoke(c, planId, trigger, triggerEventId, traceId);
  }

  private Plan seedPlan(PlanStatus status) {
    Plan plan = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, status, 1, 3);
    tx().executeWithoutResult(t -> planRepository.save(plan));
    return plan;
  }

  @Test
  void midWeekReopt_materialChange_writesPendingSuggestion() throws Exception {
    Plan plan = seedPlan(PlanStatus.GENERATED);
    UUID triggerEventId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.PROVISIONS, triggerEventId, traceId);

    assertThat(result).isPresent();
    Optional<MealPrepPlanReoptSuggestion> saved = suggestionRepository.findById(result.get());
    assertThat(saved).isPresent();
    assertThat(saved.get().getStatus()).isEqualTo(ReoptSuggestionStatus.PENDING);
    assertThat(saved.get().getPlanId()).isEqualTo(plan.getId());
    assertThat(saved.get().getTriggerEventId()).isEqualTo(triggerEventId);
    assertThat(saved.get().getTraceId()).isEqualTo(traceId);
    assertThat(saved.get().getProposedAssignments().changes()).isNotEmpty();
    assertThat(saved.get().getProposedAssignments().schemaVersion()).isEqualTo(1);
    assertThat(saved.get().getExpiresAt()).isAfter(saved.get().getCreatedAt());
  }

  @Test
  void midWeekReopt_sameTriggerEventId_isIdempotent() throws Exception {
    Plan plan = seedPlan(PlanStatus.ACTIVE);
    UUID triggerEventId = UUID.randomUUID();

    Optional<UUID> first =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());
    Optional<UUID> second =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(first).isPresent();
    assertThat(second).isEqualTo(first);
    assertThat(suggestionRepository.count()).isEqualTo(1);
  }

  @Test
  void midWeekReopt_allSlotsPinned_returnsEmpty_noRow() throws Exception {
    Plan plan = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.GENERATED, 1, 3);
    for (Day d : plan.getDays()) {
      for (MealSlot s : d.getSlots()) {
        s.setState(SlotState.EATEN);
      }
    }
    tx().executeWithoutResult(t -> planRepository.save(plan));

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, UUID.randomUUID(), UUID.randomUUID());

    assertThat(result).isEmpty();
    assertThat(suggestionRepository.count()).isZero();
  }

  /**
   * In-line Stage-A/Stage-C wiring. {@link ReoptContextBuilder} returns a context whose skeletons
   * cover the non-pinned slots (empty eaters so hard-constraint check passes trivially) and whose
   * pool holds one fresh recipe per slot kind — a different recipe id than the seeded plan's, so
   * Stage-A produces a candidate that yields a material diff. {@link ReoptStageCInvoker} picks the
   * top candidate deterministically.
   */
  @TestConfiguration
  static class ReoptTestWiring {

    // planner-01j merged the production ReoptContextBuilder (PlanCompositionContextBuilder); this
    // IT keeps its deterministic in-line builder as @Primary so the algorithm-focused assertions
    // (material-diff, idempotency, no-degrees-of-freedom) stay hermetic and don't depend on the
    // production bundle fan-out.
    @Bean
    @org.springframework.context.annotation.Primary
    ReoptContextBuilder reoptContextBuilder() {
      return (activePlan, nonPinnedSlots, pinnedAssignments, traceId) -> {
        List<MealSlotSkeleton> skeletons = new ArrayList<>();
        List<RecipeDto> pool = new ArrayList<>();
        for (MealSlot slot : nonPinnedSlots) {
          skeletons.add(
              new MealSlotSkeleton(
                  slot.getDay().getId(),
                  slot.getId(),
                  slot.getSlotIndex(),
                  slot.getDay().getOnDate(),
                  slot.getKind(),
                  slot.getLabel(),
                  600, // generous time budget so the time filter never drops the pool recipe
                  true,
                  new ArrayList<>())); // empty eaters -> hard-constraint check passes
          pool.add(
              PlanTestData.recipeFor(UUID.randomUUID(), slot.getKind(), 30, List.of(), List.of()));
        }
        return new PlanCompositionContext(
            activePlan.getHouseholdId(),
            activePlan.getWeekStartDate(),
            skeletons,
            java.util.Map.of(),
            java.util.Map.of(),
            null,
            null,
            null,
            new RecipePoolSnapshot(pool, Instant.now()),
            pinnedAssignments,
            traceId,
            UUID.randomUUID(),
            java.util.Map.of());
      };
    }

    @Bean
    ReoptStageCInvoker reoptStageCInvoker() {
      return (candidates, rollups, context, traceId) ->
          new ReoptStageCInvoker.Result(0, "IT: deterministic top candidate");
    }
  }
}
