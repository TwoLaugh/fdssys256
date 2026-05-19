package com.example.mealprep.planner.domain.service.internal.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.PlanReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument.ProposedSlotChange;
import com.example.mealprep.planner.api.mapper.ReoptSuggestionMapperImpl;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.MealSlotRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogEntry;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter;
import com.example.mealprep.planner.domain.service.internal.decisionlog.PlannerDecisionKind;
import com.example.mealprep.planner.event.PlanAbandonedEvent;
import com.example.mealprep.planner.event.PlanAcceptedEvent;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.planner.event.PlanRejectedEvent;
import com.example.mealprep.planner.event.PlanSupersededEvent;
import com.example.mealprep.planner.exception.InvalidPlanStateTransitionException;
import com.example.mealprep.planner.exception.InvalidSlotStateTransitionException;
import com.example.mealprep.planner.exception.MealSlotNotFoundException;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.PlanNotReoptableException;
import com.example.mealprep.planner.exception.ReoptSuggestionNotFoundException;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Pure-unit tests over {@link PlanWriteServiceImpl}. Persistence + event publication + decision-log
 * are mocked (infra / cross-collaborators); the real {@link PlanStateMachine}, real {@link
 * ReoptSuggestionMapperImpl}, real {@link ObjectMapper} and a frozen {@link Clock} are used so the
 * lifecycle branching, idempotency, error routing and the copy-forward graph logic are exercised
 * for real (playbook: never mock within the module under test).
 *
 * <p>The Testcontainers happy paths live in {@code PlansControllerIT} / {@code MidWeekReoptFlowIT};
 * this suite targets the decision branches and mutants those wider ITs do not pin.
 */
@ExtendWith(MockitoExtension.class)
class PlanWriteServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-05-18T10:15:30Z");
  private static final LocalDate WEEK = LocalDate.of(2026, 5, 18);

  @Mock private PlanRepository planRepository;
  @Mock private MealSlotRepository mealSlotRepository;
  @Mock private MealPrepPlanReoptSuggestionRepository suggestionRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private DecisionLogWriter decisionLogWriter;

  private PlanWriteServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new PlanWriteServiceImpl(
            planRepository,
            mealSlotRepository,
            suggestionRepository,
            new PlanStateMachine(),
            eventPublisher,
            new ReoptSuggestionMapperImpl(),
            decisionLogWriter,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Plan generatedPlan() {
    Plan p = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.GENERATED, 2, 2);
    when(planRepository.findById(p.getId())).thenReturn(Optional.of(p));
    return p;
  }

  // ---------- acceptPlan ----------

  @Test
  void acceptPlan_generated_transitionsActive_setsAcceptedAt_publishesAccepted_logsTransition() {
    Plan plan = generatedPlan();

    UUID id = service.acceptPlan(plan.getId());

    assertThat(id).isEqualTo(plan.getId());
    assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
    assertThat(plan.getAcceptedAt()).isEqualTo(NOW);
    verify(planRepository).save(plan);

    ArgumentCaptor<PlanAcceptedEvent> ev = ArgumentCaptor.forClass(PlanAcceptedEvent.class);
    verify(eventPublisher).publishEvent(ev.capture());
    assertThat(ev.getValue().planId()).isEqualTo(plan.getId());
    assertThat(ev.getValue().occurredAt()).isEqualTo(NOW);

    ArgumentCaptor<DecisionLogEntry> log = ArgumentCaptor.forClass(DecisionLogEntry.class);
    verify(decisionLogWriter).write(log.capture());
    assertThat(log.getValue().kind()).isEqualTo(PlannerDecisionKind.PLAN_LIFECYCLE_TRANSITION);
    assertThat(log.getValue().inputs().get("to").asText()).isEqualTo("ACTIVE");
  }

  @Test
  void acceptPlan_unknownId_throwsPlanNotFound_noSaveNoEvent() {
    UUID missing = UUID.randomUUID();
    when(planRepository.findById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.acceptPlan(missing)).isInstanceOf(PlanNotFoundException.class);
    verify(planRepository, never()).save(any());
    verifyNoInteractions(eventPublisher, decisionLogWriter);
  }

  @Test
  void acceptPlan_alreadyActive_illegalTransition_throws409_noEvent() {
    Plan plan = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.ACTIVE, 1, 1);
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

    assertThatThrownBy(() -> service.acceptPlan(plan.getId()))
        .isInstanceOf(InvalidPlanStateTransitionException.class);
    verify(planRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  // ---------- rejectPlan ----------

  @Test
  void rejectPlan_generated_setsRejectedReasonAndAt_publishesRejected() {
    Plan plan = generatedPlan();

    service.rejectPlan(plan.getId(), "not enough variety");

    assertThat(plan.getStatus()).isEqualTo(PlanStatus.REJECTED);
    assertThat(plan.getRejectedReason()).isEqualTo("not enough variety");
    assertThat(plan.getRejectedAt()).isEqualTo(NOW);
    ArgumentCaptor<PlanRejectedEvent> ev = ArgumentCaptor.forClass(PlanRejectedEvent.class);
    verify(eventPublisher).publishEvent(ev.capture());
    assertThat(ev.getValue().reason()).isEqualTo("not enough variety");
  }

  @Test
  void rejectPlan_alreadyRejected_idempotentNoOp_noSaveNoEventNoLog() {
    Plan plan = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.REJECTED, 1, 1);
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

    UUID id = service.rejectPlan(plan.getId(), "ignored on replay");

    assertThat(id).isEqualTo(plan.getId());
    // Idempotent branch: no reason mutation, no persistence, no event, no audit row.
    assertThat(plan.getRejectedReason()).isNull();
    verify(planRepository, never()).save(any());
    verifyNoInteractions(eventPublisher, decisionLogWriter);
  }

  @Test
  void rejectPlan_fromActive_illegalTransition_throws409() {
    Plan plan = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.ACTIVE, 1, 1);
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

    assertThatThrownBy(() -> service.rejectPlan(plan.getId(), "x"))
        .isInstanceOf(InvalidPlanStateTransitionException.class);
  }

  // ---------- abandonPlan ----------

  @Test
  void abandonPlan_active_setsAbandonedReasonAndAt_publishesAbandoned() {
    Plan plan = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.ACTIVE, 1, 1);
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

    service.abandonPlan(plan.getId(), "going on holiday");

    assertThat(plan.getStatus()).isEqualTo(PlanStatus.ABANDONED);
    assertThat(plan.getAbandonedReason()).isEqualTo("going on holiday");
    assertThat(plan.getAbandonedAt()).isEqualTo(NOW);
    verify(eventPublisher).publishEvent(any(PlanAbandonedEvent.class));
  }

  @Test
  void abandonPlan_fromGenerated_illegalTransition_throws409() {
    Plan plan = generatedPlan();
    assertThatThrownBy(() -> service.abandonPlan(plan.getId(), "x"))
        .isInstanceOf(InvalidPlanStateTransitionException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  // ---------- revertPlan ----------

  @Test
  void revertPlan_active_supersedesAndCreatesGeneratedCopy_publishesBothEvents() {
    Plan current = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 3, PlanStatus.ACTIVE, 2, 2);
    when(planRepository.findById(current.getId())).thenReturn(Optional.of(current));

    UUID newId = service.revertPlan(current.getId());

    assertThat(newId).isNotEqualTo(current.getId());
    assertThat(current.getStatus()).isEqualTo(PlanStatus.SUPERSEDED);

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    verify(planRepository, times(2)).save(saved.capture());
    Plan copy =
        saved.getAllValues().stream()
            .filter(p -> !p.getId().equals(current.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(copy.getStatus()).isEqualTo(PlanStatus.GENERATED);
    assertThat(copy.getGeneration()).isEqualTo(current.getGeneration() + 1);
    assertThat(copy.getReplacesPlanId()).isEqualTo(current.getId());
    // copyForward deep-copies the graph with fresh ids.
    assertThat(copy.getDays()).hasSize(current.getDays().size());
    assertThat(copy.getDays().get(0).getId()).isNotEqualTo(current.getDays().get(0).getId());
    assertThat(copy.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId())
        .isEqualTo(current.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId());

    verify(eventPublisher).publishEvent(any(PlanSupersededEvent.class));
    verify(eventPublisher).publishEvent(any(PlanGeneratedEvent.class));
  }

  @Test
  void revertPlan_notActive_throwsPlanNotReoptable_noSave() {
    Plan plan = generatedPlan();
    assertThatThrownBy(() -> service.revertPlan(plan.getId()))
        .isInstanceOf(PlanNotReoptableException.class);
    verify(planRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  // ---------- changeSlotState ----------

  @Test
  void changeSlotState_validTransition_savesSlot_derivesPinnedReason() {
    UUID planId = UUID.randomUUID();
    MealSlot slot =
        MealSlot.builder()
            .id(UUID.randomUUID())
            .slotIndex(0)
            .kind(SlotKind.DINNER)
            .state(SlotState.PLANNED)
            .build();
    when(mealSlotRepository.findByIdAndPlanId(slot.getId(), planId)).thenReturn(Optional.of(slot));

    UUID returned = service.changeSlotState(planId, slot.getId(), SlotState.COOKING);

    assertThat(returned).isEqualTo(planId);
    assertThat(slot.getState()).isEqualTo(SlotState.COOKING);
    // PlanStateMachine.derivePinnedReason(COOKING) -> PinnedReason.COOKING.
    assertThat(slot.getPinnedReason())
        .isEqualTo(com.example.mealprep.planner.domain.entity.PinnedReason.COOKING);
    verify(mealSlotRepository).save(slot);
  }

  @Test
  void changeSlotState_unknownSlot_throwsMealSlotNotFound() {
    UUID planId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    when(mealSlotRepository.findByIdAndPlanId(slotId, planId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.changeSlotState(planId, slotId, SlotState.COOKING))
        .isInstanceOf(MealSlotNotFoundException.class);
    verify(mealSlotRepository, never()).save(any());
  }

  @Test
  void changeSlotState_illegalSlotTransition_throws409_noSave() {
    UUID planId = UUID.randomUUID();
    MealSlot slot =
        MealSlot.builder()
            .id(UUID.randomUUID())
            .slotIndex(0)
            .kind(SlotKind.DINNER)
            .state(SlotState.PLANNED)
            .build();
    when(mealSlotRepository.findByIdAndPlanId(slot.getId(), planId)).thenReturn(Optional.of(slot));

    // PLANNED -> EATEN is not a legal slot transition.
    assertThatThrownBy(() -> service.changeSlotState(planId, slot.getId(), SlotState.EATEN))
        .isInstanceOf(InvalidSlotStateTransitionException.class);
    verify(mealSlotRepository, never()).save(any());
  }

  // ---------- acceptReoptSuggestion ----------

  private MealPrepPlanReoptSuggestion suggestion(
      UUID planId, ReoptSuggestionStatus status, List<ProposedSlotChange> changes) {
    return MealPrepPlanReoptSuggestion.builder()
        .id(UUID.randomUUID())
        .planId(planId)
        .triggerKind(ReoptTriggerKind.PROVISIONS)
        .triggerEventId(UUID.randomUUID())
        .traceId(UUID.randomUUID())
        .decisionId(UUID.randomUUID())
        .summary("swap one slot")
        .status(status)
        .proposedAssignments(ProposedReoptAssignmentsDocument.of(changes))
        .createdAt(NOW)
        .expiresAt(NOW.plusSeconds(86400))
        .swept(false)
        .build();
  }

  @Test
  void acceptReoptSuggestion_appliesChangeToCopy_marksAccepted_publishesEvents() {
    Plan current = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.ACTIVE, 1, 1);
    MealSlot originalSlot = current.getDays().get(0).getSlots().get(0);
    UUID newRecipeId = UUID.randomUUID();
    ProposedSlotChange change =
        new ProposedSlotChange(
            originalSlot.getId(), null, newRecipeId, null, null, 4, "more protein");
    MealPrepPlanReoptSuggestion sug =
        suggestion(current.getId(), ReoptSuggestionStatus.PENDING, List.of(change));
    when(suggestionRepository.findById(sug.getId())).thenReturn(Optional.of(sug));
    when(planRepository.findById(current.getId())).thenReturn(Optional.of(current));

    PlanReoptSuggestionDto dto = service.acceptReoptSuggestion(current.getId(), sug.getId());

    assertThat(dto.status()).isEqualTo(ReoptSuggestionStatus.ACCEPTED);
    assertThat(sug.getStatus()).isEqualTo(ReoptSuggestionStatus.ACCEPTED);
    assertThat(current.getStatus()).isEqualTo(PlanStatus.SUPERSEDED);

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    verify(planRepository, times(2)).save(saved.capture());
    Plan copy =
        saved.getAllValues().stream()
            .filter(p -> p.getStatus() == PlanStatus.GENERATED)
            .findFirst()
            .orElseThrow();
    ScheduledRecipe sr = copy.getDays().get(0).getSlots().get(0).getScheduledRecipe();
    assertThat(sr.getRecipeId()).isEqualTo(newRecipeId);
    assertThat(sr.getServings()).isEqualTo(4);
    verify(eventPublisher).publishEvent(any(PlanSupersededEvent.class));
    verify(eventPublisher).publishEvent(any(PlanGeneratedEvent.class));
  }

  @Test
  void acceptReoptSuggestion_alreadyAccepted_idempotent_noPlanLoadNoSave() {
    UUID planId = UUID.randomUUID();
    MealPrepPlanReoptSuggestion sug = suggestion(planId, ReoptSuggestionStatus.ACCEPTED, List.of());
    when(suggestionRepository.findById(sug.getId())).thenReturn(Optional.of(sug));

    PlanReoptSuggestionDto dto = service.acceptReoptSuggestion(planId, sug.getId());

    assertThat(dto.status()).isEqualTo(ReoptSuggestionStatus.ACCEPTED);
    verify(planRepository, never()).findById(any());
    verify(planRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void acceptReoptSuggestion_planIdMismatch_throwsSuggestionNotFound() {
    UUID planId = UUID.randomUUID();
    MealPrepPlanReoptSuggestion sug =
        suggestion(UUID.randomUUID(), ReoptSuggestionStatus.PENDING, List.of());
    when(suggestionRepository.findById(sug.getId())).thenReturn(Optional.of(sug));

    assertThatThrownBy(() -> service.acceptReoptSuggestion(planId, sug.getId()))
        .isInstanceOf(ReoptSuggestionNotFoundException.class);
  }

  @Test
  void acceptReoptSuggestion_unknownSuggestion_throwsSuggestionNotFound() {
    UUID sid = UUID.randomUUID();
    when(suggestionRepository.findById(sid)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.acceptReoptSuggestion(UUID.randomUUID(), sid))
        .isInstanceOf(ReoptSuggestionNotFoundException.class);
  }

  @Test
  void acceptReoptSuggestion_planNotReoptableState_throwsPlanNotReoptable() {
    Plan current =
        PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.ABANDONED, 1, 1);
    MealPrepPlanReoptSuggestion sug =
        suggestion(current.getId(), ReoptSuggestionStatus.PENDING, List.of());
    when(suggestionRepository.findById(sug.getId())).thenReturn(Optional.of(sug));
    when(planRepository.findById(current.getId())).thenReturn(Optional.of(current));

    assertThatThrownBy(() -> service.acceptReoptSuggestion(current.getId(), sug.getId()))
        .isInstanceOf(PlanNotReoptableException.class);
    verify(planRepository, never()).save(any());
  }

  @Test
  void acceptReoptSuggestion_existingScheduledRecipe_mutatedInPlace_servingsZeroKeepsOld() {
    Plan current = PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.ACTIVE, 1, 1);
    MealSlot originalSlot = current.getDays().get(0).getSlots().get(0);
    UUID newRecipeId = UUID.randomUUID();
    // newServings = 0 -> keep the copied servings (2 from PlanTestData), only recipe id changes.
    ProposedSlotChange change =
        new ProposedSlotChange(originalSlot.getId(), null, newRecipeId, null, null, 0, "swap");
    MealPrepPlanReoptSuggestion sug =
        suggestion(current.getId(), ReoptSuggestionStatus.PENDING, List.of(change));
    when(suggestionRepository.findById(sug.getId())).thenReturn(Optional.of(sug));
    when(planRepository.findById(current.getId())).thenReturn(Optional.of(current));

    service.acceptReoptSuggestion(current.getId(), sug.getId());

    ArgumentCaptor<Plan> saved = ArgumentCaptor.forClass(Plan.class);
    verify(planRepository, times(2)).save(saved.capture());
    Plan copy =
        saved.getAllValues().stream()
            .filter(p -> p.getStatus() == PlanStatus.GENERATED)
            .findFirst()
            .orElseThrow();
    ScheduledRecipe sr = copy.getDays().get(0).getSlots().get(0).getScheduledRecipe();
    assertThat(sr.getRecipeId()).isEqualTo(newRecipeId);
    assertThat(sr.getServings()).isEqualTo(2); // unchanged: newServings was 0
  }

  // ---------- rejectReoptSuggestion ----------

  @Test
  void rejectReoptSuggestion_pending_marksRejected_logsDecision() {
    UUID planId = UUID.randomUUID();
    Plan plan = PlanTestData.newPlanGraph(planId, WEEK, 1, PlanStatus.ACTIVE, 1, 1);
    MealPrepPlanReoptSuggestion sug = suggestion(planId, ReoptSuggestionStatus.PENDING, List.of());
    when(suggestionRepository.findById(sug.getId())).thenReturn(Optional.of(sug));
    when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

    PlanReoptSuggestionDto dto = service.rejectReoptSuggestion(planId, sug.getId());

    assertThat(dto.status()).isEqualTo(ReoptSuggestionStatus.REJECTED);
    assertThat(sug.getStatus()).isEqualTo(ReoptSuggestionStatus.REJECTED);
    verify(suggestionRepository).save(sug);
    ArgumentCaptor<DecisionLogEntry> log = ArgumentCaptor.forClass(DecisionLogEntry.class);
    verify(decisionLogWriter).write(log.capture());
    assertThat(log.getValue().kind()).isEqualTo(PlannerDecisionKind.REOPT_SUGGESTION_REJECTED);
  }

  @Test
  void rejectReoptSuggestion_alreadyRejected_idempotent_noSaveNoLog() {
    UUID planId = UUID.randomUUID();
    MealPrepPlanReoptSuggestion sug = suggestion(planId, ReoptSuggestionStatus.REJECTED, List.of());
    when(suggestionRepository.findById(sug.getId())).thenReturn(Optional.of(sug));

    PlanReoptSuggestionDto dto = service.rejectReoptSuggestion(planId, sug.getId());

    assertThat(dto.status()).isEqualTo(ReoptSuggestionStatus.REJECTED);
    verify(suggestionRepository, never()).save(any());
    verifyNoInteractions(decisionLogWriter);
  }

  @Test
  void rejectReoptSuggestion_pendingButPlanGone_skipsDecisionLog_stillRejects() {
    UUID planId = UUID.randomUUID();
    MealPrepPlanReoptSuggestion sug = suggestion(planId, ReoptSuggestionStatus.PENDING, List.of());
    when(suggestionRepository.findById(sug.getId())).thenReturn(Optional.of(sug));
    when(planRepository.findById(planId)).thenReturn(Optional.empty());

    PlanReoptSuggestionDto dto = service.rejectReoptSuggestion(planId, sug.getId());

    assertThat(dto.status()).isEqualTo(ReoptSuggestionStatus.REJECTED);
    verify(suggestionRepository).save(sug);
    verifyNoInteractions(decisionLogWriter);
  }

  @Test
  void rejectReoptSuggestion_planIdMismatch_throwsSuggestionNotFound() {
    UUID planId = UUID.randomUUID();
    MealPrepPlanReoptSuggestion sug =
        suggestion(UUID.randomUUID(), ReoptSuggestionStatus.PENDING, List.of());
    when(suggestionRepository.findById(sug.getId())).thenReturn(Optional.of(sug));

    assertThatThrownBy(() -> service.rejectReoptSuggestion(planId, sug.getId()))
        .isInstanceOf(ReoptSuggestionNotFoundException.class);
  }
}
