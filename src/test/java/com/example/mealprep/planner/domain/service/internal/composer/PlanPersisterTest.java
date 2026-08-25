package com.example.mealprep.planner.domain.service.internal.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.lifecycle.PlanStateMachine;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit tests over {@link PlanPersister}'s aggregate-materialisation logic. {@link
 * PlanRepository} is mocked (infra); the real {@link PlanStateMachine} enforces the DRAFT&rarr;
 * GENERATED guard (playbook: never mock within the module under test). The aggregate-build IT lives
 * in {@code PlanComposerIT}; this targets the day-grouping / ordering / defaulting branches.
 */
@ExtendWith(MockitoExtension.class)
class PlanPersisterTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 5, 18);

  @Mock private PlanRepository planRepository;
  private PlanPersister persister;

  @BeforeEach
  void setUp() {
    persister = new PlanPersister(planRepository, new PlanStateMachine());
    when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private GeneratePlanRequest req(UUID householdId) {
    return new GeneratePlanRequest(householdId, WEEK, false);
  }

  private PlanCompositionContext ctx() {
    return PlanTestData.minimalContext(List.of(), List.of());
  }

  private SlotAssignment assignment(
      UUID slotId, UUID recipeId, LocalDate onDate, int slotIndex, int servings) {
    return PlanTestData.assignment(slotId, recipeId, onDate, slotIndex, servings);
  }

  @Test
  void persist_computesGenerationAndReplacesPlanId_setsGeneratedStatus() {
    UUID householdId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    Plan active = PlanTestData.testActivePlan(householdId, WEEK, 2);
    when(planRepository.countByHouseholdIdAndWeekStartDate(householdId, WEEK)).thenReturn(2);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            householdId, WEEK, PlanStatus.ACTIVE))
        .thenReturn(Optional.of(active));

    UUID recipeId = UUID.randomUUID();
    CandidatePlan chosen =
        new CandidatePlan(
            UUID.randomUUID(),
            WEEK,
            List.of(assignment(UUID.randomUUID(), recipeId, WEEK, 0, 2)),
            new ScoreResult(BigDecimal.ONE, PlanTestData.zeroScoreBreakdown()));

    Plan result =
        persister.persist(
            chosen,
            req(householdId),
            ctx(),
            planId,
            PlanTestData.emptyRollup(),
            true,
            false,
            false);

    assertThat(result.getId()).isEqualTo(planId);
    assertThat(result.getGeneration()).isEqualTo(3); // 1 + count(2)
    assertThat(result.getReplacesPlanId()).isEqualTo(active.getId());
    assertThat(result.getStatus()).isEqualTo(PlanStatus.GENERATED);
    assertThat(result.isAiAugmented()).isTrue();
    assertThat(result.isQualityWarning()).isFalse();
  }

  @Test
  void persist_noActivePlan_replacesPlanIdNull_generationOne() {
    UUID householdId = UUID.randomUUID();
    when(planRepository.countByHouseholdIdAndWeekStartDate(householdId, WEEK)).thenReturn(0);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            eq(householdId), eq(WEEK), eq(PlanStatus.ACTIVE)))
        .thenReturn(Optional.empty());

    CandidatePlan chosen =
        new CandidatePlan(UUID.randomUUID(), WEEK, List.of(), new ScoreResult(null, null));

    Plan result =
        persister.persist(
            chosen,
            req(householdId),
            ctx(),
            UUID.randomUUID(),
            PlanTestData.emptyRollup(),
            false,
            true,
            false);

    assertThat(result.getGeneration()).isEqualTo(1);
    assertThat(result.getReplacesPlanId()).isNull();
    assertThat(result.isQualityWarning()).isTrue();
    // scoreResult.breakdown() null -> zero breakdown fallback (uniform v1).
    ScoreBreakdownDocument bd = result.getScoreBreakdown();
    assertThat(bd.weightSchemeVersion()).isEqualTo("v1-uniform");
    assertThat(bd.composite()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void persist_groupsAssignmentsByDay_inWeekThenSlotOrder() {
    UUID householdId = UUID.randomUUID();
    when(planRepository.countByHouseholdIdAndWeekStartDate(householdId, WEEK)).thenReturn(0);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());

    // PlanPersister groups by SlotAssignment.onDate(); slots sharing a date collapse to one Day
    // regardless of dayId. (Here same-date slots also share a dayId, which still groups correctly.)
    UUID day1Id = UUID.randomUUID();
    UUID day2Id = UUID.randomUUID();
    SlotAssignment d2s1 =
        new SlotAssignment(
            day2Id,
            UUID.randomUUID(),
            1,
            WEEK.plusDays(1),
            SlotKind.DINNER,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    SlotAssignment d1s1 =
        new SlotAssignment(
            day1Id,
            UUID.randomUUID(),
            1,
            WEEK,
            SlotKind.DINNER,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    SlotAssignment d1s0 =
        new SlotAssignment(
            day1Id,
            UUID.randomUUID(),
            0,
            WEEK,
            SlotKind.BREAKFAST,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    // Deliberately supply out-of-order assignments across two days.
    CandidatePlan chosen =
        new CandidatePlan(
            UUID.randomUUID(), WEEK, List.of(d2s1, d1s1, d1s0), new ScoreResult(null, null));

    Plan result =
        persister.persist(
            chosen,
            req(householdId),
            ctx(),
            UUID.randomUUID(),
            PlanTestData.emptyRollup(),
            false,
            false,
            false);

    assertThat(result.getDays()).hasSize(2);
    Day day1 = result.getDays().get(0);
    assertThat(day1.getOnDate()).isEqualTo(WEEK);
    // Sorted by (onDate, slotIndex): day-1 slot 0 then slot 1.
    assertThat(day1.getSlots().get(0).getSlotIndex()).isEqualTo(0);
    assertThat(day1.getSlots().get(1).getSlotIndex()).isEqualTo(1);
    assertThat(result.getDays().get(1).getOnDate()).isEqualTo(WEEK.plusDays(1));
  }

  @Test
  void persist_multiKindSlotsOnSameDateWithDistinctDayIds_collapseToOneDay() {
    // Regression (recipe-pool live find): the slot skeleton mints a DISTINCT dayId per slot kind,
    // so a real multi-kind day (breakfast + dinner on the SAME date) arrives as assignments sharing
    // an onDate but with different dayIds. They MUST collapse into ONE Day — planner_days is
    // UNIQUE(plan_id, on_date); grouping by dayId produced duplicate (plan_id, on_date) rows and a
    // 23505 violation, masked for the project's life while NoOpRecipePoolSource kept plans empty.
    UUID householdId = UUID.randomUUID();
    when(planRepository.countByHouseholdIdAndWeekStartDate(householdId, WEEK)).thenReturn(0);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());

    SlotAssignment breakfast =
        new SlotAssignment(
            UUID.randomUUID(), // distinct dayId
            UUID.randomUUID(),
            0,
            WEEK,
            SlotKind.BREAKFAST,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    SlotAssignment dinner =
        new SlotAssignment(
            UUID.randomUUID(), // different dayId, SAME date
            UUID.randomUUID(),
            2,
            WEEK,
            SlotKind.DINNER,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    CandidatePlan chosen =
        new CandidatePlan(
            UUID.randomUUID(), WEEK, List.of(breakfast, dinner), new ScoreResult(null, null));

    Plan result =
        persister.persist(
            chosen,
            req(householdId),
            ctx(),
            UUID.randomUUID(),
            PlanTestData.emptyRollup(),
            false,
            false,
            false);

    assertThat(result.getDays()).hasSize(1);
    Day only = result.getDays().get(0);
    assertThat(only.getOnDate()).isEqualTo(WEEK);
    assertThat(only.getSlots()).hasSize(2);
  }

  @Test
  void persist_assignmentWithRecipe_buildsScheduledRecipe_defaultsServingsAndIds() {
    UUID householdId = UUID.randomUUID();
    when(planRepository.countByHouseholdIdAndWeekStartDate(householdId, WEEK)).thenReturn(0);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());

    UUID recipeId = UUID.randomUUID();
    // servings 0 -> defaults to 1; version/branch null -> default to recipeId.
    SlotAssignment a =
        new SlotAssignment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK,
            SlotKind.DINNER,
            recipeId,
            null,
            null,
            0,
            false);
    CandidatePlan chosen =
        new CandidatePlan(UUID.randomUUID(), WEEK, List.of(a), new ScoreResult(null, null));

    Plan result =
        persister.persist(
            chosen,
            req(householdId),
            ctx(),
            UUID.randomUUID(),
            PlanTestData.emptyRollup(),
            false,
            false,
            false);

    ScheduledRecipe sr = result.getDays().get(0).getSlots().get(0).getScheduledRecipe();
    assertThat(sr.getRecipeId()).isEqualTo(recipeId);
    assertThat(sr.getServings()).isEqualTo(1); // 0 -> default 1
    assertThat(sr.getRecipeVersionId()).isEqualTo(recipeId); // null -> recipeId
    assertThat(sr.getRecipeBranchId()).isEqualTo(recipeId);
  }

  @Test
  void persist_assignmentWithoutRecipe_slotHasNoScheduledRecipe() {
    UUID householdId = UUID.randomUUID();
    when(planRepository.countByHouseholdIdAndWeekStartDate(householdId, WEEK)).thenReturn(0);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());

    SlotAssignment noRecipe =
        new SlotAssignment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK,
            SlotKind.LUNCH,
            null,
            null,
            null,
            2,
            false);
    CandidatePlan chosen =
        new CandidatePlan(UUID.randomUUID(), WEEK, List.of(noRecipe), new ScoreResult(null, null));

    Plan result =
        persister.persist(
            chosen,
            req(householdId),
            ctx(),
            UUID.randomUUID(),
            PlanTestData.emptyRollup(),
            false,
            false,
            false);

    assertThat(result.getDays().get(0).getSlots().get(0).getScheduledRecipe()).isNull();
  }

  @Test
  void persist_slotWithMatchingSkeleton_carriesEatersSharedLabelAndTimeBudget() {
    UUID householdId = UUID.randomUUID();
    when(planRepository.countByHouseholdIdAndWeekStartDate(householdId, WEEK)).thenReturn(0);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());

    UUID eaterA = UUID.randomUUID();
    UUID eaterB = UUID.randomUUID();
    UUID sharedSlotId = UUID.randomUUID();
    UUID soloSlotId = UUID.randomUUID();
    MealSlotSkeleton sharedDinner =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            sharedSlotId,
            1,
            WEEK,
            SlotKind.DINNER,
            "family-dinner",
            45,
            true,
            List.of(eaterA, eaterB));
    MealSlotSkeleton soloLunch =
        new MealSlotSkeleton(
            UUID.randomUUID(),
            soloSlotId,
            0,
            WEEK,
            SlotKind.LUNCH,
            "packed-lunch",
            25,
            false,
            List.of(eaterA));
    PlanCompositionContext context =
        PlanTestData.minimalContext(List.of(sharedDinner, soloLunch), List.of());

    CandidatePlan chosen =
        new CandidatePlan(
            UUID.randomUUID(),
            WEEK,
            List.of(
                assignment(soloSlotId, UUID.randomUUID(), WEEK, 0, 1),
                assignment(sharedSlotId, UUID.randomUUID(), WEEK, 1, 2)),
            new ScoreResult(null, null));

    Plan result =
        persister.persist(
            chosen,
            req(householdId),
            context,
            UUID.randomUUID(),
            PlanTestData.emptyRollup(),
            false,
            false,
            false);

    var slots = result.getDays().get(0).getSlots();
    var lunch = slots.get(0);
    assertThat(lunch.getEaters()).containsExactly(eaterA);
    assertThat(lunch.isShared()).isFalse();
    assertThat(lunch.getLabel()).isEqualTo("packed-lunch");
    assertThat(lunch.getTimeBudgetMin()).isEqualTo(25);
    var dinner = slots.get(1);
    assertThat(dinner.getEaters()).containsExactly(eaterA, eaterB);
    assertThat(dinner.isShared()).isTrue();
    assertThat(dinner.getLabel()).isEqualTo("family-dinner");
    assertThat(dinner.getTimeBudgetMin()).isEqualTo(45);
  }

  @Test
  void persist_assignmentWithoutSkeleton_fallsBackToSharedSlotWithEaterUnion() {
    UUID householdId = UUID.randomUUID();
    when(planRepository.countByHouseholdIdAndWeekStartDate(householdId, WEEK)).thenReturn(0);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());

    UUID eaterA = UUID.randomUUID();
    UUID eaterB = UUID.randomUUID();
    // Two skeletons whose slot ids do NOT match the assignment; eaterA appears in both so the
    // fallback union must de-duplicate.
    MealSlotSkeleton skelOne =
        PlanTestData.skeletonWithEaters(UUID.randomUUID(), WEEK, 0, List.of(eaterA));
    MealSlotSkeleton skelTwo =
        PlanTestData.skeletonWithEaters(UUID.randomUUID(), WEEK, 1, List.of(eaterA, eaterB));
    PlanCompositionContext context =
        PlanTestData.minimalContext(List.of(skelOne, skelTwo), List.of());

    CandidatePlan chosen =
        new CandidatePlan(
            UUID.randomUUID(),
            WEEK,
            List.of(assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2)),
            new ScoreResult(null, null));

    Plan result =
        persister.persist(
            chosen,
            req(householdId),
            context,
            UUID.randomUUID(),
            PlanTestData.emptyRollup(),
            false,
            false,
            false);

    var slot = result.getDays().get(0).getSlots().get(0);
    assertThat(slot.getEaters()).containsExactly(eaterA, eaterB);
    assertThat(slot.isShared()).isTrue();
    assertThat(slot.getLabel()).isEqualTo(SlotKind.DINNER.name());
    assertThat(slot.getTimeBudgetMin()).isEqualTo(0);
  }

  @Test
  void persist_nullAssignments_persistsEmptyDayGraph() {
    UUID householdId = UUID.randomUUID();
    when(planRepository.countByHouseholdIdAndWeekStartDate(householdId, WEEK)).thenReturn(0);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(any(), any(), any()))
        .thenReturn(Optional.empty());

    CandidatePlan chosen =
        new CandidatePlan(UUID.randomUUID(), WEEK, null, new ScoreResult(null, null));

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    Plan result =
        persister.persist(
            chosen,
            req(householdId),
            ctx(),
            UUID.randomUUID(),
            PlanTestData.emptyRollup(),
            false,
            false,
            false);

    org.mockito.Mockito.verify(planRepository).save(saved.capture());
    assertThat(result.getDays()).isEmpty();
    assertThat(saved.getValue().getStatus()).isEqualTo(PlanStatus.GENERATED);
  }
}
