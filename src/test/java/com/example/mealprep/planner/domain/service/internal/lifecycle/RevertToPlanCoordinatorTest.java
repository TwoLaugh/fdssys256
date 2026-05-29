package com.example.mealprep.planner.domain.service.internal.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.lock.LeaseHandle;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.RevertToPlanRequest;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.planner.event.PlanSupersededEvent;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.RevertTargetNotInHistoryException;
import com.example.mealprep.planner.security.PlannerAuth;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.api.dto.Violation;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Pure-unit tests over {@link RevertToPlanCoordinator} (planner-5, the revert-to-historical flow).
 * Persistence + event publication + decision-log + the cross-module read surfaces are mocked; the
 * real {@link PlanStateMachine}, real {@link ObjectMapper} and a frozen {@link Clock} are used so
 * the strip / refill / supersede branching is exercised for real (playbook: never mock within the
 * module under test).
 *
 * <p>The {@code @Transactional} annotations on {@code hydrateTarget} / {@code persistAndPublish}
 * are inert here (no tx manager); the {@code self} proxy field is wired to the instance itself by
 * reflection so the self-invocations resolve to the real methods.
 */
@ExtendWith(MockitoExtension.class)
class RevertToPlanCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-05-18T10:15:30Z");
  private static final LocalDate WEEK = LocalDate.of(2026, 5, 18);

  @Mock private PlanRepository planRepository;
  @Mock private HardConstraintFilterService hardConstraintFilterService;
  @Mock private RecipeQueryService recipeQueryService;
  @Mock private PlannerAuth plannerAuth;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private DecisionLogWriter decisionLogWriter;
  @Mock private LockService lockService;

  private RevertToPlanCoordinator coordinator;

  @BeforeEach
  void setUp() throws Exception {
    coordinator =
        new RevertToPlanCoordinator(
            planRepository,
            new PlanStateMachine(),
            hardConstraintFilterService,
            recipeQueryService,
            plannerAuth,
            eventPublisher,
            decisionLogWriter,
            lockService,
            PlanTestData.scoringProperties(),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            null);
    // Wire the self-proxy field to the instance itself (no Spring proxy in a unit test).
    Field self = RevertToPlanCoordinator.class.getDeclaredField("self");
    self.setAccessible(true);
    self.set(coordinator, coordinator);
  }

  private LeaseHandle stubLease(UUID householdId) {
    LeaseHandle lease =
        new LeaseHandle(LockKey.forPlanWeek(householdId, WEEK), UUID.randomUUID(), NOW, NOW);
    lenient()
        .when(lockService.acquireLease(any(LockKey.class), any()))
        .thenReturn(Optional.of(lease));
    return lease;
  }

  // ---------- ownership guard (422) ----------

  @Test
  void revertToPlan_targetNotInCallerHousehold_throws422_noWrite() {
    UUID caller = UUID.randomUUID();
    Plan target =
        PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 2, PlanStatus.SUPERSEDED, 1, 1);
    when(planRepository.findById(target.getId())).thenReturn(Optional.of(target));
    when(plannerAuth.canAccessHousehold(caller, target.getHouseholdId())).thenReturn(false);

    assertThatThrownBy(
            () -> coordinator.revertToPlan(caller, new RevertToPlanRequest(target.getId())))
        .isInstanceOf(RevertTargetNotInHistoryException.class);

    verify(planRepository, never()).save(any());
    verify(lockService, never()).acquireLease(any(), any());
  }

  @Test
  void revertToPlan_targetNotFound_throws404() {
    UUID caller = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    when(planRepository.findById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> coordinator.revertToPlan(caller, new RevertToPlanRequest(missing)))
        .isInstanceOf(PlanNotFoundException.class);
    verify(lockService, never()).acquireLease(any(), any());
  }

  // ---------- happy path: copy + supersede + publish ----------

  @Test
  void revertToPlan_validTarget_copiesContent_supersedesActive_publishesBothEvents() {
    UUID caller = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    Plan target =
        PlanTestData.newPlanGraph(household, WEEK, 2, PlanStatus.SUPERSEDED, 2, 2); // 4 slots
    Plan active = PlanTestData.newPlanGraph(household, WEEK, 5, PlanStatus.ACTIVE, 1, 1);
    when(planRepository.findById(target.getId())).thenReturn(Optional.of(target));
    when(plannerAuth.canAccessHousehold(caller, household)).thenReturn(true);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            household, WEEK, PlanStatus.ACTIVE))
        .thenReturn(Optional.of(active));
    when(planRepository.countByHouseholdIdAndWeekStartDate(household, WEEK)).thenReturn(5);
    stubLease(household);
    // Every copied recipe still passes the caller's current hard constraints (no strip).
    allRecipesPass();

    UUID newId = coordinator.revertToPlan(caller, new RevertToPlanRequest(target.getId()));

    assertThat(newId).isNotEqualTo(target.getId()).isNotEqualTo(active.getId());
    assertThat(active.getStatus()).isEqualTo(PlanStatus.SUPERSEDED);

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    verify(planRepository, org.mockito.Mockito.times(2)).save(saved.capture());
    Plan copy =
        saved.getAllValues().stream()
            .filter(p -> p.getId().equals(newId))
            .findFirst()
            .orElseThrow();
    assertThat(copy.getStatus()).isEqualTo(PlanStatus.GENERATED);
    assertThat(copy.getGeneration()).isEqualTo(6); // 1 + count(5)
    assertThat(copy.getReplacesPlanId()).isEqualTo(active.getId());
    // Content copied from the TARGET (4 slots), not the active (1 slot).
    int copiedSlots = copy.getDays().stream().mapToInt(d -> d.getSlots().size()).sum();
    int targetSlots = target.getDays().stream().mapToInt(d -> d.getSlots().size()).sum();
    assertThat(copiedSlots).isEqualTo(targetSlots).isEqualTo(4);
    assertThat(copy.isQualityWarning()).isFalse();

    ArgumentCaptor<PlanSupersededEvent> sup = ArgumentCaptor.forClass(PlanSupersededEvent.class);
    ArgumentCaptor<PlanGeneratedEvent> gen = ArgumentCaptor.forClass(PlanGeneratedEvent.class);
    verify(eventPublisher).publishEvent(sup.capture());
    verify(eventPublisher).publishEvent(gen.capture());
    assertThat(sup.getValue().planId()).isEqualTo(active.getId());
    assertThat(sup.getValue().replacedByPlanId()).isEqualTo(newId);
    assertThat(gen.getValue().planId()).isEqualTo(newId);
    verify(lockService).releaseLease(any(LeaseHandle.class));
  }

  @Test
  void revertToPlan_noActivePlan_stillCreatesGeneration_noSupersedeEvent() {
    UUID caller = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    Plan target = PlanTestData.newPlanGraph(household, WEEK, 1, PlanStatus.SUPERSEDED, 1, 1);
    when(planRepository.findById(target.getId())).thenReturn(Optional.of(target));
    when(plannerAuth.canAccessHousehold(caller, household)).thenReturn(true);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            household, WEEK, PlanStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(planRepository.countByHouseholdIdAndWeekStartDate(household, WEEK)).thenReturn(1);
    stubLease(household);
    allRecipesPass();

    UUID newId = coordinator.revertToPlan(caller, new RevertToPlanRequest(target.getId()));

    assertThat(newId).isNotNull();
    // No active plan ⇒ replacesPlanId falls back to the target; no PlanSupersededEvent published.
    verify(eventPublisher, never()).publishEvent(any(PlanSupersededEvent.class));
    verify(eventPublisher).publishEvent(any(PlanGeneratedEvent.class));
  }

  // ---------- safety-relevant strip + refill ----------

  @Test
  void revertToPlan_recipeNowBanned_strippedAndSlotRefilled() {
    UUID caller = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    // Single shared DINNER slot whose copied recipe is now banned.
    Plan target = singleSlotTarget(household, SlotKind.DINNER);
    UUID bannedRecipeId =
        target.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId();
    when(planRepository.findById(target.getId())).thenReturn(Optional.of(target));
    when(plannerAuth.canAccessHousehold(caller, household)).thenReturn(true);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            household, WEEK, PlanStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(planRepository.countByHouseholdIdAndWeekStartDate(household, WEEK)).thenReturn(1);
    stubLease(household);

    // The banned recipe's ingredient keys fail the household check; the replacement passes.
    RecipeDto banned =
        PlanTestData.recipeFor(bannedRecipeId, SlotKind.DINNER, 25, List.of(), List.of("peanut"));
    UUID safeRecipeId = UUID.randomUUID();
    RecipeDto safe =
        PlanTestData.recipeFor(safeRecipeId, SlotKind.DINNER, 25, List.of(), List.of("rice"));
    when(recipeQueryService.getById(bannedRecipeId)).thenReturn(Optional.of(banned));
    when(recipeQueryService.findPlannableCandidates(eq(caller), anyInt()))
        .thenReturn(List.of(safe));
    // peanut fails, rice passes (shared slot → checkForHousehold).
    when(hardConstraintFilterService.checkForHousehold(anyList(), eq(List.of("peanut"))))
        .thenReturn(new FilterResult(false, List.of(banViolation(caller, bannedRecipeId))));
    when(hardConstraintFilterService.checkForHousehold(anyList(), eq(List.of("rice"))))
        .thenReturn(new FilterResult(true, List.of()));

    UUID newId = coordinator.revertToPlan(caller, new RevertToPlanRequest(target.getId()));

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    verify(planRepository).save(saved.capture());
    Plan copy = saved.getValue();
    assertThat(copy.getId()).isEqualTo(newId);
    ScheduledRecipe sr = copy.getDays().get(0).getSlots().get(0).getScheduledRecipe();
    // Banned recipe stripped, slot refilled with the safe replacement (slot is NOT empty).
    assertThat(sr).isNotNull();
    assertThat(sr.getRecipeId()).isEqualTo(safeRecipeId).isNotEqualTo(bannedRecipeId);
    assertThat(copy.isQualityWarning()).isFalse(); // refilled ⇒ complete plan
  }

  @Test
  void revertToPlan_perPersonSlot_recipeBannedForOneEater_strippedAndRefilled() {
    UUID caller = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    Plan target = singleSlotTarget(household, SlotKind.DINNER);
    MealSlot slot = target.getDays().get(0).getSlots().get(0);
    // Per-person slot with two eaters; the copied recipe is banned for the SECOND eater only.
    slot.setShared(false);
    UUID eaterA = UUID.randomUUID();
    UUID eaterB = UUID.randomUUID();
    slot.setEaters(new java.util.ArrayList<>(List.of(eaterA, eaterB)));
    UUID bannedRecipeId = slot.getScheduledRecipe().getRecipeId();

    when(planRepository.findById(target.getId())).thenReturn(Optional.of(target));
    when(plannerAuth.canAccessHousehold(caller, household)).thenReturn(true);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            household, WEEK, PlanStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(planRepository.countByHouseholdIdAndWeekStartDate(household, WEEK)).thenReturn(1);
    stubLease(household);

    RecipeDto banned =
        PlanTestData.recipeFor(bannedRecipeId, SlotKind.DINNER, 25, List.of(), List.of("peanut"));
    UUID safeRecipeId = UUID.randomUUID();
    RecipeDto safe =
        PlanTestData.recipeFor(safeRecipeId, SlotKind.DINNER, 25, List.of(), List.of("rice"));
    when(recipeQueryService.getById(bannedRecipeId)).thenReturn(Optional.of(banned));
    when(recipeQueryService.findPlannableCandidates(eq(caller), anyInt()))
        .thenReturn(List.of(safe));
    // Per-person path uses check(eater, keys): eaterA passes peanut, eaterB does NOT → strip.
    when(hardConstraintFilterService.check(eq(eaterA), eq(List.of("peanut"))))
        .thenReturn(new FilterResult(true, List.of()));
    when(hardConstraintFilterService.check(eq(eaterB), eq(List.of("peanut"))))
        .thenReturn(new FilterResult(false, List.of(banViolation(eaterB, bannedRecipeId))));
    // The replacement (rice) passes for BOTH eaters.
    when(hardConstraintFilterService.check(any(), eq(List.of("rice"))))
        .thenReturn(new FilterResult(true, List.of()));

    UUID newId = coordinator.revertToPlan(caller, new RevertToPlanRequest(target.getId()));

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    verify(planRepository).save(saved.capture());
    ScheduledRecipe sr = saved.getValue().getDays().get(0).getSlots().get(0).getScheduledRecipe();
    assertThat(sr).isNotNull();
    assertThat(sr.getRecipeId()).isEqualTo(safeRecipeId);
    assertThat(saved.getValue().getId()).isEqualTo(newId);
  }

  @Test
  void
      revertToPlan_refill_skipsWrongKindAndOverBudget_picksMatchingCandidate_mapsVersionAndServings() {
    UUID caller = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    Plan target = singleSlotTarget(household, SlotKind.DINNER);
    MealSlot slot = target.getDays().get(0).getSlots().get(0);
    slot.setShared(true);
    slot.setTimeBudgetMin(30); // overshoot cap = 30 * 1.5 = 45 mins
    UUID bannedRecipeId = slot.getScheduledRecipe().getRecipeId();

    when(planRepository.findById(target.getId())).thenReturn(Optional.of(target));
    when(plannerAuth.canAccessHousehold(caller, household)).thenReturn(true);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            household, WEEK, PlanStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(planRepository.countByHouseholdIdAndWeekStartDate(household, WEEK)).thenReturn(1);
    stubLease(household);

    RecipeDto banned =
        PlanTestData.recipeFor(bannedRecipeId, SlotKind.DINNER, 25, List.of(), List.of("peanut"));
    when(recipeQueryService.getById(bannedRecipeId)).thenReturn(Optional.of(banned));

    // Candidate 1: wrong kind (BREAKFAST) — must be skipped by matchesKind.
    RecipeDto wrongKind =
        PlanTestData.recipeFor(
            UUID.randomUUID(), SlotKind.BREAKFAST, 20, List.of(), List.of("oats"));
    // Candidate 2: right kind but 90 mins > 45 cap — must be skipped by withinTimeBudget.
    RecipeDto overBudget =
        PlanTestData.recipeFor(UUID.randomUUID(), SlotKind.DINNER, 90, List.of(), List.of("rice"));
    // Candidate 3: right kind + within budget + passes constraints — the one that should win.
    UUID chosenId = UUID.randomUUID();
    RecipeDto good =
        PlanTestData.recipeFor(chosenId, SlotKind.DINNER, 25, List.of(), List.of("rice"));
    when(recipeQueryService.findPlannableCandidates(eq(caller), anyInt()))
        .thenReturn(List.of(wrongKind, overBudget, good));

    when(hardConstraintFilterService.checkForHousehold(anyList(), eq(List.of("peanut"))))
        .thenReturn(new FilterResult(false, List.of(banViolation(caller, bannedRecipeId))));
    when(hardConstraintFilterService.checkForHousehold(anyList(), eq(List.of("rice"))))
        .thenReturn(new FilterResult(true, List.of()));
    // wrongKind/overBudget are dropped by matchesKind/withinTimeBudget BEFORE any constraint
    // check, so no stub for "oats" is needed (would be an unnecessary stubbing).

    coordinator.revertToPlan(caller, new RevertToPlanRequest(target.getId()));

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    verify(planRepository).save(saved.capture());
    ScheduledRecipe sr = saved.getValue().getDays().get(0).getSlots().get(0).getScheduledRecipe();
    assertThat(sr).isNotNull();
    // Wrong-kind + over-budget candidates skipped; the matching one chosen.
    assertThat(sr.getRecipeId()).isEqualTo(chosenId);
    // recipeVersionId / branchId mapped from the chosen recipe's current version + branch.
    assertThat(sr.getRecipeVersionId()).isEqualTo(good.currentVersionBody().id());
    assertThat(sr.getRecipeBranchId()).isEqualTo(good.currentBranchId());
    // servings carried from the recipe metadata (recipeFor sets servings = 2).
    assertThat(sr.getServings()).isEqualTo(good.currentVersionBody().metadata().servings());
  }

  @Test
  void revertToPlan_validTarget_copiesRecipeIdsAndServingsVerbatim() {
    UUID caller = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    Plan target = PlanTestData.newPlanGraph(household, WEEK, 1, PlanStatus.SUPERSEDED, 1, 1);
    ScheduledRecipe original = target.getDays().get(0).getSlots().get(0).getScheduledRecipe();
    UUID originalRecipeId = original.getRecipeId();
    int originalServings = original.getServings();
    when(planRepository.findById(target.getId())).thenReturn(Optional.of(target));
    when(plannerAuth.canAccessHousehold(caller, household)).thenReturn(true);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            household, WEEK, PlanStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(planRepository.countByHouseholdIdAndWeekStartDate(household, WEEK)).thenReturn(1);
    stubLease(household);
    allRecipesPass();

    coordinator.revertToPlan(caller, new RevertToPlanRequest(target.getId()));

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    verify(planRepository).save(saved.capture());
    ScheduledRecipe copied =
        saved.getValue().getDays().get(0).getSlots().get(0).getScheduledRecipe();
    // Not stripped (passes) ⇒ recipe id + servings copied verbatim from the target (kills the
    // servings-fallback and recipe-id mutations on the copy path).
    assertThat(copied.getRecipeId()).isEqualTo(originalRecipeId);
    assertThat(copied.getServings()).isEqualTo(originalServings);
  }

  @Test
  void revertToPlan_recipeBannedAndNoReplacement_slotShipsEmpty_qualityWarning() {
    UUID caller = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    Plan target = singleSlotTarget(household, SlotKind.DINNER);
    UUID bannedRecipeId =
        target.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId();
    when(planRepository.findById(target.getId())).thenReturn(Optional.of(target));
    when(plannerAuth.canAccessHousehold(caller, household)).thenReturn(true);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            household, WEEK, PlanStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(planRepository.countByHouseholdIdAndWeekStartDate(household, WEEK)).thenReturn(1);
    stubLease(household);

    RecipeDto banned =
        PlanTestData.recipeFor(bannedRecipeId, SlotKind.DINNER, 25, List.of(), List.of("peanut"));
    when(recipeQueryService.getById(bannedRecipeId)).thenReturn(Optional.of(banned));
    when(recipeQueryService.findPlannableCandidates(eq(caller), anyInt())).thenReturn(List.of());
    when(hardConstraintFilterService.checkForHousehold(anyList(), eq(List.of("peanut"))))
        .thenReturn(new FilterResult(false, List.of(banViolation(caller, bannedRecipeId))));

    UUID newId = coordinator.revertToPlan(caller, new RevertToPlanRequest(target.getId()));

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    verify(planRepository).save(saved.capture());
    Plan copy = saved.getValue();
    assertThat(copy.getId()).isEqualTo(newId);
    // No replacement available ⇒ slot ships empty, plan flagged quality-warning (LLD Failure
    // Modes).
    assertThat(copy.getDays().get(0).getSlots().get(0).getScheduledRecipe()).isNull();
    assertThat(copy.isQualityWarning()).isTrue();
  }

  // ---------- contention ----------

  @Test
  void revertToPlan_leaseContended_throws409() {
    UUID caller = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    Plan target = PlanTestData.newPlanGraph(household, WEEK, 1, PlanStatus.SUPERSEDED, 1, 1);
    when(planRepository.findById(target.getId())).thenReturn(Optional.of(target));
    when(plannerAuth.canAccessHousehold(caller, household)).thenReturn(true);
    when(lockService.acquireLease(any(LockKey.class), any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> coordinator.revertToPlan(caller, new RevertToPlanRequest(target.getId())))
        .isInstanceOf(
            com.example.mealprep.planner.exception.ConcurrentGenerationInProgressException.class);
    verify(planRepository, never()).save(any());
  }

  // ---------- helpers ----------

  /** Build a target with one shared slot of the given kind carrying one scheduled recipe. */
  private Plan singleSlotTarget(UUID household, SlotKind kind) {
    Plan target = PlanTestData.newPlanGraph(household, WEEK, 1, PlanStatus.SUPERSEDED, 1, 1);
    MealSlot slot = target.getDays().get(0).getSlots().get(0);
    slot.setKind(kind);
    slot.setShared(true);
    return target;
  }

  /** Stub both the per-user and household constraint checks to pass for any keys. */
  private void allRecipesPass() {
    lenient()
        .when(hardConstraintFilterService.check(any(), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    lenient()
        .when(hardConstraintFilterService.checkForHousehold(anyList(), anyList()))
        .thenReturn(new FilterResult(true, List.of()));
    // Copied recipes resolve to a benign recipe with safe ingredient keys.
    lenient()
        .when(recipeQueryService.getById(any()))
        .thenAnswer(
            inv ->
                Optional.of(
                    PlanTestData.recipeFor(
                        inv.getArgument(0), SlotKind.DINNER, 20, List.of(), List.of("rice"))));
  }

  private Violation banViolation(UUID userId, UUID recipeId) {
    // Minimal Violation; field shape is read only for attribution, not asserted here.
    return new Violation(
        userId,
        recipeId,
        "peanut",
        com.example.mealprep.preference.domain.entity.ViolationKind.ALLERGY,
        "peanut");
  }
}
