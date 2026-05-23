package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.planner.api.dto.UpcomingSlotView;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealSchedule;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealTiming;
import com.example.mealprep.preference.domain.service.LifestyleConfigUpdateService;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Cross-module integration test for the planner-01m wall-clock meal-time resolution against real
 * Postgres. Exercises the three-level coalesce end-to-end through {@link
 * PlanQueryService#getUpcomingSlots}: stored override, household-owner lifestyle-config fallback,
 * and the slot-kind default floor (no-regression). The pure-logic cases live in {@code
 * UpcomingSlotTimeResolutionTest}; this IT proves the cross-module reads (planner → household →
 * preference) and the new {@code time} columns wire up correctly.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class UpcomingSlotTimeResolutionIT {

  @Autowired private PlanRepository planRepository;
  @Autowired private PlanQueryService planQueryService;
  @Autowired private HouseholdUpdateService householdUpdateService;
  @Autowired private LifestyleConfigUpdateService lifestyleConfigUpdateService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<UUID> seededOwners = new ArrayList<>();

  private TransactionTemplate txTemplate() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM planner_plans");
    for (UUID owner : seededOwners) {
      // Audit rows cascade-delete with the parent (FK ON DELETE CASCADE).
      jdbcTemplate.update("DELETE FROM preference_lifestyle_config WHERE user_id = ?", owner);
    }
    jdbcTemplate.update("DELETE FROM household_member");
    jdbcTemplate.update("DELETE FROM household");
    seededOwners.clear();
  }

  /** Create a household; the creator is seated as primary owner. Returns the new household id. */
  private UUID newHousehold(UUID ownerUserId) {
    HouseholdDto household =
        householdUpdateService.createHousehold(
            ownerUserId, new CreateHouseholdRequest("h-" + ownerUserId));
    return household.id();
  }

  private void seedDinnerSchedule(UUID ownerUserId, String range) {
    seededOwners.add(ownerUserId);
    LifestyleConfigDocument document =
        new LifestyleConfigDocument(
            null,
            new MealTiming(new MealSchedule(Map.of("dinner", range)), "flexible", null),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    lifestyleConfigUpdateService.initialise(
        ownerUserId,
        com.example.mealprep.preference.testdata.LifestyleConfigTestData.updateRequest(
            document, 0L));
  }

  /** Persist a one-day, single-DINNER-slot ACTIVE plan; the slot carries the given override. */
  private Plan persistDinnerPlan(UUID householdId, LocalDate week, LocalTime override) {
    Plan plan =
        Plan.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .weekStartDate(week)
            .generation(1)
            .status(PlanStatus.ACTIVE)
            .triggerKind(TriggerKind.USER_INITIATED)
            .qualityWarning(false)
            .coldStart(false)
            .aiAugmented(false)
            .traceId(UUID.randomUUID())
            .decisionId(UUID.randomUUID())
            .scoreBreakdown(PlanTestData.zeroScoreBreakdown())
            .rollupSummary(PlanTestData.emptyRollup())
            .days(new ArrayList<>())
            .build();
    Day day =
        Day.builder()
            .id(UUID.randomUUID())
            .plan(plan)
            .onDate(week)
            .slots(new ArrayList<>())
            .build();
    MealSlot slot =
        MealSlot.builder()
            .id(UUID.randomUUID())
            .day(day)
            .plan(plan)
            .slotIndex(0)
            .kind(SlotKind.DINNER)
            .label("Dinner")
            .timeBudgetMin(45)
            .mealTime(override)
            .shared(true)
            .eaters(new ArrayList<>(List.of(UUID.randomUUID())))
            .state(SlotState.PLANNED)
            .build();
    day.getSlots().add(slot);
    plan.getDays().add(day);
    txTemplate().executeWithoutResult(tx -> planRepository.save(plan));
    return plan;
  }

  @Test
  void getUpcomingSlots_lifestyleConfigFallback_resolvesRangeStart() {
    UUID owner = UUID.randomUUID();
    UUID householdId = newHousehold(owner);
    seedDinnerSchedule(owner, "18:30-19:30");
    LocalDate week = LocalDate.of(2026, 6, 15);
    persistDinnerPlan(householdId, week, null);

    List<UpcomingSlotView> views = planQueryService.getUpcomingSlots(householdId, week, week);

    assertThat(views).hasSize(1);
    assertThat(views.get(0).mealTime()).isEqualTo(LocalTime.of(18, 30));
    assertThat(views.get(0).prepStepAtTime()).isNull();
  }

  @Test
  void getUpcomingSlots_storedOverride_winsOverLifestyleConfig() {
    UUID owner = UUID.randomUUID();
    UUID householdId = newHousehold(owner);
    seedDinnerSchedule(owner, "18:30-19:30");
    LocalDate week = LocalDate.of(2026, 6, 15);
    persistDinnerPlan(householdId, week, LocalTime.of(20, 0));

    List<UpcomingSlotView> views = planQueryService.getUpcomingSlots(householdId, week, week);

    assertThat(views).hasSize(1);
    assertThat(views.get(0).mealTime()).isEqualTo(LocalTime.of(20, 0));
  }

  @Test
  void getUpcomingSlots_noLifestyleConfig_usesSlotKindDefault_noRegression() {
    UUID owner = UUID.randomUUID();
    UUID householdId = newHousehold(owner);
    // No lifestyle config seeded for the owner.
    LocalDate week = LocalDate.of(2026, 6, 15);
    persistDinnerPlan(householdId, week, null);

    List<UpcomingSlotView> views = planQueryService.getUpcomingSlots(householdId, week, week);

    assertThat(views).hasSize(1);
    assertThat(views.get(0).mealTime()).isEqualTo(LocalTime.of(18, 0)); // DINNER default
  }
}
