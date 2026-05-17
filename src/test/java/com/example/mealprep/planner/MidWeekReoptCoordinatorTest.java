package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchEngine;
import com.example.mealprep.planner.domain.service.internal.reopt.ReoptContextBuilder;
import com.example.mealprep.planner.domain.service.internal.reopt.ReoptStageCInvoker;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.event.ReoptSuggestedEvent;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.PlanNotReoptableException;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Pure unit test for the package-private {@code MidWeekReoptCoordinator} (planner-01i). Stubs the
 * Stage A&rarr;C helpers and verifies each acceptance invariant: idempotency,
 * no-degrees-of-freedom, diff-materiality, budget, the active-plan precondition, and the happy-path
 * suggestion write + AFTER_COMMIT event. The coordinator is reflectively constructed
 * (package-private to {@code domain.service.internal.reopt}).
 *
 * <p>Slot dates are anchored 50 years in the future so the {@code PinningSetCalculator}'s
 * wall-clock 24h lock window never spuriously pins a "regenerable" PLANNED slot (no time-bomb).
 */
@ExtendWith(MockitoExtension.class)
class MidWeekReoptCoordinatorTest {

  // Far future: 24h lock window can never reach these dates from real wall-clock now.
  private static final LocalDate WEEK = LocalDate.now().plusYears(50);

  @Mock private PlanRepository planRepository;
  @Mock private MealPrepPlanReoptSuggestionRepository suggestionRepository;
  @Mock private BeamSearchEngine beamSearchEngine;
  @Mock private RollupBuilder rollupBuilder;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ReoptContextBuilder contextBuilder;
  @Mock private ReoptStageCInvoker stageCInvoker;
  @Mock private DecisionLogService decisionLogService;
  @Mock private ObjectProvider<DecisionLogService> decisionLogProvider;

  private Object coordinator;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> cls =
        Class.forName(
            "com.example.mealprep.planner.domain.service.internal.reopt"
                + ".MidWeekReoptCoordinator");
    Constructor<?> ctor = cls.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    coordinator =
        ctor.newInstance(
            planRepository,
            suggestionRepository,
            beamSearchEngine,
            rollupBuilder,
            eventPublisher,
            PlanTestData.scoringProperties(),
            new ObjectMapper(),
            contextBuilder,
            stageCInvoker,
            decisionLogProvider);
  }

  @SuppressWarnings("unchecked")
  private Optional<UUID> requestReopt(
      UUID planId, ReoptTriggerKind trigger, UUID triggerEventId, UUID traceId) throws Throwable {
    java.lang.reflect.Method m =
        coordinator
            .getClass()
            .getMethod("requestReopt", UUID.class, ReoptTriggerKind.class, UUID.class, UUID.class);
    m.setAccessible(true);
    try {
      return (Optional<UUID>) m.invoke(coordinator, planId, trigger, triggerEventId, traceId);
    } catch (java.lang.reflect.InvocationTargetException ite) {
      throw ite.getCause(); // surface the real business exception to assertThatThrownBy
    }
  }

  /** A GENERATED plan: 1 day, 3 PLANNED slots, each with a ScheduledRecipe. */
  private Plan generatedPlan() {
    return PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.GENERATED, 1, 3);
  }

  private List<MealSlot> slotsOf(Plan plan) {
    List<MealSlot> slots = new ArrayList<>();
    for (Day d : plan.getDays()) {
      slots.addAll(d.getSlots());
    }
    return slots;
  }

  /** A Stage-C-chosen candidate that swaps every slot to a brand-new recipe id. */
  private CandidatePlan candidateSwappingAll(Plan plan) {
    List<SlotAssignment> assignments = new ArrayList<>();
    for (MealSlot s : slotsOf(plan)) {
      assignments.add(
          new SlotAssignment(
              s.getDay().getId(),
              s.getId(),
              s.getSlotIndex(),
              s.getDay().getOnDate(),
              s.getKind(),
              UUID.randomUUID(), // a different recipe -> material change
              UUID.randomUUID(),
              UUID.randomUUID(),
              2,
              false));
    }
    return new CandidatePlan(UUID.randomUUID(), WEEK, assignments, null);
  }

  /** A Stage-C-chosen candidate that keeps every slot's ORIGINAL recipe id (no diff). */
  private CandidatePlan candidateKeepingAll(Plan plan) {
    List<SlotAssignment> assignments = new ArrayList<>();
    for (MealSlot s : slotsOf(plan)) {
      ScheduledRecipe sr = s.getScheduledRecipe();
      assignments.add(
          new SlotAssignment(
              s.getDay().getId(),
              s.getId(),
              s.getSlotIndex(),
              s.getDay().getOnDate(),
              s.getKind(),
              sr.getRecipeId(),
              sr.getRecipeVersionId(),
              sr.getRecipeBranchId(),
              sr.getServings(),
              false));
    }
    return new CandidatePlan(UUID.randomUUID(), WEEK, assignments, null);
  }

  private void stubPipeline(CandidatePlan chosen) {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    when(contextBuilder.buildForReopt(any(), anyList(), anyList(), any())).thenReturn(ctx);
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(chosen), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(anyList(), anyList(), any(), any()))
        .thenReturn(new ReoptStageCInvoker.Result(0, "picked top"));
    // Lenient: the no-material-change path returns before any decision-log write.
    org.mockito.Mockito.lenient()
        .when(decisionLogProvider.getIfAvailable())
        .thenReturn(decisionLogService);
    org.mockito.Mockito.lenient()
        .when(decisionLogService.write(any(DecisionLogWriteRequest.class)))
        .thenReturn(UUID.randomUUID());
  }

  @Test
  void requestReopt_planMissing_throwsPlanNotFound() {
    UUID planId = UUID.randomUUID();
    when(planRepository.findById(planId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> requestReopt(planId, ReoptTriggerKind.USER, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(PlanNotFoundException.class);
  }

  @Test
  void requestReopt_terminalPlan_throwsPlanNotReoptable() {
    Plan plan = generatedPlan();
    plan.setStatus(PlanStatus.SUPERSEDED);
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

    assertThatThrownBy(
            () ->
                requestReopt(
                    plan.getId(), ReoptTriggerKind.USER, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(PlanNotReoptableException.class);
    verifyNoInteractions(beamSearchEngine);
  }

  @Test
  void requestReopt_activePlanAlsoReoptable() throws Throwable {
    Plan plan = generatedPlan();
    plan.setStatus(PlanStatus.ACTIVE);
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    stubPipeline(candidateSwappingAll(plan));

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isPresent();
  }

  @Test
  void requestReopt_sameTriggerEventId_isIdempotent_returnsExistingId() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    UUID existingId = UUID.randomUUID();
    MealPrepPlanReoptSuggestion existing =
        MealPrepPlanReoptSuggestion.builder().id(existingId).build();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.of(existing));

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).hasValue(existingId);
    // No re-run, no new write, no event.
    verifyNoInteractions(beamSearchEngine);
    verify(suggestionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void requestReopt_allSlotsPinned_returnsEmpty_noDecisionLog_noEvent() throws Throwable {
    Plan plan = generatedPlan();
    slotsOf(plan).forEach(s -> s.setState(SlotState.EATEN));
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isEmpty();
    verifyNoInteractions(beamSearchEngine);
    verify(suggestionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
    verify(decisionLogService, never()).write(any());
  }

  @Test
  void requestReopt_stageCReturnsIdenticalPlan_returnsEmpty_noSuggestion() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    stubPipeline(candidateKeepingAll(plan));

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isEmpty();
    verify(suggestionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void requestReopt_budgetExhausted_returnsEmpty_writesBudgetDecisionLogNote() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    // Default maxSuggestionsPerPlan = 3 -> 3 active suggestions exhausts the budget.
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(3L);
    when(decisionLogProvider.getIfAvailable()).thenReturn(decisionLogService);
    when(decisionLogService.write(any())).thenReturn(UUID.randomUUID());

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isEmpty();
    verifyNoInteractions(beamSearchEngine);
    verify(suggestionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
    ArgumentCaptor<DecisionLogWriteRequest> cap =
        ArgumentCaptor.forClass(DecisionLogWriteRequest.class);
    verify(decisionLogService).write(cap.capture());
    assertThat(cap.getValue().chosen().get("summary").asText()).contains("rejected-by-budget");
  }

  @Test
  void requestReopt_budgetCountUsesPendingAndRejected() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(3L);
    when(decisionLogProvider.getIfAvailable()).thenReturn(decisionLogService);
    when(decisionLogService.write(any())).thenReturn(UUID.randomUUID());

    requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<ReoptSuggestionStatus>> cap =
        ArgumentCaptor.forClass(Collection.class);
    verify(suggestionRepository).countByPlanIdAndStatusIn(eq(plan.getId()), cap.capture());
    assertThat(cap.getValue())
        .containsExactlyInAnyOrder(ReoptSuggestionStatus.PENDING, ReoptSuggestionStatus.REJECTED);
  }

  @Test
  void requestReopt_materialChange_persistsSuggestion_andPublishesEvent() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    stubPipeline(candidateSwappingAll(plan));

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.PROVISIONS, triggerEventId, traceId);

    assertThat(result).isPresent();

    ArgumentCaptor<MealPrepPlanReoptSuggestion> sCap =
        ArgumentCaptor.forClass(MealPrepPlanReoptSuggestion.class);
    verify(suggestionRepository).save(sCap.capture());
    MealPrepPlanReoptSuggestion saved = sCap.getValue();
    assertThat(saved.getId()).isEqualTo(result.get());
    assertThat(saved.getPlanId()).isEqualTo(plan.getId());
    assertThat(saved.getTriggerKind()).isEqualTo(ReoptTriggerKind.PROVISIONS);
    assertThat(saved.getTriggerEventId()).isEqualTo(triggerEventId);
    assertThat(saved.getTraceId()).isEqualTo(traceId);
    assertThat(saved.getStatus()).isEqualTo(ReoptSuggestionStatus.PENDING);
    assertThat(saved.isSwept()).isFalse();
    assertThat(saved.getExpiresAt())
        .isEqualTo(saved.getCreatedAt().plus(java.time.Duration.ofHours(24)));
    assertThat(saved.getProposedAssignments().changes()).hasSize(3);
    assertThat(saved.getProposedAssignments().schemaVersion()).isEqualTo(1);

    ArgumentCaptor<ReoptSuggestedEvent> eCap = ArgumentCaptor.forClass(ReoptSuggestedEvent.class);
    verify(eventPublisher).publishEvent(eCap.capture());
    ReoptSuggestedEvent ev = eCap.getValue();
    assertThat(ev.suggestionId()).isEqualTo(saved.getId());
    assertThat(ev.planId()).isEqualTo(plan.getId());
    assertThat(ev.householdId()).isEqualTo(plan.getHouseholdId());
    assertThat(ev.traceId()).isEqualTo(traceId);
    assertThat(ev.affectedSlotIds()).hasSize(3);

    // Decision-log row written with the triggerEventId as parent (trace propagation #15).
    ArgumentCaptor<DecisionLogWriteRequest> dCap =
        ArgumentCaptor.forClass(DecisionLogWriteRequest.class);
    verify(decisionLogService).write(dCap.capture());
    assertThat(dCap.getValue().parentDecisionId()).isEqualTo(triggerEventId);
    assertThat(dCap.getValue().scopeKind()).isEqualTo("mid_week_reopt");
    assertThat(dCap.getValue().inputs().get("pinnedSlotCount").asInt()).isZero();
    assertThat(dCap.getValue().inputs().get("unpinnedSlotCount").asInt()).isEqualTo(3);
  }

  @Test
  void requestReopt_partialPin_onlyUnpinnedSlotsDiffed() throws Throwable {
    Plan plan = generatedPlan();
    List<MealSlot> slots = slotsOf(plan);
    // Pin the first slot (EATEN); leave the other two PLANNED/regenerable.
    slots.get(0).setState(SlotState.EATEN);
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    stubPipeline(candidateSwappingAll(plan));

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isPresent();
    ArgumentCaptor<MealPrepPlanReoptSuggestion> sCap =
        ArgumentCaptor.forClass(MealPrepPlanReoptSuggestion.class);
    verify(suggestionRepository).save(sCap.capture());
    // Only the 2 unpinned slots are in the diff; the EATEN slot is excluded.
    assertThat(sCap.getValue().getProposedAssignments().changes()).hasSize(2);
    assertThat(sCap.getValue().getProposedAssignments().changes())
        .noneMatch(c -> c.slotId().equals(slots.get(0).getId()));
    verify(decisionLogService, times(1)).write(any());
  }

  @Test
  void requestReopt_stageCInvokerNull_fallsBackToTopScored() throws Throwable {
    // Re-construct with null stageCInvoker (01g not merged) — degrade to index 0.
    Class<?> cls =
        Class.forName(
            "com.example.mealprep.planner.domain.service.internal.reopt"
                + ".MidWeekReoptCoordinator");
    Constructor<?> ctor = cls.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    coordinator =
        ctor.newInstance(
            planRepository,
            suggestionRepository,
            beamSearchEngine,
            rollupBuilder,
            eventPublisher,
            PlanTestData.scoringProperties(),
            new ObjectMapper(),
            contextBuilder,
            null,
            decisionLogProvider);

    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    CandidatePlan chosen = candidateSwappingAll(plan);
    when(contextBuilder.buildForReopt(any(), anyList(), anyList(), any()))
        .thenReturn(PlanTestData.minimalContext(List.of(), List.of()));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(chosen), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(decisionLogProvider.getIfAvailable()).thenReturn(decisionLogService);
    when(decisionLogService.write(any())).thenReturn(UUID.randomUUID());

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isPresent();
    verifyNoInteractions(stageCInvoker);
  }

  @Test
  void requestReopt_contextBuilderNull_returnsEmpty() throws Throwable {
    Class<?> cls =
        Class.forName(
            "com.example.mealprep.planner.domain.service.internal.reopt"
                + ".MidWeekReoptCoordinator");
    Constructor<?> ctor = cls.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    coordinator =
        ctor.newInstance(
            planRepository,
            suggestionRepository,
            beamSearchEngine,
            rollupBuilder,
            eventPublisher,
            PlanTestData.scoringProperties(),
            new ObjectMapper(),
            null,
            stageCInvoker,
            decisionLogProvider);

    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isEmpty();
    verify(suggestionRepository, never()).save(any());
  }
}
