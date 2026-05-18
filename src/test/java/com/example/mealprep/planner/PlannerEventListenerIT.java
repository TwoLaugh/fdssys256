package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.repository.HouseholdRepository;
import com.example.mealprep.nutrition.api.dto.DivergenceSummaryDto;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.reopt.ReoptContextBuilder;
import com.example.mealprep.planner.domain.service.internal.reopt.ReoptStageCInvoker;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.provisions.event.ItemAddedFromGroceryEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end test of {@link
 * com.example.mealprep.planner.domain.service.internal.listeners.PlannerEventListener} over a real
 * Postgres (Testcontainers). For each of the 4 upstream source events it asserts: a material event
 * publishes a {@code ReoptSuggestion} row with the right {@link ReoptTriggerKind}; an immaterial
 * event creates none; a cross-household event does not touch household B's plan; and a duplicate
 * publish is idempotent (no second suggestion).
 *
 * <p>Seeding is direct via {@link PlanRepository} + {@link HouseholdRepository} (whole FK graph
 * satisfied, children-before-parent cleanup). A real {@link Household} aggregate with the test user
 * as a member is persisted so the real {@code HouseholdQueryService.getByUserId} resolves —
 * deliberately NOT a {@code @MockBean}: {@code HouseholdServiceImpl} / {@code RecipeServiceImpl}
 * are multi-interface {@code @Service}s, so a {@code @MockBean} on one interface evicts ALL their
 * beans (e.g. {@code RecipeWriteApi}) and breaks unrelated context wiring (the ticket's
 * multi-interface-MockBean gotcha — observed first-hand here). The events are published with {@link
 * ApplicationEventPublisher} inside a transaction so the
 * {@code @TransactionalEventListener(AFTER_COMMIT)} actually fires. The {@code REQUIRES_NEW}
 * listener tx commits asynchronously w.r.t. the publisher, so assertions poll with a bounded wait.
 *
 * <p>{@link ReoptContextBuilder} / {@link ReoptStageCInvoker} are supplied inline (planner-01j/01g
 * not merged) exactly as in {@code MidWeekReoptFlowIT}, so the coordinator produces a real
 * material-diff suggestion.
 */
@SpringBootTest
@Import({TestContainersConfig.class, PlannerEventListenerIT.ReoptTestWiring.class})
@ActiveProfiles("test")
class PlannerEventListenerIT {

  private static final LocalDate WEEK = LocalDate.now().plusYears(60);

  @Autowired private PlanRepository planRepository;
  @Autowired private MealPrepPlanReoptSuggestionRepository suggestionRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;
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
    jdbcTemplate.update("DELETE FROM household_member");
    jdbcTemplate.update("DELETE FROM household");
  }

  private Plan seedPlan(UUID householdId) {
    Plan plan = PlanTestData.newPlanGraph(householdId, WEEK, 1, PlanStatus.GENERATED, 1, 3);
    tx().executeWithoutResult(t -> planRepository.save(plan));
    return plan;
  }

  /** Persist a real household with {@code userId} as a member; returns the household id. */
  private UUID seedHousehold(UUID userId) {
    UUID householdId = UUID.randomUUID();
    HouseholdMember member =
        HouseholdMember.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .role(HouseholdRole.primary)
            .priority(100)
            .joinedAt(Instant.now())
            .build();
    Household household =
        Household.builder()
            .id(householdId)
            .name("test-hh")
            .createdByUserId(userId)
            .members(new ArrayList<>())
            .build();
    member.setHousehold(household);
    household.getMembers().add(member);
    tx().executeWithoutResult(t -> householdRepository.save(household));
    return householdId;
  }

  private void publishInTx(Object event) {
    tx().executeWithoutResult(t -> eventPublisher.publishEvent(event));
  }

  private List<MealPrepPlanReoptSuggestion> awaitSuggestions(UUID planId, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      List<MealPrepPlanReoptSuggestion> mine =
          suggestionRepository.findAll().stream()
              .filter(s -> s.getPlanId().equals(planId))
              .toList();
      if (mine.size() >= expected) {
        return mine;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return suggestionRepository.findAll().stream()
        .filter(s -> s.getPlanId().equals(planId))
        .toList();
  }

  private void assertNoSuggestionsAfterSettle(UUID planId) {
    // Give the AFTER_COMMIT REQUIRES_NEW listener time to (not) write, then assert empty.
    try {
      TimeUnit.SECONDS.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(
            suggestionRepository.findAll().stream()
                .filter(s -> s.getPlanId().equals(planId))
                .toList())
        .isEmpty();
  }

  // ---- Provisions -----------------------------------------------------------------------------

  @Test
  void provisionChanged_material_writesSuggestion_withProvisionsTrigger() {
    UUID userId = UUID.randomUUID();
    UUID householdId = seedHousehold(userId);
    Plan plan = seedPlan(householdId);

    // ItemSpoiled carries no mapping key -> conservative over-trigger -> material.
    publishInTx(
        new ItemSpoiledEvent(
            userId,
            List.of(UUID.randomUUID()),
            "fridge failure",
            UUID.randomUUID(),
            Instant.now()));

    List<MealPrepPlanReoptSuggestion> mine = awaitSuggestions(plan.getId(), 1);
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).getTriggerKind()).isEqualTo(ReoptTriggerKind.PROVISIONS);
  }

  @Test
  void provisionChanged_immaterial_itemAddedFromGrocery_writesNothing() {
    UUID userId = UUID.randomUUID();
    UUID householdId = seedHousehold(userId);
    Plan plan = seedPlan(householdId);

    publishInTx(
        new ItemAddedFromGroceryEvent(
            userId,
            List.of(UUID.randomUUID()),
            "Tesco",
            "ref-1",
            UUID.randomUUID(),
            Instant.now()));

    assertNoSuggestionsAfterSettle(plan.getId());
  }

  @Test
  void provisionChanged_crossHousehold_doesNotTouchOtherHouseholdsPlan() {
    UUID userA = UUID.randomUUID();
    UUID householdA = seedHousehold(userA); // userA's real household — has NO seeded plan
    UUID householdB = UUID.randomUUID();
    Plan planB = seedPlan(householdB); // a different household's plan; userA is not a member

    publishInTx(
        new ItemSpoiledEvent(
            userA, List.of(UUID.randomUUID()), "spoiled", UUID.randomUUID(), Instant.now()));

    assertThat(householdA).isNotEqualTo(householdB);
    assertNoSuggestionsAfterSettle(planB.getId());
  }

  // ---- Nutrition ------------------------------------------------------------------------------

  @Test
  void nutritionDiverged_material_writesSuggestion_withNutritionTrigger() {
    UUID userId = UUID.randomUUID();
    UUID householdId = seedHousehold(userId);
    Plan plan = seedPlan(householdId);

    DivergenceSummaryDto summary =
        new DivergenceSummaryDto(
            Map.of("protein", java.math.BigDecimal.TEN),
            Map.of("protein", java.math.BigDecimal.ONE),
            Map.of("protein", new java.math.BigDecimal("0.40")));
    publishInTx(
        new NutritionIntakeDivergedEvent(
            userId,
            WEEK.plusDays(1),
            Set.of("protein"),
            summary,
            UUID.randomUUID(),
            Instant.now()));

    List<MealPrepPlanReoptSuggestion> mine = awaitSuggestions(plan.getId(), 1);
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).getTriggerKind()).isEqualTo(ReoptTriggerKind.NUTRITION);
  }

  @Test
  void nutritionDiverged_immaterial_belowThreshold_writesNothing() {
    UUID userId = UUID.randomUUID();
    UUID householdId = seedHousehold(userId);
    Plan plan = seedPlan(householdId);

    DivergenceSummaryDto summary =
        new DivergenceSummaryDto(
            Map.of("protein", java.math.BigDecimal.TEN),
            Map.of("protein", java.math.BigDecimal.ONE),
            Map.of("protein", new java.math.BigDecimal("0.05"))); // 5% < 15% threshold
    publishInTx(
        new NutritionIntakeDivergedEvent(
            userId,
            WEEK.plusDays(1),
            Set.of("protein"),
            summary,
            UUID.randomUUID(),
            Instant.now()));

    assertNoSuggestionsAfterSettle(plan.getId());
  }

  // ---- Preference -----------------------------------------------------------------------------

  @Test
  void preferenceUpdated_material_writesSuggestion_withPreferenceTrigger() {
    UUID userId = UUID.randomUUID();
    UUID householdId = seedHousehold(userId);
    Plan plan = seedPlan(householdId);

    publishInTx(
        new HardConstraintsUpdatedEvent(
            userId, Set.of("allergies"), UUID.randomUUID(), Instant.now()));

    List<MealPrepPlanReoptSuggestion> mine = awaitSuggestions(plan.getId(), 1);
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).getTriggerKind()).isEqualTo(ReoptTriggerKind.PREFERENCE);
  }

  @Test
  void preferenceUpdated_immaterial_emptyFieldSet_writesNothing() {
    UUID userId = UUID.randomUUID();
    UUID householdId = seedHousehold(userId);
    Plan plan = seedPlan(householdId);

    publishInTx(
        new HardConstraintsUpdatedEvent(userId, Set.of(), UUID.randomUUID(), Instant.now()));

    assertNoSuggestionsAfterSettle(plan.getId());
  }

  // ---- Household ------------------------------------------------------------------------------

  @Test
  void householdConfigChanged_material_writesSuggestion_withHouseholdSettingsTrigger() {
    UUID householdId = UUID.randomUUID();
    Plan plan = seedPlan(householdId);

    publishInTx(
        new com.example.mealprep.household.event.HouseholdSettingsChangedEvent(
            householdId,
            UUID.randomUUID(),
            Set.of("mealStructure.dinnerEnabled"),
            UUID.randomUUID(),
            Instant.now()));

    List<MealPrepPlanReoptSuggestion> mine = awaitSuggestions(plan.getId(), 1);
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).getTriggerKind()).isEqualTo(ReoptTriggerKind.HOUSEHOLD_SETTINGS);
  }

  @Test
  void householdConfigChanged_immaterial_cosmeticOnly_writesNothing() {
    UUID householdId = UUID.randomUUID();
    Plan plan = seedPlan(householdId);

    publishInTx(
        new com.example.mealprep.household.event.HouseholdSettingsChangedEvent(
            householdId,
            UUID.randomUUID(),
            Set.of("displayName", "timezone"),
            UUID.randomUUID(),
            Instant.now()));

    assertNoSuggestionsAfterSettle(plan.getId());
  }

  // ---- Idempotency ----------------------------------------------------------------------------

  @Test
  void duplicatePublishOfSameEvent_isIdempotent_noSecondSuggestion() {
    UUID householdId = UUID.randomUUID();
    Plan plan = seedPlan(householdId);
    UUID traceId = UUID.randomUUID();
    UUID settingsId = UUID.randomUUID();

    // Same trace + same scope -> same derived triggerEventId -> coordinator dedupes.
    com.example.mealprep.household.event.HouseholdSettingsChangedEvent event =
        new com.example.mealprep.household.event.HouseholdSettingsChangedEvent(
            householdId, settingsId, Set.of("mealStructure.dinnerEnabled"), traceId, Instant.now());

    publishInTx(event);
    awaitSuggestions(plan.getId(), 1);
    publishInTx(event);

    try {
      TimeUnit.SECONDS.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(
            suggestionRepository.findAll().stream()
                .filter(s -> s.getPlanId().equals(plan.getId()))
                .toList())
        .hasSize(1);
  }

  /** Inline Stage-A/Stage-C wiring — identical strategy to {@code MidWeekReoptFlowIT}. */
  @TestConfiguration
  static class ReoptTestWiring {

    // planner-01j merged the production ReoptContextBuilder (PlanCompositionContextBuilder); this
    // IT keeps its deterministic in-line builder as @Primary so the listener/materiality
    // assertions stay deterministic and MidWeekReoptCoordinator's single-ReoptContextBuilder
    // injection is unambiguous (matches MidWeekReoptFlowIT; round-6 retro: test stand-in carries
    // @Primary, not the prod impl).
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
                  600,
                  true,
                  new ArrayList<>()));
          pool.add(
              PlanTestData.recipeFor(UUID.randomUUID(), slot.getKind(), 30, List.of(), List.of()));
        }
        return new PlanCompositionContext(
            activePlan.getHouseholdId(),
            activePlan.getWeekStartDate(),
            skeletons,
            Map.of(),
            Map.of(),
            null,
            null,
            null,
            new RecipePoolSnapshot(pool, Instant.now()),
            pinnedAssignments,
            traceId,
            UUID.randomUUID(),
            Map.of());
      };
    }

    @Bean
    ReoptStageCInvoker reoptStageCInvoker() {
      return (candidates, rollups, context, traceId) ->
          new ReoptStageCInvoker.Result(0, "IT: deterministic top candidate");
    }
  }
}
