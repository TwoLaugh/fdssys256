package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogEntry;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter;
import com.example.mealprep.planner.domain.service.internal.decisionlog.PlannerDecisionKind;
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
import org.springframework.context.ApplicationEventPublisher;

/**
 * Pure unit test for the package-private {@code MidWeekReoptCoordinator} (planner-01i + 01l). Stubs
 * the Stage A&rarr;C helpers and the {@link DecisionLogWriter}, and verifies each acceptance
 * invariant: idempotency, no-degrees-of-freedom, diff-materiality, budget, the active-plan
 * precondition, the happy-path suggestion write + AFTER_COMMIT event, and the planner-01l
 * decision-log contract (a {@code MID_WEEK_REOPT_REQUEST} entry row chained to the listener's
 * decision id + a {@code MID_WEEK_REOPT_RESULT} exit row carrying the suggestion id or a {@code
 * skippedReason}). The coordinator is reflectively constructed (package-private to {@code
 * domain.service.internal.reopt}).
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
  @Mock private DecisionLogWriter decisionLogWriter;

  private Object coordinator;

  private Object newCoordinator(Object ctxBuilder, Object stageC) throws Exception {
    Class<?> cls =
        Class.forName(
            "com.example.mealprep.planner.domain.service.internal.reopt"
                + ".MidWeekReoptCoordinator");
    Constructor<?> ctor = cls.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    return ctor.newInstance(
        planRepository,
        suggestionRepository,
        beamSearchEngine,
        rollupBuilder,
        eventPublisher,
        PlanTestData.scoringProperties(),
        new ObjectMapper(),
        ctxBuilder,
        stageC,
        decisionLogWriter);
  }

  @BeforeEach
  void setUp() throws Exception {
    coordinator = newCoordinator(contextBuilder, stageCInvoker);
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
  }

  private void stubWriter() {
    when(decisionLogWriter.write(any(DecisionLogEntry.class))).thenReturn(UUID.randomUUID());
  }

  private List<DecisionLogEntry> capturedEntries() {
    ArgumentCaptor<DecisionLogEntry> cap = ArgumentCaptor.forClass(DecisionLogEntry.class);
    verify(decisionLogWriter, org.mockito.Mockito.atLeastOnce()).write(cap.capture());
    return cap.getAllValues();
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
    // Terminal-plan precondition fails before the REQUEST row is written.
    verifyNoInteractions(decisionLogWriter);
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
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isPresent();
    // Kills L167 NegateConditionals (other arm): a USER trigger maps to "user".
    assertThat(capturedEntries())
        .filteredOn(e -> e.kind() == PlannerDecisionKind.MID_WEEK_REOPT_REQUEST)
        .allMatch(e -> "user".equals(e.triggeredBy()));
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
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).hasValue(existingId);
    // No re-run, no new suggestion, no event.
    verifyNoInteractions(beamSearchEngine);
    verify(suggestionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
    // REQUEST + RESULT(idempotent) rows still recorded.
    List<DecisionLogEntry> kinds = capturedEntries();
    assertThat(kinds)
        .extracting(DecisionLogEntry::kind)
        .containsExactly(
            PlannerDecisionKind.MID_WEEK_REOPT_REQUEST, PlannerDecisionKind.MID_WEEK_REOPT_RESULT);
  }

  @Test
  void requestReopt_allSlotsPinned_returnsEmpty_writesSkippedResult_noEvent() throws Throwable {
    Plan plan = generatedPlan();
    slotsOf(plan).forEach(s -> s.setState(SlotState.EATEN));
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isEmpty();
    verifyNoInteractions(beamSearchEngine);
    verify(suggestionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
    DecisionLogEntry resultRow = lastResultRow();
    assertThat(resultRow.outputs().get("skippedReason").asText())
        .isEqualTo("no_degrees_of_freedom");
    assertThat(resultRow.outputs().get("suggestionId").isNull()).isTrue();
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
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isEmpty();
    verify(suggestionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
    assertThat(lastResultRow().outputs().get("skippedReason").asText())
        .isEqualTo("no_material_change");
  }

  @Test
  void requestReopt_budgetExhausted_returnsEmpty_writesBudgetSkippedResult() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    // Default maxSuggestionsPerPlan = 3 -> 3 active suggestions exhausts the budget.
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(3L);
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isEmpty();
    verifyNoInteractions(beamSearchEngine);
    verify(suggestionRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
    assertThat(lastResultRow().outputs().get("skippedReason").asText())
        .isEqualTo("budget_exhausted");
  }

  @Test
  void requestReopt_budgetCountUsesPendingAndRejected() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(3L);
    stubWriter();

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
    UUID requestDecisionId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    stubPipeline(candidateSwappingAll(plan));
    // First write (REQUEST) returns a stable id we then assert the suggestion + RESULT chain on.
    when(decisionLogWriter.write(any(DecisionLogEntry.class)))
        .thenReturn(requestDecisionId)
        .thenReturn(UUID.randomUUID());

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
    // Suggestion's decisionId anchors to the REQUEST row.
    assertThat(saved.getDecisionId()).isEqualTo(requestDecisionId);

    ArgumentCaptor<ReoptSuggestedEvent> eCap = ArgumentCaptor.forClass(ReoptSuggestedEvent.class);
    verify(eventPublisher).publishEvent(eCap.capture());
    ReoptSuggestedEvent ev = eCap.getValue();
    assertThat(ev.suggestionId()).isEqualTo(saved.getId());
    assertThat(ev.planId()).isEqualTo(plan.getId());
    assertThat(ev.householdId()).isEqualTo(plan.getHouseholdId());
    assertThat(ev.traceId()).isEqualTo(traceId);
    assertThat(ev.affectedSlotIds()).hasSize(3);

    // planner-01l: REQUEST entry row + RESULT exit row chained to it; RESULT carries suggestionId.
    List<DecisionLogEntry> entries = capturedEntries();
    DecisionLogEntry request = entries.get(0);
    DecisionLogEntry resultRow = entries.get(entries.size() - 1);
    assertThat(request.kind()).isEqualTo(PlannerDecisionKind.MID_WEEK_REOPT_REQUEST);
    assertThat(request.planId()).isEqualTo(plan.getId());
    assertThat(request.traceId()).isEqualTo(traceId);
    // Kills L479 NullReturnVals (requestInputs -> null): the REQUEST row must carry the
    // planId/trigger/triggerEventId correlation inputs, not a null node.
    assertThat(request.inputs()).isNotNull();
    assertThat(request.inputs().get("planId").asText()).isEqualTo(plan.getId().toString());
    assertThat(request.inputs().get("trigger").asText()).isEqualTo("PROVISIONS");
    assertThat(request.inputs().get("triggerEventId").asText())
        .isEqualTo(triggerEventId.toString());
    // Kills L167 NegateConditionals: a non-USER (PROVISIONS) trigger maps to "system".
    assertThat(request.triggeredBy()).isEqualTo("system");
    assertThat(resultRow.kind()).isEqualTo(PlannerDecisionKind.MID_WEEK_REOPT_RESULT);
    assertThat(resultRow.parentDecisionId()).isEqualTo(requestDecisionId);
    assertThat(resultRow.outputs().get("suggestionId").asText())
        .isEqualTo(saved.getId().toString());
    assertThat(resultRow.outputs().get("skippedReason").isNull()).isTrue();
    // Kills L492 NullReturnVals (resultDetailInputs -> null): the RESULT row's detail inputs must
    // be present with the pin split + affected slots + summary.
    assertThat(resultRow.inputs()).isNotNull();
    assertThat(resultRow.inputs().get("unpinnedSlotCount").asInt()).isEqualTo(3);
    assertThat(resultRow.inputs().get("pinnedSlotCount").asInt()).isZero();
    assertThat(resultRow.inputs().get("summary").asText()).isNotBlank();
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
    stubWriter();

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
  }

  @Test
  void requestReopt_stageCInvokerNull_fallsBackToTopScored() throws Throwable {
    coordinator = newCoordinator(contextBuilder, null);

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
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isPresent();
    verifyNoInteractions(stageCInvoker);
  }

  @Test
  void requestReopt_contextBuilderNull_returnsEmpty_writesSkippedResult() throws Throwable {
    coordinator = newCoordinator(null, stageCInvoker);

    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isEmpty();
    verify(suggestionRepository, never()).save(any());
    assertThat(lastResultRow().outputs().get("skippedReason").asText())
        .isEqualTo("no_material_change");
  }

  /**
   * Two candidates: index 0 keeps the original recipes (no material change), index 1 swaps all.
   * Stage-C picks index 1 → a suggestion IS produced. Kills the L464 PrimitiveReturns mutant
   * ({@code return idx} → {@code return 0}): forcing index 0 would select the no-change candidate
   * and yield an empty result.
   */
  @Test
  void requestReopt_stageCPicksNonZeroIndex_selectsThatCandidate() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    CandidatePlan keep = candidateKeepingAll(plan);
    CandidatePlan swap = candidateSwappingAll(plan);
    when(contextBuilder.buildForReopt(any(), anyList(), anyList(), any()))
        .thenReturn(PlanTestData.minimalContext(List.of(), List.of()));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(keep, swap), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(anyList(), anyList(), any(), any()))
        .thenReturn(new ReoptStageCInvoker.Result(1, "second is better"));
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isPresent();
    ArgumentCaptor<MealPrepPlanReoptSuggestion> sCap =
        ArgumentCaptor.forClass(MealPrepPlanReoptSuggestion.class);
    verify(suggestionRepository).save(sCap.capture());
    assertThat(sCap.getValue().getProposedAssignments().changes()).hasSize(3);
  }

  /**
   * Stage-C returns an out-of-range index (== candidates.size()) → coordinator falls back to index
   * 0 (the no-change candidate) → empty result. Kills the L457 ConditionalsBoundary / Negate
   * mutants on {@code idx < 0 || idx >= candidates.size()}: a relaxed/negated guard would index out
   * of bounds or accept the bad index.
   */
  @Test
  void requestReopt_stageCOutOfRangeIndex_fallsBackToZero() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    CandidatePlan keep = candidateKeepingAll(plan);
    CandidatePlan swap = candidateSwappingAll(plan);
    when(contextBuilder.buildForReopt(any(), anyList(), anyList(), any()))
        .thenReturn(PlanTestData.minimalContext(List.of(), List.of()));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(keep, swap), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(anyList(), anyList(), any(), any()))
        .thenReturn(new ReoptStageCInvoker.Result(2, "out of range")); // size == 2
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    // index 0 = keep-all → no material change → empty, no suggestion.
    assertThat(result).isEmpty();
    verify(suggestionRepository, never()).save(any());
    assertThat(lastResultRow().outputs().get("skippedReason").asText())
        .isEqualTo("no_material_change");
  }

  /**
   * Negative Stage-C index → same index-0 fallback. Pairs with the out-of-range test to pin both
   * arms of {@code idx < 0 || idx >= size}, killing the L457 NegateConditionals mutants.
   */
  @Test
  void requestReopt_stageCNegativeIndex_fallsBackToZero() throws Throwable {
    Plan plan = generatedPlan();
    UUID triggerEventId = UUID.randomUUID();
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(suggestionRepository.findByPlanIdAndTriggerEventId(plan.getId(), triggerEventId))
        .thenReturn(Optional.empty());
    when(suggestionRepository.countByPlanIdAndStatusIn(eq(plan.getId()), any())).thenReturn(0L);
    CandidatePlan keep = candidateKeepingAll(plan);
    CandidatePlan swap = candidateSwappingAll(plan);
    when(contextBuilder.buildForReopt(any(), anyList(), anyList(), any()))
        .thenReturn(PlanTestData.minimalContext(List.of(), List.of()));
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(keep, swap), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(anyList(), anyList(), any(), any()))
        .thenReturn(new ReoptStageCInvoker.Result(-1, "negative"));
    stubWriter();

    Optional<UUID> result =
        requestReopt(plan.getId(), ReoptTriggerKind.USER, triggerEventId, UUID.randomUUID());

    assertThat(result).isEmpty();
    verify(suggestionRepository, never()).save(any());
  }

  /** The last {@code MID_WEEK_REOPT_RESULT} entry the writer received. */
  private DecisionLogEntry lastResultRow() {
    List<DecisionLogEntry> entries = capturedEntries();
    return entries.stream()
        .filter(e -> e.kind() == PlannerDecisionKind.MID_WEEK_REOPT_RESULT)
        .reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no MID_WEEK_REOPT_RESULT row written"));
  }
}
