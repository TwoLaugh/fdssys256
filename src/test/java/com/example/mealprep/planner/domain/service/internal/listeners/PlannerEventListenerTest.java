package com.example.mealprep.planner.domain.service.internal.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.event.HouseholdSettingsChangedEvent;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogEntry;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter;
import com.example.mealprep.planner.domain.service.internal.decisionlog.PlannerDecisionKind;
import com.example.mealprep.planner.domain.service.internal.reopt.MidWeekReoptCoordinator;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.provisions.event.GenericProvisionChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit tests over {@link PlannerEventListener}'s routing / materiality-gating / null-household
 * / week-window / idempotent-trigger-id / exception-isolation logic. The orchestration
 * collaborators (coordinator, materiality filters) and cross-module reads are Mockito-stubbed —
 * mirroring the established {@code DiscoveryJobRunnerTest} pattern (an orchestration listener whose
 * step collaborators each carry their own dedicated tests). A real {@link ObjectMapper} builds the
 * decision-log inputs. The Testcontainers flow lives in {@code PlannerEventListenerIT}.
 */
@ExtendWith(MockitoExtension.class)
class PlannerEventListenerTest {

  private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID HOUSEHOLD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final LocalDate WEEK = LocalDate.of(2026, 5, 18);
  private static final Instant NOW = Instant.parse("2026-05-18T09:00:00Z");

  @Mock private PlanRepository planRepository;
  @Mock private MidWeekReoptCoordinator reoptCoordinator;
  @Mock private HouseholdQueryService householdQueryService;
  @Mock private ProvisionMaterialityFilter provisionMaterialityFilter;
  @Mock private NutritionMaterialityFilter nutritionMaterialityFilter;
  @Mock private PreferenceMaterialityFilter preferenceMaterialityFilter;
  @Mock private HouseholdMaterialityFilter householdMaterialityFilter;
  @Mock private DecisionLogWriter decisionLogWriter;

  private PlannerEventListener listener;

  @BeforeEach
  void setUp() {
    listener =
        new PlannerEventListener(
            planRepository,
            reoptCoordinator,
            householdQueryService,
            provisionMaterialityFilter,
            nutritionMaterialityFilter,
            preferenceMaterialityFilter,
            householdMaterialityFilter,
            decisionLogWriter,
            new ObjectMapper());
  }

  private HouseholdDto household() {
    return new HouseholdDto(HOUSEHOLD, "h", USER, List.of(), NOW, 0L);
  }

  private Plan activePlan() {
    return PlanTestData.newPlanGraph(HOUSEHOLD, WEEK, 1, PlanStatus.ACTIVE, 1, 1);
  }

  private GenericProvisionChangedEvent provisionEvent() {
    return new GenericProvisionChangedEvent(
        USER, List.of(UUID.randomUUID()), "ADD", UUID.randomUUID(), NOW);
  }

  // ---------- onProvisionChanged ----------

  @Test
  void onProvisionChanged_material_writesListenerDecision_callsCoordinator() {
    Plan plan = activePlan();
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.of(household()));
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenReturn(List.of(plan));
    when(provisionMaterialityFilter.isMaterial(any(), eq(plan))).thenReturn(true);
    when(decisionLogWriter.write(any())).thenReturn(UUID.randomUUID());
    when(reoptCoordinator.requestReopt(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(UUID.randomUUID()));

    listener.onProvisionChanged(provisionEvent());

    ArgumentCaptor<DecisionLogEntry> log = ArgumentCaptor.forClass(DecisionLogEntry.class);
    verify(decisionLogWriter).write(log.capture());
    assertThat(log.getValue().kind()).isEqualTo(PlannerDecisionKind.LISTENER_TRIGGER);
    verify(reoptCoordinator)
        .requestReopt(eq(plan.getId()), eq(ReoptTriggerKind.PROVISIONS), any(), any(), any());
  }

  @Test
  void onProvisionChanged_notMaterial_noCoordinatorCall_noDecisionLog() {
    Plan plan = activePlan();
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.of(household()));
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenReturn(List.of(plan));
    when(provisionMaterialityFilter.isMaterial(any(), eq(plan))).thenReturn(false);

    listener.onProvisionChanged(provisionEvent());

    verifyNoInteractions(decisionLogWriter);
    verify(reoptCoordinator, never()).requestReopt(any(), any(), any(), any(), any());
  }

  @Test
  void onProvisionChanged_userMapsToNoHousehold_shortCircuits_noPlanLookup() {
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.empty());

    listener.onProvisionChanged(provisionEvent());

    verify(planRepository, never()).findByHouseholdIdAndStatusIn(any(), any());
    verifyNoInteractions(reoptCoordinator, decisionLogWriter);
  }

  @Test
  void onProvisionChanged_coordinatorThrowsAiUnavailable_swallowed_noPropagation() {
    Plan plan = activePlan();
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.of(household()));
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenReturn(List.of(plan));
    when(provisionMaterialityFilter.isMaterial(any(), eq(plan))).thenReturn(true);
    when(decisionLogWriter.write(any())).thenReturn(UUID.randomUUID());
    when(reoptCoordinator.requestReopt(any(), any(), any(), any(), any()))
        .thenThrow(new AiUnavailableException("model down"));

    // AiUnavailableException is the graceful-degrade case — must not propagate.
    listener.onProvisionChanged(provisionEvent());

    verify(reoptCoordinator).requestReopt(any(), any(), any(), any(), any());
  }

  @Test
  void onProvisionChanged_unexpectedRuntime_swallowed_noPropagation() {
    when(householdQueryService.getByUserId(USER)).thenThrow(new IllegalStateException("boom"));

    // Generic RuntimeException is logged WARN and swallowed (AFTER_COMMIT failure isolation).
    listener.onProvisionChanged(provisionEvent());

    verifyNoInteractions(reoptCoordinator);
  }

  // ---------- onNutritionIntakeDiverged: week-window guard ----------

  private NutritionIntakeDivergedEvent nutritionEvent(LocalDate onDate) {
    return new NutritionIntakeDivergedEvent(
        USER, onDate, Set.of("protein"), null, UUID.randomUUID(), NOW);
  }

  @Test
  void onNutritionIntakeDiverged_dateInPlanWeek_material_routesNutritionTrigger() {
    Plan plan = activePlan();
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.of(household()));
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenReturn(List.of(plan));
    when(nutritionMaterialityFilter.isMaterial(any(), eq(plan))).thenReturn(true);
    when(decisionLogWriter.write(any())).thenReturn(UUID.randomUUID());
    when(reoptCoordinator.requestReopt(any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());

    listener.onNutritionIntakeDiverged(nutritionEvent(WEEK.plusDays(2)));

    verify(reoptCoordinator)
        .requestReopt(eq(plan.getId()), eq(ReoptTriggerKind.NUTRITION), any(), any(), any());
  }

  @Test
  void onNutritionIntakeDiverged_dateOutsidePlanWeek_skippedBeforeMaterialityCheck() {
    Plan plan = activePlan();
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.of(household()));
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenReturn(List.of(plan));

    // 10 days after week-start is outside the [weekStart, weekStart+7) window.
    listener.onNutritionIntakeDiverged(nutritionEvent(WEEK.plusDays(10)));

    verifyNoInteractions(nutritionMaterialityFilter, reoptCoordinator, decisionLogWriter);
  }

  @Test
  void onNutritionIntakeDiverged_nullDate_weekContainsFalse_skipped() {
    Plan plan = activePlan();
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.of(household()));
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenReturn(List.of(plan));

    listener.onNutritionIntakeDiverged(nutritionEvent(null));

    verifyNoInteractions(nutritionMaterialityFilter, reoptCoordinator);
  }

  // ---------- onPreferenceUpdated ----------

  @Test
  void onPreferenceUpdated_material_routesPreferenceTrigger() {
    Plan plan = activePlan();
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.of(household()));
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenReturn(List.of(plan));
    when(preferenceMaterialityFilter.isMaterial(any(), eq(plan))).thenReturn(true);
    when(decisionLogWriter.write(any())).thenReturn(UUID.randomUUID());
    when(reoptCoordinator.requestReopt(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(UUID.randomUUID()));

    listener.onPreferenceUpdated(
        new HardConstraintsUpdatedEvent(USER, Set.of("allergies"), UUID.randomUUID(), NOW));

    verify(reoptCoordinator)
        .requestReopt(eq(plan.getId()), eq(ReoptTriggerKind.PREFERENCE), any(), any(), any());
  }

  @Test
  void onPreferenceUpdated_noHousehold_shortCircuits() {
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.empty());
    listener.onPreferenceUpdated(
        new HardConstraintsUpdatedEvent(USER, Set.of("allergies"), UUID.randomUUID(), NOW));
    verifyNoInteractions(reoptCoordinator);
  }

  // ---------- onHouseholdConfigChanged: householdId is on the event itself ----------

  @Test
  void onHouseholdConfigChanged_material_routesHouseholdSettingsTrigger_noUserLookup() {
    Plan plan = activePlan();
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenReturn(List.of(plan));
    when(householdMaterialityFilter.isMaterial(any(), eq(plan))).thenReturn(true);
    when(decisionLogWriter.write(any())).thenReturn(UUID.randomUUID());
    when(reoptCoordinator.requestReopt(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(UUID.randomUUID()));

    listener.onHouseholdConfigChanged(
        new HouseholdSettingsChangedEvent(
            HOUSEHOLD, UUID.randomUUID(), Set.of("scheduling"), UUID.randomUUID(), NOW));

    // householdId comes off the event directly — no getByUserId resolution.
    verifyNoInteractions(householdQueryService);
    verify(reoptCoordinator)
        .requestReopt(
            eq(plan.getId()), eq(ReoptTriggerKind.HOUSEHOLD_SETTINGS), any(), any(), any());
  }

  @Test
  void onHouseholdConfigChanged_runtimeError_swallowed() {
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenThrow(new IllegalStateException("db down"));

    listener.onHouseholdConfigChanged(
        new HouseholdSettingsChangedEvent(
            HOUSEHOLD, UUID.randomUUID(), Set.of("scheduling"), UUID.randomUUID(), NOW));

    verifyNoInteractions(reoptCoordinator);
  }

  // ---------- idempotent trigger-event-id derivation ----------

  @Test
  void deriveTriggerEventId_sameTraceAndScope_yieldsStableId_acrossTwoFires() {
    Plan plan = activePlan();
    UUID trace = UUID.randomUUID();
    UUID scopeItem = UUID.randomUUID();
    GenericProvisionChangedEvent ev =
        new GenericProvisionChangedEvent(USER, List.of(scopeItem), "ADD", trace, NOW);
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.of(household()));
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any()))
        .thenReturn(List.of(plan));
    when(provisionMaterialityFilter.isMaterial(any(), eq(plan))).thenReturn(true);
    when(decisionLogWriter.write(any())).thenReturn(UUID.randomUUID());
    when(reoptCoordinator.requestReopt(any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());

    listener.onProvisionChanged(ev);
    listener.onProvisionChanged(ev);

    ArgumentCaptor<UUID> triggerId = ArgumentCaptor.forClass(UUID.class);
    verify(reoptCoordinator, org.mockito.Mockito.times(2))
        .requestReopt(eq(plan.getId()), any(), triggerId.capture(), eq(trace), any());
    // Both fires of the SAME logical event derive the SAME trigger id (idempotency key).
    assertThat(triggerId.getAllValues().get(0)).isEqualTo(triggerId.getAllValues().get(1));
  }

  @Test
  void onProvisionChanged_noActivePlans_noCoordinatorCall() {
    when(householdQueryService.getByUserId(USER)).thenReturn(Optional.of(household()));
    when(planRepository.findByHouseholdIdAndStatusIn(eq(HOUSEHOLD), any())).thenReturn(List.of());
    lenient().when(provisionMaterialityFilter.isMaterial(any(), any())).thenReturn(true);

    listener.onProvisionChanged(provisionEvent());

    verify(reoptCoordinator, never()).requestReopt(any(), any(), any(), any(), any());
  }
}
