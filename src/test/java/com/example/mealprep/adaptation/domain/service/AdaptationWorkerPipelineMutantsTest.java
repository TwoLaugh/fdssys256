package com.example.mealprep.adaptation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.AdaptationContextAssembler;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.TriggerInputs;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.AdaptationRollupDto;
import com.example.mealprep.adaptation.api.dto.DataModelChangeType;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationLlmInvoker;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationLockAcquirer;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationTraceWriter;
import com.example.mealprep.adaptation.domain.service.internal.CandidateGenerator;
import com.example.mealprep.adaptation.domain.service.internal.ChangeDimensionResolver;
import com.example.mealprep.adaptation.domain.service.internal.CharacterPreservationGate;
import com.example.mealprep.adaptation.domain.service.internal.ConfidenceFloorGate;
import com.example.mealprep.adaptation.domain.service.internal.FingerprintRefresher;
import com.example.mealprep.adaptation.domain.service.internal.PendingChangeStore;
import com.example.mealprep.adaptation.domain.service.internal.PlannerHintEmitter;
import com.example.mealprep.adaptation.domain.service.internal.RebaseOrchestrator;
import com.example.mealprep.adaptation.domain.service.internal.ScoringEngine;
import com.example.mealprep.adaptation.event.AdaptationCandidateProducedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobCompletedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobFailedEvent;
import com.example.mealprep.adaptation.exception.AdaptationHardConstraintViolationException;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.SubstitutedItemDto;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.exception.RecipeVersionConflictException;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedBranchCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedSubstitutionCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Worker-pipeline internals: failure excerpts, trace and decision-log field values, post-write
 * hooks, status transitions, candidate selection and the rebase re-read. Pins the exact values the
 * mutation report showed unasserted.
 */
class AdaptationWorkerPipelineMutantsTest {

  // ------------------------------------------------------------------------------------------
  // Step 3 failure excerpts
  // ------------------------------------------------------------------------------------------

  @Test
  void noGeneratedCandidates_failsHardFilter_withNoCandidatesExcerpt() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);
    when(w.candidateGenerator.generate(any(), any())).thenReturn(List.of());

    w.service.processJob(job);

    AdaptationJobFailedEvent failed = w.captureFailedEvent();
    assertThat(failed.reason()).isEqualTo(JobFailureReason.HARD_FILTER);
    assertThat(failed.excerpt()).isEqualTo("no-candidates");
    // FAILED is terminal: the status writer stamps completedAt.
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getCompletedAt()).isNotNull();
  }

  @Test
  void allCandidatesInfeasible_failsHardFilter_withCountingExcerpt() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);
    when(w.candidateGenerator.generate(any(), any()))
        .thenReturn(
            List.of(
                candidate(0, swapDiff("beef", "tofu")), candidate(1, swapDiff("beef", "seitan"))));
    when(w.filter.checkRecipe(any(), any(), anyList(), any()))
        .thenReturn(new FilterResult(false, List.of()));

    w.service.processJob(job);

    AdaptationJobFailedEvent failed = w.captureFailedEvent();
    assertThat(failed.excerpt()).isEqualTo("hard-filter: all 2 candidates infeasible");
  }

  // ------------------------------------------------------------------------------------------
  // Trace + decision-log field values (Step 8) and the completion event
  // ------------------------------------------------------------------------------------------

  @Test
  void happyDirectVersion_writesExactTraceAndDecisionLogValues() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    job.setPromptTemplateVersion("v3");
    w.stubCommon(job);
    w.stubHappyLlm(AdaptationClassification.VERSION);
    UUID versionId = UUID.randomUUID();
    when(w.recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(versionId, UUID.randomUUID()));

    w.service.processJob(job);

    ArgumentCaptor<AdaptationTraceWriter.TraceData> trace =
        ArgumentCaptor.forClass(AdaptationTraceWriter.TraceData.class);
    verify(w.traceWriter).write(trace.capture());
    AdaptationTraceWriter.TraceData data = trace.getValue();
    assertThat(data.promptTemplateVersion()).isEqualTo("v3");
    assertThat(data.rawAiResponse()).isNotNull();
    assertThat(data.chosenCandidateIndex()).isEqualTo(0);
    assertThat(data.validationResult()).isEqualTo(ValidationResult.PASSED);
    assertThat(data.outcomeTargetId()).isEqualTo(versionId);
    // A wall-clock duration, not a sum of epoch millis.
    assertThat(data.durationMs()).isBetween(0, 60_000);

    ArgumentCaptor<DecisionLogWriteRequest> decision =
        ArgumentCaptor.forClass(DecisionLogWriteRequest.class);
    verify(w.decisionLogService).write(decision.capture());
    assertThat(decision.getValue().triggeredBy()).isEqualTo("user");
    assertThat(decision.getValue().reasoning()).isEqualTo("picked");

    ArgumentCaptor<AdaptationJobCompletedEvent> completed =
        ArgumentCaptor.forClass(AdaptationJobCompletedEvent.class);
    verify(w.events).publishEvent(completed.capture());
    assertThat(completed.getValue().confidence()).isEqualByComparingTo("0.9");
    // Step 10 status write ran.
    assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(job.getCompletedAt()).isNotNull();
  }

  @Test
  void autoSkip_writesNullRawResponseAndIndex_passedValidation_andAutoSkipReasoning() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.FEEDBACK, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);
    when(w.scoringEngine.shouldAutoSkipStageC(any())).thenReturn(true);

    w.service.processJob(job);

    verify(w.llmInvoker, never()).invoke(any(), any());
    ArgumentCaptor<AdaptationTraceWriter.TraceData> trace =
        ArgumentCaptor.forClass(AdaptationTraceWriter.TraceData.class);
    verify(w.traceWriter).write(trace.capture());
    assertThat(trace.getValue().rawAiResponse()).isNull();
    assertThat(trace.getValue().chosenCandidateIndex()).isNull();
    assertThat(trace.getValue().validationResult()).isEqualTo(ValidationResult.PASSED);

    ArgumentCaptor<DecisionLogWriteRequest> decision =
        ArgumentCaptor.forClass(DecisionLogWriteRequest.class);
    verify(w.decisionLogService).write(decision.capture());
    assertThat(decision.getValue().reasoning()).isEqualTo("auto-skip: top score 2x");
    assertThat(decision.getValue().triggeredBy()).isEqualTo("feedback");
  }

  @Test
  void lowConfidenceResponse_recordsLowConfidenceValidationOnTheTrace() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    // 0.4 is under the 0.50 floor: the policy downgrades to PENDING_CHANGE.
    w.stubLlm(
        response(0, AdaptationClassification.VERSION, "0.4", "0.9", swapDiff("beef", "chicken")));

    w.service.processJob(job);

    ArgumentCaptor<AdaptationTraceWriter.TraceData> trace =
        ArgumentCaptor.forClass(AdaptationTraceWriter.TraceData.class);
    verify(w.traceWriter).write(trace.capture());
    assertThat(trace.getValue().validationResult()).isEqualTo(ValidationResult.LOW_CONFIDENCE);
    verify(w.pendingChangeStore).create(any(), any(), any(), any(), any(), any(), any());
    verify(w.recipeWriteApi, never()).saveAdaptedVersion(any());
  }

  @Test
  void nullConfidenceResponse_publishesCompletionWithZeroConfidence() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.FEEDBACK, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);
    w.stubLlm(
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.VERSION,
            "picked",
            "",
            null,
            new BigDecimal("0.9"),
            null,
            swapDiff("beef", "chicken"),
            List.of()));

    w.service.processJob(job);

    ArgumentCaptor<AdaptationJobCompletedEvent> completed =
        ArgumentCaptor.forClass(AdaptationJobCompletedEvent.class);
    verify(w.events).publishEvent(completed.capture());
    assertThat(completed.getValue().confidence()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void pendingChangeStore_receivesBaseVersionAndPromptVersionFromContextAndJob() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.FEEDBACK, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    job.setPromptTemplateVersion("v3");
    w.stubCommon(job);
    w.stubHappyLlm(AdaptationClassification.VERSION);

    w.service.processJob(job);

    verify(w.pendingChangeStore)
        .create(
            any(),
            any(),
            any(),
            eq(w.currentVersion.id()),
            eq(w.currentVersion.branchId()),
            eq("v3"),
            any());
  }

  @Test
  void pendingChangeStore_promptVersionDefaultsToV0WhenJobHasNone() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.FEEDBACK, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);
    w.stubHappyLlm(AdaptationClassification.VERSION);

    w.service.processJob(job);

    verify(w.pendingChangeStore).create(any(), any(), any(), any(), any(), eq("v0"), any());
  }

  @Test
  void candidateProducedEvent_carriesTheTopCandidatesTasteScore() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.FEEDBACK, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);
    w.stubHappyLlm(AdaptationClassification.VERSION);

    w.service.processJob(job);

    ArgumentCaptor<AdaptationCandidateProducedEvent> event =
        ArgumentCaptor.forClass(AdaptationCandidateProducedEvent.class);
    verify(w.events).publishEvent(event.capture());
    assertThat(event.getValue().topCandidateScore()).isEqualByComparingTo("0.7");
    assertThat(event.getValue().candidateCount()).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Failure-path trace values
  // ------------------------------------------------------------------------------------------

  @Test
  void failureTrace_carriesJobPromptVersion_andWallClockDuration() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    job.setPromptTemplateVersion("v3");
    w.stubCommon(job);
    when(w.candidateGenerator.generate(any(), any())).thenReturn(List.of());

    w.service.processJob(job);

    ArgumentCaptor<AdaptationTraceWriter.TraceData> trace =
        ArgumentCaptor.forClass(AdaptationTraceWriter.TraceData.class);
    verify(w.traceWriter).write(trace.capture());
    assertThat(trace.getValue().promptTemplateVersion()).isEqualTo("v3");
    assertThat(trace.getValue().durationMs()).isBetween(0, 60_000);
  }

  @Test
  void failureTrace_promptVersionDefaultsToV0() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);
    when(w.candidateGenerator.generate(any(), any())).thenReturn(List.of());

    w.service.processJob(job);

    ArgumentCaptor<AdaptationTraceWriter.TraceData> trace =
        ArgumentCaptor.forClass(AdaptationTraceWriter.TraceData.class);
    verify(w.traceWriter).write(trace.capture());
    assertThat(trace.getValue().promptTemplateVersion()).isEqualTo("v0");
  }

  // ------------------------------------------------------------------------------------------
  // Step 7b post-write hooks
  // ------------------------------------------------------------------------------------------

  @Test
  void directVersionWrite_invalidatesOldVersionHints_andSkipsFingerprintRefresh() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    w.stubHappyLlm(AdaptationClassification.VERSION);
    when(w.recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(UUID.randomUUID(), UUID.randomUUID()));

    w.service.processJob(job);

    verify(w.plannerHintEmitter).invalidateHintsForOldVersion(w.currentVersion.id());
    verify(w.fingerprintRefresher, never())
        .refreshOnBranch(any(), any(), any(), any(), any(), any());
  }

  @Test
  void directBranchWrite_refreshesFingerprintFromTheResponseDiff() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    ObjectNode diff = swapDiff("beef", "chicken");
    // Char score below 0.6 with a high-coherence BRANCH forces the branch path.
    w.stubLlm(response(0, AdaptationClassification.BRANCH, "0.9", "0.4", diff));
    UUID branchId = UUID.randomUUID();
    when(w.recipeWriteApi.saveAdaptedBranch(any(SaveAdaptedBranchCommand.class)))
        .thenReturn(branchDto(branchId));

    w.service.processJob(job);

    verify(w.plannerHintEmitter).invalidateHintsForOldVersion(w.currentVersion.id());
    verify(w.fingerprintRefresher)
        .refreshOnBranch(job.getRecipeId(), branchId, branchId, diff, diff.toString(), job.getId());
  }

  @Test
  void directBranchWrite_withoutFinalDiff_skipsFingerprintRefresh() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    w.stubLlm(
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.BRANCH,
            "picked",
            "",
            new BigDecimal("0.9"),
            new BigDecimal("0.4"),
            null,
            null,
            List.of()));
    when(w.recipeWriteApi.saveAdaptedBranch(any(SaveAdaptedBranchCommand.class)))
        .thenReturn(branchDto(UUID.randomUUID()));

    w.service.processJob(job);

    verify(w.fingerprintRefresher, never())
        .refreshOnBranch(any(), any(), any(), any(), any(), any());
  }

  @Test
  void pendingOutcome_leavesHintsAndFingerprintAlone() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.FEEDBACK, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);
    w.stubHappyLlm(AdaptationClassification.VERSION);

    w.service.processJob(job);

    verify(w.plannerHintEmitter, never()).invalidateHintsForOldVersion(any());
    verify(w.fingerprintRefresher, never())
        .refreshOnBranch(any(), any(), any(), any(), any(), any());
  }

  // ------------------------------------------------------------------------------------------
  // transitionJobStatus
  // ------------------------------------------------------------------------------------------

  @Test
  void transition_toDone_stampsCompletedAt() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

    w.service.transitionJobStatus(job.getId(), JobStatus.DONE, null, null);

    assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(job.getCompletedAt()).isNotNull();
    verify(w.jobRepository).saveAndFlush(job);
  }

  @Test
  void transition_toFailed_storesReasonExcerptAndCompletedAt() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

    w.service.transitionJobStatus(job.getId(), JobStatus.FAILED, JobFailureReason.TIMEOUT, "slow");

    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getFailureReason()).isEqualTo(JobFailureReason.TIMEOUT);
    assertThat(job.getFailureExcerpt()).isEqualTo("slow");
    assertThat(job.getCompletedAt()).isNotNull();
  }

  @Test
  void transition_toRunning_leavesCompletedAtUnset() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

    w.service.transitionJobStatus(job.getId(), JobStatus.RUNNING, null, null);

    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(job.getCompletedAt()).isNull();
  }

  @Test
  void transition_truncatesOverlongExcerptTo512() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
    String excerpt = "x".repeat(600);

    w.service.transitionJobStatus(job.getId(), JobStatus.FAILED, JobFailureReason.UNKNOWN, excerpt);

    assertThat(job.getFailureExcerpt()).isEqualTo("x".repeat(512));
  }

  // ------------------------------------------------------------------------------------------
  // chosenCandidate / currentVersionKeys / filter internals
  // ------------------------------------------------------------------------------------------

  @Test
  void chosenCandidate_matchesByIndex_notListPosition() {
    Wiring w = new Wiring();
    AdaptationCandidateDto first = candidate(0, swapDiff("beef", "chicken"));
    AdaptationCandidateDto second = candidate(1, swapDiff("beef", "pork"));
    AdaptationContext ctx = w.contextWith("beef").withCandidates(List.of(first, second));

    assertThat(
            w.service.chosenCandidate(
                ctx, response(1, AdaptationClassification.VERSION, "0.9", "0.9", null)))
        .isSameAs(second);
    assertThat(
            w.service.chosenCandidate(
                ctx, response(0, AdaptationClassification.VERSION, "0.9", "0.9", null)))
        .isSameAs(first);
  }

  @Test
  void chosenCandidate_negativeIndex_unknownIndex_orNullContext_yieldNull() {
    Wiring w = new Wiring();
    AdaptationContext ctx =
        w.contextWith("beef").withCandidates(List.of(candidate(0, swapDiff("beef", "chicken"))));

    assertThat(
            w.service.chosenCandidate(
                ctx, response(-1, AdaptationClassification.NO_CHANGE, "0.9", "0.9", null)))
        .isNull();
    assertThat(
            w.service.chosenCandidate(
                ctx, response(7, AdaptationClassification.VERSION, "0.9", "0.9", null)))
        .isNull();
    assertThat(
            w.service.chosenCandidate(
                null, response(0, AdaptationClassification.VERSION, "0.9", "0.9", null)))
        .isNull();
  }

  @Test
  void filterChecksDistinctNonBlankIngredientKeysOfTheCurrentVersion() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    RecipeVersionDto version =
        versionWithIngredients(
            ingredient("milk"), ingredient(null), ingredient("  "), ingredient("milk"));
    AdaptationContext ctx = contextWithVersion(version);
    when(w.filter.checkRecipe(any(), any(), anyList(), any()))
        .thenReturn(new FilterResult(true, List.of()));
    ObjectNode noChange = JsonNodeFactory.instance.objectNode();
    noChange.put("kind", "portion-adjust");

    List<AdaptationCandidateDto> out =
        w.service.filterFeasibleCandidates(job, ctx, List.of(candidate(0, noChange)));

    assertThat(out).hasSize(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(w.filter).checkRecipe(any(), any(), keys.capture(), any());
    assertThat(keys.getValue()).containsExactly("milk");
  }

  @Test
  void filter_withNullContext_checksAnEmptyKeySet() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    when(w.filter.checkRecipe(any(), any(), anyList(), any()))
        .thenReturn(new FilterResult(true, List.of()));
    ObjectNode noChange = JsonNodeFactory.instance.objectNode();
    noChange.put("kind", "portion-adjust");

    w.service.filterFeasibleCandidates(job, null, List.of(candidate(0, noChange)));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(w.filter).checkRecipe(any(), any(), keys.capture(), any());
    assertThat(keys.getValue()).isEmpty();
  }

  @Test
  void filter_dropsCandidateEvenWhenTheResultCarriesNullViolations() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    AdaptationContext ctx = w.contextWith("beef");
    when(w.filter.checkRecipe(any(), any(), anyList(), any()))
        .thenReturn(new FilterResult(false, null));

    List<AdaptationCandidateDto> out =
        w.service.filterFeasibleCandidates(
            job, ctx, List.of(candidate(0, swapDiff("beef", "tofu"))));

    assertThat(out).isEmpty();
  }

  @Test
  void recheck_withNullViolations_reportsZeroViolationsInTheMessage() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    AdaptationContext ctx = w.contextWith("beef");
    when(w.filter.checkRecipe(any(), any(), anyList(), any()))
        .thenReturn(new FilterResult(false, null));

    assertThatThrownBy(
            () ->
                w.service.recheckFinalDiff(
                    job,
                    ctx,
                    ctx,
                    response(
                        0,
                        AdaptationClassification.VERSION,
                        "0.9",
                        "0.9",
                        swapDiff("beef", "tofu"))))
        .isInstanceOf(AdaptationHardConstraintViolationException.class)
        .hasMessageContaining("0 hard-constraint violation(s)");
  }

  // ------------------------------------------------------------------------------------------
  // applyDirect / applyPlanOverlay command shapes and the rebase re-read
  // ------------------------------------------------------------------------------------------

  @Test
  void applyDirect_versionCommand_carriesTheResponseDiffAndCurrentVersionExpectations() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    AdaptationContext ctx = w.contextWith("beef");
    ObjectNode diff = swapDiff("beef", "chicken");
    when(w.recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(UUID.randomUUID(), UUID.randomUUID()));

    w.service.applyDirect(
        job,
        ctx,
        response(0, AdaptationClassification.VERSION, "0.9", "0.9", diff),
        AdaptationClassification.VERSION);

    ArgumentCaptor<SaveAdaptedVersionCommand> cmd =
        ArgumentCaptor.forClass(SaveAdaptedVersionCommand.class);
    verify(w.recipeWriteApi).saveAdaptedVersion(cmd.capture());
    assertThat(cmd.getValue().changeDiff()).isEqualTo(diff);
    assertThat(cmd.getValue().branchId()).isEqualTo(w.currentVersion.branchId());
    assertThat(cmd.getValue().expectedParentVersionId()).isEqualTo(w.currentVersion.id());
    assertThat(cmd.getValue().expectedParentVersionNumber())
        .isEqualTo(w.currentVersion.versionNumber());
  }

  @Test
  void applyDirect_nullContextAndNullDiff_writesEmptyDiffWithNullExpectations() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    when(w.recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(UUID.randomUUID(), UUID.randomUUID()));

    w.service.applyDirect(
        job,
        null,
        response(0, AdaptationClassification.VERSION, "0.9", "0.9", null),
        AdaptationClassification.VERSION);

    ArgumentCaptor<SaveAdaptedVersionCommand> cmd =
        ArgumentCaptor.forClass(SaveAdaptedVersionCommand.class);
    verify(w.recipeWriteApi).saveAdaptedVersion(cmd.capture());
    assertThat(cmd.getValue().branchId()).isNull();
    assertThat(cmd.getValue().expectedParentVersionId()).isNull();
    assertThat(cmd.getValue().changeDiff().isObject()).isTrue();
    assertThat(cmd.getValue().changeDiff().isEmpty()).isTrue();
  }

  @Test
  void applyDirect_branchCommand_branchesFromTheCurrentVersion() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    AdaptationContext ctx = w.contextWith("beef");
    when(w.recipeWriteApi.saveAdaptedBranch(any(SaveAdaptedBranchCommand.class)))
        .thenReturn(branchDto(UUID.randomUUID()));

    w.service.applyDirect(
        job,
        ctx,
        response(0, AdaptationClassification.BRANCH, "0.9", "0.9", swapDiff("beef", "chicken")),
        AdaptationClassification.BRANCH);

    ArgumentCaptor<SaveAdaptedBranchCommand> cmd =
        ArgumentCaptor.forClass(SaveAdaptedBranchCommand.class);
    verify(w.recipeWriteApi).saveAdaptedBranch(cmd.capture());
    assertThat(cmd.getValue().parentBranchId()).isEqualTo(w.currentVersion.branchId());
    assertThat(cmd.getValue().branchPointVersionId()).isEqualTo(w.currentVersion.id());
  }

  @Test
  void applyDirect_branchConflict_rebasesBranchPointFromTheFreshHead() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    AdaptationContext ctx = w.contextWith("beef");
    // The head the re-read sees is a different version than the one the first attempt used.
    RecipeVersionDto head = versionDto(UUID.randomUUID(), UUID.randomUUID());
    when(w.contextAssembler.assemble(any(), anyList(), any(TriggerInputs.class)))
        .thenReturn(contextWithVersion(head));
    when(w.recipeWriteApi.saveAdaptedBranch(any(SaveAdaptedBranchCommand.class)))
        .thenThrow(new RecipeVersionConflictException("head moved"))
        .thenReturn(branchDto(UUID.randomUUID()));

    w.service.applyDirect(
        job,
        ctx,
        response(0, AdaptationClassification.BRANCH, "0.9", "0.9", swapDiff("beef", "chicken")),
        AdaptationClassification.BRANCH);

    ArgumentCaptor<SaveAdaptedBranchCommand> cmds =
        ArgumentCaptor.forClass(SaveAdaptedBranchCommand.class);
    verify(w.recipeWriteApi, times(2)).saveAdaptedBranch(cmds.capture());
    SaveAdaptedBranchCommand rebased = cmds.getAllValues().get(1);
    assertThat(rebased.parentBranchId()).isEqualTo(head.branchId());
    assertThat(rebased.branchPointVersionId()).isEqualTo(head.id());
  }

  @Test
  void planOverlay_fallsBackToTheChosenCandidatesDiffWhenTheResponseHasNone() {
    Wiring w = new Wiring();
    AdaptationJob job = w.job(JobSource.PLAN_TIME, Catalogue.USER, ApprovalPolicy.PLAN_OVERLAY);
    AdaptationContext withCandidates =
        w.contextWith("beef").withCandidates(List.of(candidate(0, swapDiff("beef", "chicken"))));
    when(w.recipeWriteApi.saveAdaptedSubstitution(any(SaveAdaptedSubstitutionCommand.class)))
        .thenReturn(substitutionDto(UUID.randomUUID()));

    w.service.applyPlanOverlay(
        job,
        null,
        withCandidates,
        response(0, AdaptationClassification.SUBSTITUTION, "0.9", "0.9", null));

    ArgumentCaptor<SaveAdaptedSubstitutionCommand> cmd =
        ArgumentCaptor.forClass(SaveAdaptedSubstitutionCommand.class);
    verify(w.recipeWriteApi).saveAdaptedSubstitution(cmd.capture());
    assertThat(cmd.getValue().original().ingredientMappingKey()).isEqualTo("beef");
    assertThat(cmd.getValue().substitute().ingredientMappingKey()).isEqualTo("chicken");
  }

  // ------------------------------------------------------------------------------------------
  // triggerInputsFromJob: DATA_MODEL_CHANGE and IMPORT branches
  // ------------------------------------------------------------------------------------------

  @Test
  void triggerInputs_dataModel_prefersChangeSummaryOverTheWholeInputs() {
    Wiring w = new Wiring();
    ObjectNode inputs = JsonNodeFactory.instance.objectNode();
    inputs.put("changeType", DataModelChangeType.NUTRITION_TARGETS.name());
    ObjectNode summary = inputs.putObject("changeSummary");
    summary.put("surface", "targets");
    AdaptationJob job =
        w.job(JobSource.DATA_MODEL_CHANGE, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    job.setInputs(inputs);

    TriggerInputs ti = w.service.triggerInputsFromJob(job);

    assertThat(ti.dataModelChange()).isEqualTo(summary);
  }

  @Test
  void triggerInputs_dataModel_withoutSummary_fallsBackToTheWholeInputsNode() {
    Wiring w = new Wiring();
    ObjectNode inputs = JsonNodeFactory.instance.objectNode();
    inputs.put("changeType", DataModelChangeType.PREFERENCE.name());
    AdaptationJob job =
        w.job(JobSource.DATA_MODEL_CHANGE, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    job.setInputs(inputs);

    TriggerInputs ti = w.service.triggerInputsFromJob(job);

    assertThat(ti.dataModelChange()).isEqualTo(inputs);
  }

  @Test
  void triggerInputs_dataModel_nullInputs_yieldsNullSummary() {
    Wiring w = new Wiring();
    AdaptationJob job =
        w.job(JobSource.DATA_MODEL_CHANGE, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    job.setInputs(null);

    assertThat(w.service.triggerInputsFromJob(job).dataModelChange()).isNull();
  }

  @Test
  void triggerInputs_import_extractsRawImportContext_orNull() {
    Wiring w = new Wiring();
    ObjectNode inputs = JsonNodeFactory.instance.objectNode();
    ObjectNode raw = inputs.putObject("rawImportContext");
    raw.put("url", "https://example.test");
    AdaptationJob withRaw = w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    withRaw.setInputs(inputs);
    AdaptationJob withoutRaw =
        w.job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    withoutRaw.setInputs(null);

    assertThat(w.service.triggerInputsFromJob(withRaw))
        .isEqualTo(new TriggerInputs.ImportTriggerInputs(raw));
    assertThat(w.service.triggerInputsFromJob(withoutRaw))
        .isEqualTo(new TriggerInputs.ImportTriggerInputs(null));
  }

  // ------------------------------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------------------------------

  private static RecipeAdaptationResponse response(
      int index,
      AdaptationClassification classification,
      String confidence,
      String characterScore,
      ObjectNode finalDiff) {
    return new RecipeAdaptationResponse(
        index,
        classification,
        "picked",
        "",
        new BigDecimal(confidence),
        new BigDecimal(characterScore),
        null,
        finalDiff,
        List.of());
  }

  private static AdaptationCandidateDto candidate(int index, ObjectNode diff) {
    return new AdaptationCandidateDto(
        index,
        AdaptationClassification.VERSION,
        diff,
        new AdaptationRollupDto(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            Map.of(),
            BigDecimal.ZERO,
            0,
            0,
            new BigDecimal("0.7"),
            Set.of(),
            List.of()),
        "c",
        "n",
        new BigDecimal("0.9"),
        new BigDecimal("0.9"),
        List.of());
  }

  private static ObjectNode swapDiff(String from, String to) {
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    diff.put("from", from);
    diff.put("to", to);
    return diff;
  }

  private static IngredientDto ingredient(String key) {
    return new IngredientDto(
        UUID.randomUUID(),
        0,
        key,
        "name",
        BigDecimal.ONE,
        "unit",
        null,
        false,
        false,
        BigDecimal.ONE);
  }

  private static RecipeVersionDto versionDto(UUID versionId, UUID branchId) {
    return versionWithIngredients(versionId, branchId, ingredient("beef"));
  }

  private static RecipeVersionDto versionWithIngredients(IngredientDto... ingredients) {
    return versionWithIngredients(UUID.randomUUID(), UUID.randomUUID(), ingredients);
  }

  private static RecipeVersionDto versionWithIngredients(
      UUID versionId, UUID branchId, IngredientDto... ingredients) {
    return new RecipeVersionDto(
        versionId,
        branchId,
        2,
        UUID.randomUUID(),
        VersionTrigger.ADAPTATION_PIPELINE,
        "adapted",
        "pending",
        Instant.now(),
        "system",
        UUID.randomUUID(),
        List.of(ingredients),
        List.of(),
        null,
        null,
        List.of());
  }

  private static AdaptationContext contextWithVersion(RecipeVersionDto version) {
    return new AdaptationContext(
        "IMPORT",
        null,
        version,
        null,
        List.of(),
        null,
        "hc:test",
        null,
        new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()),
        null,
        null,
        null,
        null);
  }

  private static RecipeSubstitutionDto substitutionDto(UUID id) {
    return new RecipeSubstitutionDto(
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new SubstitutedItemDto("beef", BigDecimal.ONE, "unit"),
        new SubstitutedItemDto("chicken", BigDecimal.ONE, "unit"),
        SubstitutionReason.DIETARY_TEMP,
        null,
        List.of(),
        "notes",
        true,
        0,
        null,
        SubstitutionState.ACCEPTED,
        null,
        Instant.now(),
        "system",
        UUID.randomUUID(),
        0L);
  }

  private static RecipeBranchDto branchDto(UUID branchId) {
    return new RecipeBranchDto(
        branchId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "branch",
        "label",
        "reason",
        1,
        BigDecimal.ZERO,
        Instant.now(),
        "system",
        UUID.randomUUID(),
        0L);
  }

  private static final class Wiring {
    final AdaptationJobRepository jobRepository = mock(AdaptationJobRepository.class);
    final CandidateGenerator candidateGenerator = mock(CandidateGenerator.class);
    final ScoringEngine scoringEngine = mock(ScoringEngine.class);
    final AdaptationLlmInvoker llmInvoker = mock(AdaptationLlmInvoker.class);
    final PendingChangeStore pendingChangeStore = mock(PendingChangeStore.class);
    final AdaptationTraceWriter traceWriter = mock(AdaptationTraceWriter.class);
    final LockService lockService = mock(LockService.class);
    final DecisionLogService decisionLogService = mock(DecisionLogService.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final RecipeWriteApi recipeWriteApi = mock(RecipeWriteApi.class);
    final AdaptationContextAssembler contextAssembler = mock(AdaptationContextAssembler.class);
    final HardConstraintFilterService filter = mock(HardConstraintFilterService.class);
    final PlannerHintEmitter plannerHintEmitter = mock(PlannerHintEmitter.class);
    final FingerprintRefresher fingerprintRefresher = mock(FingerprintRefresher.class);
    final RecipeVersionDto currentVersion = versionDto(UUID.randomUUID(), UUID.randomUUID());
    final AdaptationServiceImpl service;

    Wiring() {
      AdaptationConfig config =
          new AdaptationConfig(
              5,
              10_000,
              8_000,
              12_000,
              3,
              3,
              14,
              new BigDecimal("0.50"),
              new BigDecimal("2.00"),
              null,
              30,
              "0 0 4 * * *",
              "0 30 4 * * *");
      this.service =
          new AdaptationServiceImpl(
              jobRepository,
              mock(PendingChangeRepository.class),
              mock(AdaptationTraceRepository.class),
              mock(AdaptationFingerprintRepository.class),
              mock(PlannerHintRecordRepository.class),
              mock(NutritionalKnowledgeRepository.class),
              candidateGenerator,
              scoringEngine,
              llmInvoker,
              new ConfidenceFloorGate(config),
              new CharacterPreservationGate(),
              pendingChangeStore,
              new ChangeDimensionResolver(),
              traceWriter,
              decisionLogService,
              events,
              recipeWriteApi,
              null,
              config,
              contextAssembler,
              plannerHintEmitter,
              fingerprintRefresher,
              null,
              null,
              null,
              new AdaptationLockAcquirer(lockService, jobRepository),
              new RebaseOrchestrator(recipeWriteApi, config),
              filter);
    }

    AdaptationJob job(JobSource source, Catalogue catalogue, ApprovalPolicy policy) {
      return AdaptationJob.builder()
          .id(UUID.randomUUID())
          .recipeId(UUID.randomUUID())
          .userId(UUID.randomUUID())
          .catalogue(catalogue)
          .source(source)
          .priority(JobPriority.SYNC)
          .approvalPolicy(policy)
          .status(JobStatus.RUNNING)
          .inputs(JsonNodeFactory.instance.objectNode())
          .traceId(UUID.randomUUID())
          .enqueuedAt(Instant.now())
          .build();
    }

    AdaptationContext contextWith(String ingredientKey) {
      return contextWithVersion(
          versionWithIngredients(
              currentVersion.id(), currentVersion.branchId(), ingredient(ingredientKey)));
    }

    void stubCommon(AdaptationJob job) {
      when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
      when(lockService.tryAcquire(any(LockKey.class))).thenReturn(true);
      when(contextAssembler.assemble(any(), anyList(), any(TriggerInputs.class)))
          .thenReturn(contextWithVersion(currentVersion));
      when(candidateGenerator.generate(any(), any()))
          .thenReturn(List.of(candidate(0, swapDiff("beef", "chicken"))));
      when(scoringEngine.selectTopN(anyList())).thenAnswer(inv -> inv.getArgument(0));
      when(scoringEngine.shouldAutoSkipStageC(any())).thenReturn(false);
      when(filter.checkRecipe(any(), any(), anyList(), any()))
          .thenReturn(new FilterResult(true, List.of()));
      when(pendingChangeStore.create(any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(UUID.randomUUID());
    }

    void stubLlm(RecipeAdaptationResponse response) {
      when(llmInvoker.invoke(any(), any(AdaptationContext.class))).thenReturn(response);
    }

    void stubHappyLlm(AdaptationClassification classification) {
      stubLlm(response(0, classification, "0.9", "0.9", swapDiff("beef", "chicken")));
    }

    AdaptationJobFailedEvent captureFailedEvent() {
      ArgumentCaptor<AdaptationJobFailedEvent> event =
          ArgumentCaptor.forClass(AdaptationJobFailedEvent.class);
      verify(events).publishEvent(event.capture());
      return event.getValue();
    }
  }
}
