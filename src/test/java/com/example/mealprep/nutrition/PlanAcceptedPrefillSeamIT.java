package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.AugmentationResult;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.PlanWriteService;
import com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchEngine;
import com.example.mealprep.planner.domain.service.internal.composer.PlanComposer;
import com.example.mealprep.planner.domain.service.internal.composer.PlanCompositionContextBuilder;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.domain.service.internal.stagec.Augmentation;
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cross-module seam IT for the accepted-plan intake pre-fill (D-0008): compose through the REAL
 * {@code PlanPersister}, accept through the real lifecycle service, and assert the {@code
 * AFTER_COMMIT} listener created intake rows for the slot's eaters.
 *
 * <p>This is the test whose absence shipped a broken chain: the persister wrote every slot with
 * {@code eaters: []} while the listener's unit test mocked eaters as populated, so both sides
 * passed against a mock of the other and no real plan ever pre-filled anything. The deterministic
 * planner stages are mocked exactly as in {@code PlanComposerIT}; everything downstream of the
 * skeletons (persist &rarr; accept &rarr; event &rarr; listener &rarr; intake writes) is real, over
 * a real Postgres.
 *
 * <p>{@code acceptPlan} is deliberately called outside any test transaction so its own commit fires
 * the {@code AFTER_COMMIT} listener synchronously on this thread.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
// Empty mocked recipe pool: keep the cold-start gate out of this seam, as in PlanComposerIT.
@org.springframework.test.context.TestPropertySource(
    properties = "mealprep.planner.cold-start.enabled=false")
class PlanAcceptedPrefillSeamIT {

  private static final LocalDate WEEK =
      LocalDate.now().plusYears(41).with(java.time.DayOfWeek.MONDAY);

  @Autowired private PlanComposer composer;
  @Autowired private PlanWriteService planWriteService;
  @Autowired private PlanRepository planRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockBean private PlanCompositionContextBuilder contextBuilder;
  @MockBean private BeamSearchEngine beamSearchEngine;
  @MockBean private RollupBuilder rollupBuilder;
  @MockBean private StageCInvoker stageCInvoker;
  @MockBean private Phase2Augmenter phase2Augmenter;
  @MockBean private AdaptationService adaptationService;

  // AdaptationServiceImpl implements both adaptation interfaces; mocking only one evicts the
  // shared impl bean and breaks context load. Mock the sibling too (same as PlanComposerIT).
  @MockBean private AdaptationQueryService adaptationQueryService;

  private final UUID eaterA = UUID.randomUUID();
  private final UUID eaterB = UUID.randomUUID();
  private final UUID soloLunchSlotId = UUID.randomUUID();
  private final UUID sharedDinnerSlotId = UUID.randomUUID();
  private final UUID dinnerRecipeId = UUID.randomUUID();

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_intake_audit");
    jdbcTemplate.update("DELETE FROM nutrition_intake_snack");
    jdbcTemplate.update("DELETE FROM nutrition_intake_slot");
    jdbcTemplate.update("DELETE FROM nutrition_intake_day");
    jdbcTemplate.update("DELETE FROM planner_plan_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM planner_plans");
    jdbcTemplate.update("DELETE FROM decision_log");
    jdbcTemplate.update("DELETE FROM core_lock_leases");
  }

  /** A per-person lunch for eater A and a shared dinner for A + B, both on the week's Monday. */
  private PlanCompositionContext context(UUID household) {
    MealSlotSkeleton soloLunch =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            soloLunchSlotId,
            0,
            WEEK,
            SlotKind.LUNCH,
            "lunch",
            20,
            false,
            List.of(eaterA));
    MealSlotSkeleton sharedDinner =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            sharedDinnerSlotId,
            1,
            WEEK,
            SlotKind.DINNER,
            "dinner",
            45,
            true,
            List.of(eaterA, eaterB));
    return new PlanCompositionContext(
        household,
        WEEK,
        List.of(soloLunch, sharedDinner),
        Map.of(),
        Map.of(),
        null,
        null,
        null,
        new RecipePoolSnapshot(List.of(), Instant.now()),
        List.of(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Map.of());
  }

  private CandidatePlan candidate() {
    SlotAssignment lunch =
        new SlotAssignment(
            UUID.randomUUID(),
            soloLunchSlotId,
            0,
            WEEK,
            SlotKind.LUNCH,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            false);
    SlotAssignment dinner =
        new SlotAssignment(
            UUID.randomUUID(),
            sharedDinnerSlotId,
            1,
            WEEK,
            SlotKind.DINNER,
            dinnerRecipeId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    return new CandidatePlan(
        UUID.randomUUID(),
        WEEK,
        List.of(lunch, dinner),
        new ScoreResult(BigDecimal.ONE, PlanTestData.zeroScoreBreakdown()));
  }

  private void wireDeterministicStages(UUID household) {
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(context(household));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(candidate()), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "picked", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new AugmentationResult(List.<Augmentation>of(), List.<Augmentation>of(), List.of()));
  }

  @Test
  void composePersistAccept_prefillsIntakeSlotsForTheSlotsEaters() {
    UUID household = UUID.randomUUID();
    wireDeterministicStages(household);

    UUID planId =
        tx().execute(
                t ->
                    composer.compose(
                        new GeneratePlanRequest(household, WEEK, false), UUID.randomUUID(), null));
    assertThat(planId).isNotNull();

    // The persisted rows must already carry the skeletons' composition. Lazy graph walk needs a
    // session: OSIV is off.
    tx().executeWithoutResult(
            t -> {
              Plan persisted = planRepository.findById(planId).orElseThrow();
              List<MealSlot> slots = persisted.getDays().get(0).getSlots();
              assertThat(slots).hasSize(2);
              MealSlot lunch = slotOfKind(slots, SlotKind.LUNCH);
              assertThat(lunch.isShared()).isFalse();
              assertThat(lunch.getEaters()).containsExactly(eaterA);
              MealSlot dinner = slotOfKind(slots, SlotKind.DINNER);
              assertThat(dinner.isShared()).isTrue();
              assertThat(dinner.getEaters()).containsExactly(eaterA, eaterB);
            });

    planWriteService.acceptPlan(planId);

    // Eater A ate both meals: one intake day holding LUNCH + DINNER slots for the plan.
    assertThat(intakeSlotKinds(eaterA, planId)).containsExactlyInAnyOrder("LUNCH", "DINNER");
    // Eater B only shared the dinner.
    assertThat(intakeSlotKinds(eaterB, planId)).containsExactly("DINNER");

    // The pre-filled dinner slot points at the scheduled recipe and starts PENDING.
    Map<String, Object> dinnerRow =
        jdbcTemplate.queryForMap(
            "SELECT s.planned_recipe_id, s.actual_status FROM nutrition_intake_slot s"
                + " JOIN nutrition_intake_day d ON s.intake_day_id = d.id"
                + " WHERE d.user_id = ? AND d.on_date = ? AND s.meal_slot = 'DINNER'",
            eaterB,
            WEEK);
    assertThat(dinnerRow.get("planned_recipe_id")).hasToString(dinnerRecipeId.toString());
    assertThat(dinnerRow.get("actual_status")).isEqualTo("PENDING");
  }

  private static MealSlot slotOfKind(List<MealSlot> slots, SlotKind kind) {
    return slots.stream().filter(s -> s.getKind() == kind).findFirst().orElseThrow();
  }

  private List<String> intakeSlotKinds(UUID userId, UUID planId) {
    return jdbcTemplate.queryForList(
        "SELECT s.meal_slot FROM nutrition_intake_slot s"
            + " JOIN nutrition_intake_day d ON s.intake_day_id = d.id"
            + " WHERE d.user_id = ? AND d.on_date = ? AND d.plan_id = ?",
        String.class,
        userId,
        WEEK,
        planId);
  }
}
