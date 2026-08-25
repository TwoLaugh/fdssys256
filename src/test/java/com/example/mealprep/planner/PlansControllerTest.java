package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.planner.api.controller.PlansController;
import com.example.mealprep.planner.api.dto.AbandonPlanRequest;
import com.example.mealprep.planner.api.dto.FeasibilityCheckResultDto;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.PlanGenerationJobDto;
import com.example.mealprep.planner.api.dto.PlanReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.RejectPlanRequest;
import com.example.mealprep.planner.api.dto.ReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.RevertToPlanRequest;
import com.example.mealprep.planner.api.dto.SlotStateChangeRequest;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.domain.service.PlanWriteService;
import com.example.mealprep.planner.domain.service.internal.composer.PlanComposer;
import com.example.mealprep.planner.domain.service.internal.composer.PlanGenerationJobService;
import com.example.mealprep.planner.domain.service.internal.lifecycle.RevertToPlanCoordinator;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.ReoptSuggestionNotFoundException;
import com.example.mealprep.planner.security.PlannerAuth;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link PlansController} against mocked collaborators. The MockMvc ITs exercise
 * request binding, validation and the security filter chain end to end; this class pins the
 * in-method auth guards (401 / 403 / 404 ordering) and the response wiring of every endpoint
 * without a Spring context.
 */
@ExtendWith(MockitoExtension.class)
class PlansControllerTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID HOUSEHOLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID PLAN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID SLOT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID SUGGESTION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final LocalDate WEEK = LocalDate.of(2026, 8, 24);
  private static final String IDEM_KEY = "idem-key-1";

  @Mock private PlanQueryService planQueryService;
  @Mock private PlanWriteService planWriteService;
  @Mock private PlanComposer planComposer;
  @Mock private PlanGenerationJobService planGenerationJobService;
  @Mock private RevertToPlanCoordinator revertToPlanCoordinator;
  @Mock private PlannerAuth plannerAuth;
  @Mock private CurrentUserResolver currentUserResolver;

  @InjectMocks private PlansController controller;

  // ---- generate ------------------------------------------------------------------------------

  @Test
  void generate_composesAndReturns201WithLocation() {
    GeneratePlanRequest request = new GeneratePlanRequest(HOUSEHOLD_ID, WEEK, false);
    UUID newPlanId = UUID.randomUUID();
    PlanDto dto = plan(newPlanId);
    signedIn();
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(true);
    when(planComposer.cachedPlanIdFor(USER_ID, IDEM_KEY)).thenReturn(Optional.empty());
    when(planComposer.compose(request, USER_ID, IDEM_KEY)).thenReturn(newPlanId);
    when(planQueryService.getPlanById(newPlanId)).thenReturn(Optional.of(dto));

    ResponseEntity<PlanDto> response = controller.generate(request, IDEM_KEY);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getLocation())
        .isEqualTo(URI.create("/api/v1/plans/" + newPlanId));
    assertThat(response.getBody()).isSameAs(dto);
    verify(planComposer).compose(request, USER_ID, IDEM_KEY);
  }

  @Test
  void generate_replaysCachedPlanAs200WithoutComposing() {
    GeneratePlanRequest request = new GeneratePlanRequest(HOUSEHOLD_ID, WEEK, false);
    UUID cachedPlanId = UUID.randomUUID();
    PlanDto dto = plan(cachedPlanId);
    signedIn();
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(true);
    when(planComposer.cachedPlanIdFor(USER_ID, IDEM_KEY)).thenReturn(Optional.of(cachedPlanId));
    when(planQueryService.getPlanById(cachedPlanId)).thenReturn(Optional.of(dto));

    ResponseEntity<PlanDto> response = controller.generate(request, IDEM_KEY);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(dto);
    verify(planComposer, never()).compose(request, USER_ID, IDEM_KEY);
  }

  @Test
  void generate_missingCachedPlanBubblesAsNotFound() {
    GeneratePlanRequest request = new GeneratePlanRequest(HOUSEHOLD_ID, WEEK, false);
    UUID cachedPlanId = UUID.randomUUID();
    signedIn();
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(true);
    when(planComposer.cachedPlanIdFor(USER_ID, IDEM_KEY)).thenReturn(Optional.of(cachedPlanId));
    when(planQueryService.getPlanById(cachedPlanId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.generate(request, IDEM_KEY))
        .isInstanceOf(PlanNotFoundException.class);
  }

  @Test
  void generate_missingComposedPlanBubblesAsNotFound() {
    GeneratePlanRequest request = new GeneratePlanRequest(HOUSEHOLD_ID, WEEK, false);
    UUID newPlanId = UUID.randomUUID();
    signedIn();
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(true);
    when(planComposer.cachedPlanIdFor(USER_ID, IDEM_KEY)).thenReturn(Optional.empty());
    when(planComposer.compose(request, USER_ID, IDEM_KEY)).thenReturn(newPlanId);
    when(planQueryService.getPlanById(newPlanId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.generate(request, IDEM_KEY))
        .isInstanceOf(PlanNotFoundException.class);
  }

  @Test
  void generate_nonMemberGets403AndNothingComposed() {
    GeneratePlanRequest request = new GeneratePlanRequest(HOUSEHOLD_ID, WEEK, false);
    signedIn();
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(false);

    assertForbidden(() -> controller.generate(request, IDEM_KEY));
    verifyNoInteractions(planComposer, planQueryService);
  }

  @Test
  void generate_anonymousGets401BeforeAnyWork() {
    GeneratePlanRequest request = new GeneratePlanRequest(HOUSEHOLD_ID, WEEK, false);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.generate(request, IDEM_KEY))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(plannerAuth, planComposer, planQueryService);
  }

  // ---- generateAsync -------------------------------------------------------------------------

  @Test
  void generateAsync_returns202WithJobLocation() {
    GeneratePlanRequest request = new GeneratePlanRequest(HOUSEHOLD_ID, WEEK, false);
    UUID jobId = UUID.randomUUID();
    PlanGenerationJobDto job = PlanGenerationJobDto.running(jobId, HOUSEHOLD_ID, WEEK);
    signedIn();
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(true);
    when(planGenerationJobService.submit(request, USER_ID, IDEM_KEY)).thenReturn(job);

    ResponseEntity<PlanGenerationJobDto> response = controller.generateAsync(request, IDEM_KEY);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getLocation())
        .isEqualTo(URI.create("/api/v1/plans/generate/jobs/" + jobId));
    assertThat(response.getBody()).isSameAs(job);
    verify(planGenerationJobService).submit(request, USER_ID, IDEM_KEY);
  }

  @Test
  void generateAsync_nonMemberGets403AndNothingSubmitted() {
    GeneratePlanRequest request = new GeneratePlanRequest(HOUSEHOLD_ID, WEEK, false);
    signedIn();
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(false);

    assertForbidden(() -> controller.generateAsync(request, IDEM_KEY));
    verifyNoInteractions(planGenerationJobService);
  }

  // ---- generationJob -------------------------------------------------------------------------

  @Test
  void generationJob_returnsJobForHouseholdMember() {
    UUID jobId = UUID.randomUUID();
    PlanGenerationJobDto job = PlanGenerationJobDto.running(jobId, HOUSEHOLD_ID, WEEK);
    signedIn();
    when(planGenerationJobService.get(jobId)).thenReturn(Optional.of(job));
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(true);

    ResponseEntity<PlanGenerationJobDto> response = controller.generationJob(jobId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(job);
  }

  @Test
  void generationJob_unknownJobIs404() {
    UUID jobId = UUID.randomUUID();
    signedIn();
    when(planGenerationJobService.get(jobId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.generationJob(jobId))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(e.getReason()).contains(jobId.toString());
            });
    verifyNoInteractions(plannerAuth);
  }

  @Test
  void generationJob_foreignHouseholdJobIs403() {
    UUID jobId = UUID.randomUUID();
    PlanGenerationJobDto job = PlanGenerationJobDto.running(jobId, HOUSEHOLD_ID, WEEK);
    signedIn();
    when(planGenerationJobService.get(jobId)).thenReturn(Optional.of(job));
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(false);

    assertForbidden(() -> controller.generationJob(jobId));
  }

  // ---- accept / reject / abandon -------------------------------------------------------------

  @Test
  void accept_transitionsAndReturnsReloadedPlan() {
    PlanDto dto = plan(PLAN_ID);
    memberOfPlanHousehold(dto);

    ResponseEntity<PlanDto> response = controller.accept(PLAN_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(dto);
    verify(planWriteService).acceptPlan(PLAN_ID);
  }

  @Test
  void accept_nonMemberGets403AndNoWrite() {
    outsiderToPlanHousehold();

    assertForbidden(() -> controller.accept(PLAN_ID));
    verifyNoInteractions(planWriteService);
  }

  @Test
  void accept_missingPlanIs404BeforeWrite() {
    signedIn();
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.accept(PLAN_ID)).isInstanceOf(PlanNotFoundException.class);
    verifyNoInteractions(planWriteService);
  }

  @Test
  void accept_reloadMissBubblesAsNotFound() {
    // Plan exists for the auth check, then vanishes before the post-write reload.
    PlanDto dto = plan(PLAN_ID);
    signedIn();
    when(planQueryService.getPlanById(PLAN_ID))
        .thenReturn(Optional.of(dto))
        .thenReturn(Optional.empty());
    when(plannerAuth.canAccessPlan(USER_ID, PLAN_ID)).thenReturn(true);

    assertThatThrownBy(() -> controller.accept(PLAN_ID)).isInstanceOf(PlanNotFoundException.class);
    verify(planWriteService).acceptPlan(PLAN_ID);
  }

  @Test
  void reject_passesReasonAndReturnsReloadedPlan() {
    PlanDto dto = plan(PLAN_ID);
    memberOfPlanHousehold(dto);

    ResponseEntity<PlanDto> response =
        controller.reject(PLAN_ID, new RejectPlanRequest("too much fish"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(dto);
    verify(planWriteService).rejectPlan(PLAN_ID, "too much fish");
  }

  @Test
  void reject_nonMemberGets403AndNoWrite() {
    outsiderToPlanHousehold();

    assertForbidden(() -> controller.reject(PLAN_ID, new RejectPlanRequest("too much fish")));
    verifyNoInteractions(planWriteService);
  }

  @Test
  void abandon_passesReasonAndReturnsReloadedPlan() {
    PlanDto dto = plan(PLAN_ID);
    memberOfPlanHousehold(dto);

    ResponseEntity<PlanDto> response =
        controller.abandon(PLAN_ID, new AbandonPlanRequest("away this week"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(dto);
    verify(planWriteService).abandonPlan(PLAN_ID, "away this week");
  }

  @Test
  void abandon_nonMemberGets403AndNoWrite() {
    outsiderToPlanHousehold();

    assertForbidden(() -> controller.abandon(PLAN_ID, new AbandonPlanRequest("away this week")));
    verifyNoInteractions(planWriteService);
  }

  // ---- revert --------------------------------------------------------------------------------

  @Test
  void revert_returns201WithNewGeneration() {
    RevertToPlanRequest request = new RevertToPlanRequest(PLAN_ID);
    UUID newPlanId = UUID.randomUUID();
    PlanDto dto = plan(newPlanId);
    signedIn();
    when(revertToPlanCoordinator.revertToPlan(USER_ID, request)).thenReturn(newPlanId);
    when(planQueryService.getPlanById(newPlanId)).thenReturn(Optional.of(dto));

    ResponseEntity<PlanDto> response = controller.revert(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getLocation())
        .isEqualTo(URI.create("/api/v1/plans/" + newPlanId));
    assertThat(response.getBody()).isSameAs(dto);
    verify(revertToPlanCoordinator).revertToPlan(USER_ID, request);
  }

  @Test
  void revert_missingNewPlanBubblesAsNotFound() {
    RevertToPlanRequest request = new RevertToPlanRequest(PLAN_ID);
    UUID newPlanId = UUID.randomUUID();
    signedIn();
    when(revertToPlanCoordinator.revertToPlan(USER_ID, request)).thenReturn(newPlanId);
    when(planQueryService.getPlanById(newPlanId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.revert(request)).isInstanceOf(PlanNotFoundException.class);
  }

  // ---- changeSlotState -----------------------------------------------------------------------

  @Test
  void changeSlotState_appliesAndReturnsReloadedPlan() {
    PlanDto dto = plan(PLAN_ID);
    memberOfPlanHousehold(dto);

    ResponseEntity<PlanDto> response =
        controller.changeSlotState(PLAN_ID, SLOT_ID, new SlotStateChangeRequest(SlotState.EATEN));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(dto);
    verify(planWriteService).changeSlotState(PLAN_ID, SLOT_ID, SlotState.EATEN);
  }

  @Test
  void changeSlotState_nonMemberGets403AndNoWrite() {
    outsiderToPlanHousehold();

    assertForbidden(
        () ->
            controller.changeSlotState(
                PLAN_ID, SLOT_ID, new SlotStateChangeRequest(SlotState.EATEN)));
    verifyNoInteractions(planWriteService);
  }

  // ---- re-opt suggestion accept / reject / read ----------------------------------------------

  @Test
  void acceptReoptSuggestion_returnsSuggestion() {
    PlanReoptSuggestionDto suggestion = suggestion(SUGGESTION_ID);
    memberOfPlanHousehold(plan(PLAN_ID));
    when(planWriteService.acceptReoptSuggestion(PLAN_ID, SUGGESTION_ID)).thenReturn(suggestion);

    ResponseEntity<PlanReoptSuggestionDto> response =
        controller.acceptReoptSuggestion(PLAN_ID, SUGGESTION_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(suggestion);
  }

  @Test
  void acceptReoptSuggestion_nonMemberGets403AndNoWrite() {
    outsiderToPlanHousehold();

    assertForbidden(() -> controller.acceptReoptSuggestion(PLAN_ID, SUGGESTION_ID));
    verifyNoInteractions(planWriteService);
  }

  @Test
  void rejectReoptSuggestion_returnsSuggestion() {
    PlanReoptSuggestionDto suggestion = suggestion(SUGGESTION_ID);
    memberOfPlanHousehold(plan(PLAN_ID));
    when(planWriteService.rejectReoptSuggestion(PLAN_ID, SUGGESTION_ID)).thenReturn(suggestion);

    ResponseEntity<PlanReoptSuggestionDto> response =
        controller.rejectReoptSuggestion(PLAN_ID, SUGGESTION_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(suggestion);
  }

  @Test
  void rejectReoptSuggestion_nonMemberGets403AndNoWrite() {
    outsiderToPlanHousehold();

    assertForbidden(() -> controller.rejectReoptSuggestion(PLAN_ID, SUGGESTION_ID));
    verifyNoInteractions(planWriteService);
  }

  @Test
  void getReoptSuggestion_returnsStoredProposal() {
    PlanReoptSuggestionDto suggestion = suggestion(SUGGESTION_ID);
    memberOfPlanHousehold(plan(PLAN_ID));
    when(planQueryService.getPlanReoptSuggestion(PLAN_ID, SUGGESTION_ID))
        .thenReturn(Optional.of(suggestion));

    ResponseEntity<PlanReoptSuggestionDto> response =
        controller.getReoptSuggestion(PLAN_ID, SUGGESTION_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(suggestion);
  }

  @Test
  void getReoptSuggestion_unknownSuggestionIs404() {
    memberOfPlanHousehold(plan(PLAN_ID));
    when(planQueryService.getPlanReoptSuggestion(PLAN_ID, SUGGESTION_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getReoptSuggestion(PLAN_ID, SUGGESTION_ID))
        .isInstanceOf(ReoptSuggestionNotFoundException.class);
  }

  @Test
  void getReoptSuggestion_nonMemberGets403AndNoRead() {
    outsiderToPlanHousehold();

    assertForbidden(() -> controller.getReoptSuggestion(PLAN_ID, SUGGESTION_ID));
    verify(planQueryService, never()).getPlanReoptSuggestion(PLAN_ID, SUGGESTION_ID);
  }

  // ---- household-scoped reads ----------------------------------------------------------------

  @Test
  void getActive_returnsActivePlan() {
    PlanDto dto = plan(PLAN_ID);
    memberOfHousehold();
    when(planQueryService.getActivePlan(HOUSEHOLD_ID, WEEK)).thenReturn(Optional.of(dto));

    ResponseEntity<PlanDto> response = controller.getActive(HOUSEHOLD_ID, WEEK);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(dto);
  }

  @Test
  void getActive_noActivePlanIs404() {
    memberOfHousehold();
    when(planQueryService.getActivePlan(HOUSEHOLD_ID, WEEK)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getActive(HOUSEHOLD_ID, WEEK))
        .isInstanceOf(PlanNotFoundException.class)
        .hasMessageContaining(HOUSEHOLD_ID.toString());
  }

  @Test
  void getActive_nonMemberGets403AndNoRead() {
    outsiderToHousehold();

    assertForbidden(() -> controller.getActive(HOUSEHOLD_ID, WEEK));
    verifyNoInteractions(planQueryService);
  }

  @Test
  void getHistory_returnsGenerations() {
    List<PlanDto> history = List.of(plan(PLAN_ID), plan(UUID.randomUUID()));
    memberOfHousehold();
    when(planQueryService.getPlanHistory(HOUSEHOLD_ID, WEEK)).thenReturn(history);

    ResponseEntity<List<PlanDto>> response = controller.getHistory(HOUSEHOLD_ID, WEEK);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(history);
  }

  @Test
  void getHistory_nonMemberGets403AndNoRead() {
    outsiderToHousehold();

    assertForbidden(() -> controller.getHistory(HOUSEHOLD_ID, WEEK));
    verifyNoInteractions(planQueryService);
  }

  @Test
  void getBetween_pagesWithGivenPageRequest() {
    LocalDate from = WEEK.minusWeeks(4);
    LocalDate to = WEEK;
    Page<PlanDto> page = new PageImpl<>(List.of(plan(PLAN_ID)));
    memberOfHousehold();
    when(planQueryService.getPlansBetween(HOUSEHOLD_ID, from, to, PageRequest.of(1, 50)))
        .thenReturn(page);

    ResponseEntity<Page<PlanDto>> response = controller.getBetween(HOUSEHOLD_ID, from, to, 1, 50);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(page);
    verify(planQueryService).getPlansBetween(HOUSEHOLD_ID, from, to, PageRequest.of(1, 50));
  }

  @Test
  void getBetween_nonMemberGets403AndNoRead() {
    outsiderToHousehold();

    assertForbidden(() -> controller.getBetween(HOUSEHOLD_ID, WEEK.minusWeeks(1), WEEK, 0, 20));
    verifyNoInteractions(planQueryService);
  }

  @Test
  void getSuggestions_returnsPendingPage() {
    Page<ReoptSuggestionDto> page = new PageImpl<>(List.of(pendingSuggestion()));
    memberOfHousehold();
    when(planQueryService.getPendingSuggestions(HOUSEHOLD_ID, PageRequest.of(0, 20)))
        .thenReturn(page);

    ResponseEntity<Page<ReoptSuggestionDto>> response =
        controller.getSuggestions(HOUSEHOLD_ID, 0, 20);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(page);
  }

  @Test
  void getSuggestions_nonMemberGets403AndNoRead() {
    outsiderToHousehold();

    assertForbidden(() -> controller.getSuggestions(HOUSEHOLD_ID, 0, 20));
    verifyNoInteractions(planQueryService);
  }

  @Test
  void getFeasibility_returnsCheckResult() {
    FeasibilityCheckResultDto result = new FeasibilityCheckResultDto(true, List.of(), List.of());
    memberOfHousehold();
    when(planQueryService.checkFeasibility(HOUSEHOLD_ID, WEEK)).thenReturn(result);

    ResponseEntity<FeasibilityCheckResultDto> response =
        controller.getFeasibility(HOUSEHOLD_ID, WEEK);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(result);
  }

  @Test
  void getFeasibility_nonMemberGets403AndNoRead() {
    outsiderToHousehold();

    assertForbidden(() -> controller.getFeasibility(HOUSEHOLD_ID, WEEK));
    verifyNoInteractions(planQueryService);
  }

  // ---- getPlan -------------------------------------------------------------------------------

  @Test
  void getPlan_returnsHydratedPlan() {
    PlanDto dto = plan(PLAN_ID);
    memberOfPlanHousehold(dto);

    ResponseEntity<PlanDto> response = controller.getPlan(PLAN_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(dto);
  }

  @Test
  void getPlan_missingPlanIs404() {
    signedIn();
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getPlan(PLAN_ID)).isInstanceOf(PlanNotFoundException.class);
    verifyNoInteractions(plannerAuth);
  }

  @Test
  void getPlan_nonMemberGets403() {
    outsiderToPlanHousehold();

    assertForbidden(() -> controller.getPlan(PLAN_ID));
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private void signedIn() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
  }

  private void memberOfHousehold() {
    signedIn();
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(true);
  }

  private void outsiderToHousehold() {
    signedIn();
    when(plannerAuth.canAccessHousehold(USER_ID, HOUSEHOLD_ID)).thenReturn(false);
  }

  /** The plan exists and the caller is a member of its household; reload serves {@code dto}. */
  private void memberOfPlanHousehold(PlanDto dto) {
    signedIn();
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.of(dto));
    when(plannerAuth.canAccessPlan(USER_ID, PLAN_ID)).thenReturn(true);
  }

  private void outsiderToPlanHousehold() {
    signedIn();
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.of(plan(PLAN_ID)));
    when(plannerAuth.canAccessPlan(USER_ID, PLAN_ID)).thenReturn(false);
  }

  private static void assertForbidden(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  private static PlanDto plan(UUID id) {
    return new PlanDto(
        id,
        HOUSEHOLD_ID,
        WEEK,
        1,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        0L,
        null,
        null);
  }

  private static PlanReoptSuggestionDto suggestion(UUID id) {
    return new PlanReoptSuggestionDto(
        id, PLAN_ID, null, null, null, null, "swap Thursday dinner", null, null, null, null);
  }

  private static ReoptSuggestionDto pendingSuggestion() {
    return new ReoptSuggestionDto(
        SUGGESTION_ID,
        HOUSEHOLD_ID,
        WEEK,
        PLAN_ID,
        null,
        null,
        List.of(),
        "swap Thursday dinner",
        null,
        null,
        null,
        null);
  }
}
