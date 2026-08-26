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
import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.AdaptationRollupDto;
import com.example.mealprep.adaptation.api.dto.AdaptationTraceDto;
import com.example.mealprep.adaptation.api.dto.DataModelChangeType;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeListItemDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.api.mapper.AdaptationJobMapper;
import com.example.mealprep.adaptation.api.mapper.AdaptationTraceMapper;
import com.example.mealprep.adaptation.api.mapper.PendingChangeMapper;
import com.example.mealprep.adaptation.api.mapper.PlannerHintMapper;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.AdaptationTrace;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.entity.PlannerHintRecord;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
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
import com.example.mealprep.adaptation.domain.service.internal.JobReadyEvent;
import com.example.mealprep.adaptation.domain.service.internal.PendingChangeStore;
import com.example.mealprep.adaptation.domain.service.internal.PlannerHintEmitter;
import com.example.mealprep.adaptation.domain.service.internal.RebaseOrchestrator;
import com.example.mealprep.adaptation.domain.service.internal.ScoringEngine;
import com.example.mealprep.adaptation.event.PendingChangeAcceptedEvent;
import com.example.mealprep.adaptation.event.PendingChangeRejectedEvent;
import com.example.mealprep.adaptation.exception.AdaptationJobNotFoundException;
import com.example.mealprep.adaptation.exception.AdaptationJobNotRetryableException;
import com.example.mealprep.adaptation.exception.PendingChangeNotFoundException;
import com.example.mealprep.adaptation.exception.PendingChangeNotPendingException;
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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Pending-change lifecycle, retry, sweep, sync trigger entries, inputs builders and the query
 * fan-out. Companion to {@link AdaptationWorkerPipelineMutantsTest}; both files pin exact values on
 * branches the mutation report showed surviving or uncovered.
 */
class AdaptationServiceLifecycleMutantsTest {

  // ------------------------------------------------------------------------------------------
  // acceptPendingChange / applyAcceptedPendingChange
  // ------------------------------------------------------------------------------------------

  @Test
  void accept_unknownId_throwsPendingChangeNotFound() {
    Wiring w = new Wiring();
    UUID id = UUID.randomUUID();
    when(w.pendingChangeRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                w.service.acceptPendingChange(
                    id, new AcceptPendingChangeRequest(null, 0), UUID.randomUUID()))
        .isInstanceOf(PendingChangeNotFoundException.class)
        .hasMessageContaining(id.toString());
  }

  @Test
  void accept_branchProposal_forksBranch_refreshesFingerprint_invalidatesOldHints() {
    Wiring w = new Wiring();
    UUID actor = UUID.randomUUID();
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    PendingChange pc = pendingChange(actor, AdaptationClassification.BRANCH);
    pc.setProposedDiff(diff);
    when(w.pendingChangeRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
    UUID branchId = UUID.randomUUID();
    when(w.recipeWriteApi.saveAdaptedBranch(any(SaveAdaptedBranchCommand.class)))
        .thenReturn(branchDto(branchId));

    PendingChangeDto dto =
        w.service.acceptPendingChange(pc.getId(), new AcceptPendingChangeRequest(null, 0), actor);

    assertThat(dto.status()).isEqualTo(PendingChangeStatus.ACCEPTED);
    assertThat(dto.acceptedVersionId()).isEqualTo(branchId);
    assertThat(dto.resolvedAt()).isNotNull();
    verify(w.plannerHintEmitter).invalidateHintsForOldVersion(pc.getBaseVersionId());
    verify(w.fingerprintRefresher)
        .refreshOnBranch(
            pc.getRecipeId(), branchId, branchId, diff, diff.toString(), pc.getJobId());
    verify(w.events).publishEvent(any(PendingChangeAcceptedEvent.class));
  }

  @Test
  void accept_branchProposal_withoutBaseVersionOrDiff_skipsBothPostWriteHooks() {
    Wiring w = new Wiring();
    UUID actor = UUID.randomUUID();
    PendingChange pc = pendingChange(actor, AdaptationClassification.BRANCH);
    pc.setBaseVersionId(null);
    pc.setProposedDiff(null);
    when(w.pendingChangeRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
    when(w.recipeWriteApi.saveAdaptedBranch(any(SaveAdaptedBranchCommand.class)))
        .thenReturn(branchDto(UUID.randomUUID()));

    w.service.acceptPendingChange(pc.getId(), new AcceptPendingChangeRequest(null, 0), actor);

    verify(w.plannerHintEmitter, never()).invalidateHintsForOldVersion(any());
    verify(w.fingerprintRefresher, never())
        .refreshOnBranch(any(), any(), any(), any(), any(), any());
  }

  @Test
  void accept_branchConflict_rebasesBranchPointAgainstCurrentHead() {
    Wiring w = new Wiring();
    UUID actor = UUID.randomUUID();
    PendingChange pc = pendingChange(actor, AdaptationClassification.BRANCH);
    when(w.pendingChangeRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
    // The catalogue head moved between proposal and accept; currentHead re-reads it.
    RecipeVersionDto head = versionDto(UUID.randomUUID(), UUID.randomUUID());
    when(w.contextAssembler.assemble(any(), anyList(), any(TriggerInputs.class)))
        .thenReturn(contextWithVersion(head));
    UUID branchId = UUID.randomUUID();
    when(w.recipeWriteApi.saveAdaptedBranch(any(SaveAdaptedBranchCommand.class)))
        .thenThrow(new RecipeVersionConflictException("head moved"))
        .thenReturn(branchDto(branchId));

    PendingChangeDto dto =
        w.service.acceptPendingChange(pc.getId(), new AcceptPendingChangeRequest(null, 0), actor);

    assertThat(dto.acceptedVersionId()).isEqualTo(branchId);
    ArgumentCaptor<SaveAdaptedBranchCommand> cmds =
        ArgumentCaptor.forClass(SaveAdaptedBranchCommand.class);
    verify(w.recipeWriteApi, times(2)).saveAdaptedBranch(cmds.capture());
    SaveAdaptedBranchCommand rebased = cmds.getAllValues().get(1);
    assertThat(rebased.parentBranchId()).isEqualTo(head.branchId());
    assertThat(rebased.branchPointVersionId()).isEqualTo(head.id());
  }

  // ------------------------------------------------------------------------------------------
  // rejectPendingChange
  // ------------------------------------------------------------------------------------------

  @Test
  void reject_unknownId_throwsPendingChangeNotFound() {
    Wiring w = new Wiring();
    UUID id = UUID.randomUUID();
    when(w.pendingChangeRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                w.service.rejectPendingChange(
                    id, new RejectPendingChangeRequest(null), UUID.randomUUID()))
        .isInstanceOf(PendingChangeNotFoundException.class)
        .hasMessageContaining(id.toString());
  }

  @Test
  void reject_pendingRow_marksRejected_publishesEvent_andReturnsDto() {
    Wiring w = new Wiring();
    UUID actor = UUID.randomUUID();
    PendingChange pc = pendingChange(actor, AdaptationClassification.VERSION);
    when(w.pendingChangeRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

    PendingChangeDto dto =
        w.service.rejectPendingChange(pc.getId(), new RejectPendingChangeRequest("nah"), actor);

    assertThat(dto.id()).isEqualTo(pc.getId());
    assertThat(dto.status()).isEqualTo(PendingChangeStatus.REJECTED);
    assertThat(dto.resolvedAt()).isNotNull();
    verify(w.pendingChangeRepository).saveAndFlush(pc);
    verify(w.events).publishEvent(any(PendingChangeRejectedEvent.class));
  }

  @Test
  void reject_alreadyResolvedRow_throwsNotPending() {
    Wiring w = new Wiring();
    UUID actor = UUID.randomUUID();
    PendingChange pc = pendingChange(actor, AdaptationClassification.VERSION);
    pc.setStatus(PendingChangeStatus.REJECTED);
    when(w.pendingChangeRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

    assertThatThrownBy(
            () ->
                w.service.rejectPendingChange(
                    pc.getId(), new RejectPendingChangeRequest(null), actor))
        .isInstanceOf(PendingChangeNotPendingException.class);
  }

  // ------------------------------------------------------------------------------------------
  // emitPlannerHint / sweepExpiredPendingChanges / retryFailedJob
  // ------------------------------------------------------------------------------------------

  @Test
  void emitPlannerHint_returnsMappedRecord() {
    Wiring w = new Wiring();
    UUID hintId = UUID.randomUUID();
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    PlannerHintRecord record =
        PlannerHintRecord.builder()
            .id(hintId)
            .hintType(HintType.PREP_LEAD_TIME)
            .description("marinate overnight")
            .payload(payload)
            .severity(HintSeverity.INFO)
            .build();
    PlannerHintRequest request =
        new PlannerHintRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            HintType.PREP_LEAD_TIME,
            "marinate overnight",
            payload,
            HintSeverity.INFO,
            null,
            UUID.randomUUID());
    when(w.plannerHintEmitter.emit(request, null)).thenReturn(record);

    PlannerHintDto dto = w.service.emitPlannerHint(request, UUID.randomUUID());

    assertThat(dto.id()).isEqualTo(hintId);
    assertThat(dto.description()).isEqualTo("marinate overnight");
  }

  @Test
  void sweep_marksEachExpiredRow_andReturnsTheCount() {
    Wiring w = new Wiring();
    PendingChange a = pendingChange(UUID.randomUUID(), AdaptationClassification.VERSION);
    PendingChange b = pendingChange(UUID.randomUUID(), AdaptationClassification.VERSION);
    when(w.pendingChangeRepository.findExpiredPending(any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of(a, b));

    int swept = w.service.sweepExpiredPendingChanges();

    assertThat(swept).isEqualTo(2);
    assertThat(a.getStatus()).isEqualTo(PendingChangeStatus.EXPIRED);
    assertThat(b.getStatus()).isEqualTo(PendingChangeStatus.EXPIRED);
    assertThat(a.getResolvedAt()).isNotNull();
    assertThat(b.getResolvedAt()).isNotNull();
  }

  @Test
  void retry_unknownJob_throwsNotFound() {
    Wiring w = new Wiring();
    UUID id = UUID.randomUUID();
    when(w.jobRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> w.service.retryFailedJob(id))
        .isInstanceOf(AdaptationJobNotFoundException.class);
  }

  @Test
  void retry_nonFailedJob_throwsNotRetryable() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, JobPriority.ASYNC, JobStatus.DONE);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

    assertThatThrownBy(() -> w.service.retryFailedJob(job.getId()))
        .isInstanceOf(AdaptationJobNotRetryableException.class);
  }

  @Test
  void retry_failedAsyncJob_insertsPendingClone_publishesJobReady_andReturnsIt() {
    Wiring w = new Wiring();
    AdaptationJob old = job(JobSource.IMPORT, JobPriority.ASYNC, JobStatus.FAILED);
    when(w.jobRepository.findById(old.getId())).thenReturn(Optional.of(old));

    AdaptationJobDto dto = w.service.retryFailedJob(old.getId());

    ArgumentCaptor<AdaptationJob> saved = ArgumentCaptor.forClass(AdaptationJob.class);
    verify(w.jobRepository).saveAndFlush(saved.capture());
    AdaptationJob retry = saved.getValue();
    assertThat(retry.getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(retry.getParentDecisionId()).isEqualTo(old.getId());
    assertThat(dto.id()).isEqualTo(retry.getId());
    ArgumentCaptor<JobReadyEvent> event = ArgumentCaptor.forClass(JobReadyEvent.class);
    verify(w.events).publishEvent(event.capture());
    assertThat(event.getValue().jobId()).isEqualTo(retry.getId());
  }

  @Test
  void retry_failedBatchJob_neverPublishesJobReady() {
    Wiring w = new Wiring();
    AdaptationJob old = job(JobSource.DATA_MODEL_CHANGE, JobPriority.BATCH, JobStatus.FAILED);
    when(w.jobRepository.findById(old.getId())).thenReturn(Optional.of(old));

    AdaptationJobDto dto = w.service.retryFailedJob(old.getId());

    assertThat(dto).isNotNull();
    verify(w.events, never()).publishEvent(any(JobReadyEvent.class));
  }

  // ------------------------------------------------------------------------------------------
  // Job-row inserts + inputs JSONB builders
  // ------------------------------------------------------------------------------------------

  @Test
  void feedbackRow_persistsTextAndRatingDelta_inInputs() {
    Wiring w = new Wiring();
    FeedbackJobRequest request =
        new FeedbackJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "too salty",
            new FeedbackJobRequest.RatingDeltaDto(new BigDecimal("-0.8"), null, null, null),
            UUID.randomUUID(),
            null);

    UUID jobId = w.service.enqueueFeedbackJobRow(request);

    ArgumentCaptor<AdaptationJob> saved = ArgumentCaptor.forClass(AdaptationJob.class);
    verify(w.jobRepository).saveAndFlush(saved.capture());
    AdaptationJob row = saved.getValue();
    assertThat(jobId).isEqualTo(row.getId());
    assertThat(row.getSource()).isEqualTo(JobSource.FEEDBACK);
    assertThat(row.getPriority()).isEqualTo(JobPriority.SYNC);
    assertThat(row.getApprovalPolicy()).isEqualTo(ApprovalPolicy.PENDING_CHANGE);
    assertThat(row.getInputs().get("feedbackText").asText()).isEqualTo("too salty");
    assertThat(row.getInputs().get("ratingDelta").get("taste").decimalValue())
        .isEqualByComparingTo("-0.8");
  }

  @Test
  void feedbackRow_withNoTextOrDelta_persistsEmptyInputsObject() {
    Wiring w = new Wiring();
    FeedbackJobRequest request =
        new FeedbackJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            UUID.randomUUID(),
            null);

    w.service.enqueueFeedbackJobRow(request);

    ArgumentCaptor<AdaptationJob> saved = ArgumentCaptor.forClass(AdaptationJob.class);
    verify(w.jobRepository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getInputs().isObject()).isTrue();
    assertThat(saved.getValue().getInputs().isEmpty()).isTrue();
  }

  @Test
  void planTimeRow_persistsDirectiveAndConstraints_inInputs() {
    Wiring w = new Wiring();
    PlanTimeRefineDirectiveRequest request = planTimeRequest();

    UUID jobId = w.service.enqueuePlanTimeJobRow(request);

    ArgumentCaptor<AdaptationJob> saved = ArgumentCaptor.forClass(AdaptationJob.class);
    verify(w.jobRepository).saveAndFlush(saved.capture());
    AdaptationJob row = saved.getValue();
    assertThat(jobId).isEqualTo(row.getId());
    assertThat(row.getSource()).isEqualTo(JobSource.PLAN_TIME);
    assertThat(row.getApprovalPolicy()).isEqualTo(ApprovalPolicy.PLAN_OVERLAY);
    assertThat(row.getInputs().get("directive").get("description").asText()).isEqualTo("drop 2");
  }

  @Test
  void planTimeRow_withNullDirectiveAndConstraints_persistsEmptyInputsObject() {
    Wiring w = new Wiring();
    PlanTimeRefineDirectiveRequest request =
        new PlanTimeRefineDirectiveRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            UUID.randomUUID(),
            UUID.randomUUID());

    w.service.enqueuePlanTimeJobRow(request);

    ArgumentCaptor<AdaptationJob> saved = ArgumentCaptor.forClass(AdaptationJob.class);
    verify(w.jobRepository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getInputs().isObject()).isTrue();
    assertThat(saved.getValue().getInputs().isEmpty()).isTrue();
  }

  @Test
  void dataModelJobs_persistChangeTypeAndSummary_inInputs() {
    Wiring w = new Wiring();
    ObjectNode summary = JsonNodeFactory.instance.objectNode();
    summary.put("surface", "targets");
    UUID recipeId = UUID.randomUUID();
    DataModelJobRequest request =
        new DataModelJobRequest(
            UUID.randomUUID(),
            DataModelChangeType.NUTRITION_TARGETS,
            summary,
            Set.of(recipeId),
            UUID.randomUUID());

    List<UUID> ids = w.service.enqueueDataModelChangeJobs(request);

    assertThat(ids).hasSize(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AdaptationJob>> saved = ArgumentCaptor.forClass(List.class);
    verify(w.jobRepository).saveAll(saved.capture());
    AdaptationJob row = saved.getValue().get(0);
    assertThat(row.getInputs().get("changeType").asText()).isEqualTo("NUTRITION_TARGETS");
    assertThat(row.getInputs().get("changeSummary")).isEqualTo(summary);
  }

  @Test
  void dataModelJobs_withNullTypeAndSummary_persistEmptyInputsObject() {
    Wiring w = new Wiring();
    DataModelJobRequest request =
        new DataModelJobRequest(
            UUID.randomUUID(), null, null, Set.of(UUID.randomUUID()), UUID.randomUUID());

    w.service.enqueueDataModelChangeJobs(request);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AdaptationJob>> saved = ArgumentCaptor.forClass(List.class);
    verify(w.jobRepository).saveAll(saved.capture());
    assertThat(saved.getValue().get(0).getInputs().isObject()).isTrue();
    assertThat(saved.getValue().get(0).getInputs().isEmpty()).isTrue();
  }

  // ------------------------------------------------------------------------------------------
  // Sync trigger entries (through the self proxy) + processSyncJob
  // ------------------------------------------------------------------------------------------

  @Test
  void enqueueFeedbackJob_runsThePipeline_andReturnsAResult() {
    Wiring w = new Wiring();
    w.injectSelfProxy();
    w.stubRowRoundTrip();
    w.stubHappyPipeline();
    when(w.traceRepository.findByJobId(any())).thenReturn(Optional.empty());
    FeedbackJobRequest request =
        new FeedbackJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "too salty",
            null,
            UUID.randomUUID(),
            null);

    AdaptationResultDto result = w.service.enqueueFeedbackJob(request);

    assertThat(result).isNotNull();
    assertThat(result.recipeId()).isEqualTo(request.recipeId());
    verify(w.llmInvoker).invoke(any(), any(AdaptationContext.class));
  }

  @Test
  void runPlanTimeRefineJob_runsThePipeline_andReturnsAResult() {
    Wiring w = new Wiring();
    w.injectSelfProxy();
    w.stubRowRoundTrip();
    w.stubHappyPlanOverlayPipeline();
    when(w.traceRepository.findByJobId(any())).thenReturn(Optional.empty());

    AdaptationResultDto result = w.service.runPlanTimeRefineJob(planTimeRequest());

    assertThat(result).isNotNull();
    verify(w.recipeWriteApi).saveAdaptedSubstitution(any(SaveAdaptedSubstitutionCommand.class));
  }

  @Test
  void processSyncJob_planTime_hardConstraintInfeasibility_mapsToNoChangeResult() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.PLAN_TIME, JobPriority.SYNC, JobStatus.RUNNING);
    job.setApprovalPolicy(ApprovalPolicy.PLAN_OVERLAY);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
    w.stubPipelineWhereFinalDiffViolatesHardConstraints();

    AdaptationResultDto result = w.service.processSyncJob(job);

    assertThat(result.classification()).isEqualTo(AdaptationClassification.NO_CHANGE);
    assertThat(result.jobId()).isEqualTo(job.getId());
  }

  @Test
  void processSyncJob_feedback_hardConstraintInfeasibility_rethrows() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.FEEDBACK, JobPriority.SYNC, JobStatus.RUNNING);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
    w.stubPipelineWhereFinalDiffViolatesHardConstraints();

    assertThatThrownBy(() -> w.service.processSyncJob(job))
        .isInstanceOf(
            com.example.mealprep.adaptation.exception.AdaptationHardConstraintViolationException
                .class);
  }

  @Test
  void processSyncJob_happyPath_runsWorker_andReturnsTraceBackedResult() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.FEEDBACK, JobPriority.SYNC, JobStatus.RUNNING);
    when(w.jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
    w.stubHappyPipeline();
    UUID pendingId = UUID.randomUUID();
    AdaptationTrace trace =
        AdaptationTrace.builder()
            .jobId(job.getId())
            .outcomeKind(OutcomeKind.PENDING_CREATED)
            .outcomeTargetId(pendingId)
            .classificationDecision(AdaptationClassification.VERSION)
            .confidence(new BigDecimal("0.9"))
            .build();
    when(w.traceRepository.findByJobId(job.getId())).thenReturn(Optional.of(trace));

    AdaptationResultDto result = w.service.processSyncJob(job);

    verify(w.llmInvoker).invoke(any(), any(AdaptationContext.class));
    assertThat(result.pendingChangeIdCreated()).contains(pendingId);
  }

  // ------------------------------------------------------------------------------------------
  // loadResultFromTrace
  // ------------------------------------------------------------------------------------------

  @Test
  void loadResultFromTrace_mapsClassification_diff_confidence_andVersionOutcome() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, JobPriority.ASYNC, JobStatus.DONE);
    UUID versionId = UUID.randomUUID();
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    AdaptationTrace trace =
        AdaptationTrace.builder()
            .jobId(job.getId())
            .outcomeKind(OutcomeKind.VERSION_CREATED)
            .outcomeTargetId(versionId)
            .classificationDecision(AdaptationClassification.VERSION)
            .finalDiff(diff)
            .confidence(new BigDecimal("0.42"))
            .build();
    when(w.traceRepository.findByJobId(job.getId())).thenReturn(Optional.of(trace));

    AdaptationResultDto result = w.service.loadResultFromTrace(job);

    assertThat(result.classification()).isEqualTo(AdaptationClassification.VERSION);
    assertThat(result.versionIdCreated()).contains(versionId);
    assertThat(result.proposedDiff()).isEqualTo(diff);
    assertThat(result.confidence()).isEqualByComparingTo("0.42");
    assertThat(result.requiresApproval()).isFalse();
  }

  @Test
  void loadResultFromTrace_nullTraceFields_fallBackToNoChangeEmptyDiffZeroConfidence() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, JobPriority.ASYNC, JobStatus.DONE);
    AdaptationTrace trace =
        AdaptationTrace.builder().jobId(job.getId()).outcomeKind(OutcomeKind.NO_OP).build();
    when(w.traceRepository.findByJobId(job.getId())).thenReturn(Optional.of(trace));

    AdaptationResultDto result = w.service.loadResultFromTrace(job);

    assertThat(result.classification()).isEqualTo(AdaptationClassification.NO_CHANGE);
    assertThat(result.proposedDiff().isObject()).isTrue();
    assertThat(result.proposedDiff().isEmpty()).isTrue();
    assertThat(result.confidence()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // ------------------------------------------------------------------------------------------
  // Query fan-out
  // ------------------------------------------------------------------------------------------

  @Test
  void listPendingForUser_usesConfiguredBudgetAsPageSize_andMapsRows() {
    Wiring w = new Wiring(5);
    UUID userId = UUID.randomUUID();
    PendingChange pc = pendingChange(userId, AdaptationClassification.VERSION);
    when(w.pendingChangeRepository.findRankedPending(eq(userId), any(Pageable.class)))
        .thenReturn(List.of(pc));

    List<PendingChangeListItemDto> items = w.service.listPendingForUser(userId);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).id()).isEqualTo(pc.getId());
    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(w.pendingChangeRepository).findRankedPending(eq(userId), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(5);
  }

  @Test
  void listPendingForUser_zeroOrMissingBudget_fallsBackToThree() {
    Wiring zeroBudget = new Wiring(0);
    UUID userId = UUID.randomUUID();
    when(zeroBudget.pendingChangeRepository.findRankedPending(eq(userId), any(Pageable.class)))
        .thenReturn(List.of());
    zeroBudget.service.listPendingForUser(userId);
    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(zeroBudget.pendingChangeRepository).findRankedPending(eq(userId), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(3);
  }

  @Test
  void listPendingHistoryForRecipe_returnsMappedRows_newestFirstPageOf200() {
    Wiring w = new Wiring();
    UUID recipeId = UUID.randomUUID();
    PendingChange pc = pendingChange(UUID.randomUUID(), AdaptationClassification.VERSION);
    when(w.pendingChangeRepository.findByRecipeIdOrderByCreatedAtDesc(
            eq(recipeId), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(pc)));

    List<PendingChangeListItemDto> items = w.service.listPendingHistoryForRecipe(recipeId);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).id()).isEqualTo(pc.getId());
    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(w.pendingChangeRepository)
        .findByRecipeIdOrderByCreatedAtDesc(eq(recipeId), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(200);
  }

  @Test
  void listPendingHistoryForRecipe_paged_mapsThePage() {
    Wiring w = new Wiring();
    UUID recipeId = UUID.randomUUID();
    PendingChange pc = pendingChange(UUID.randomUUID(), AdaptationClassification.VERSION);
    Pageable page = PageRequest.of(0, 10);
    when(w.pendingChangeRepository.findByRecipeIdOrderByCreatedAtDesc(recipeId, page))
        .thenReturn(new PageImpl<>(List.of(pc)));

    Page<PendingChangeListItemDto> result = w.service.listPendingHistoryForRecipe(recipeId, page);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).id()).isEqualTo(pc.getId());
  }

  @Test
  void getPendingChange_present_returnsMappedDto() {
    Wiring w = new Wiring();
    PendingChange pc = pendingChange(UUID.randomUUID(), AdaptationClassification.VERSION);
    when(w.pendingChangeRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

    Optional<PendingChangeDto> dto = w.service.getPendingChange(pc.getId());

    assertThat(dto).isPresent();
    assertThat(dto.get().id()).isEqualTo(pc.getId());
  }

  @Test
  void getTracesForRecipe_returnsMappedPage() {
    Wiring w = new Wiring();
    UUID recipeId = UUID.randomUUID();
    AdaptationTrace trace =
        AdaptationTrace.builder()
            .id(UUID.randomUUID())
            .recipeId(recipeId)
            .outcomeKind(OutcomeKind.NO_OP)
            .build();
    when(w.traceRepository.findByRecipeIdOrderByCreatedAtDesc(eq(recipeId), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(trace)));

    Page<AdaptationTraceDto> result = w.service.getTracesForRecipe(recipeId, PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).id()).isEqualTo(trace.getId());
  }

  @Test
  void getTraceForJob_present_returnsMappedDto() {
    Wiring w = new Wiring();
    UUID jobId = UUID.randomUUID();
    AdaptationTrace trace =
        AdaptationTrace.builder()
            .id(UUID.randomUUID())
            .jobId(jobId)
            .outcomeKind(OutcomeKind.NO_OP)
            .build();
    when(w.traceRepository.findByJobId(jobId)).thenReturn(Optional.of(trace));

    Optional<AdaptationTraceDto> dto = w.service.getTraceForJob(jobId);

    assertThat(dto).isPresent();
    assertThat(dto.get().id()).isEqualTo(trace.getId());
  }

  // ------------------------------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------------------------------

  private static PendingChange pendingChange(UUID userId, AdaptationClassification classification) {
    return PendingChange.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(userId)
        .jobId(UUID.randomUUID())
        .traceId(UUID.randomUUID())
        .changeDimension(ChangeDimension.PROTEIN)
        .proposedClassification(classification)
        .baseVersionId(UUID.randomUUID())
        .baseBranchId(UUID.randomUUID())
        .reasoning("r")
        .status(PendingChangeStatus.PENDING)
        .createdAt(Instant.now())
        .build();
  }

  private static AdaptationJob job(JobSource source, JobPriority priority, JobStatus status) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .source(source)
        .priority(priority)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(status)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
  }

  private static PlanTimeRefineDirectiveRequest planTimeRequest() {
    return new PlanTimeRefineDirectiveRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new PlanTimeRefineDirectiveRequest.RefineDirectiveDto(null, "drop 2", null),
        null,
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private static RecipeVersionDto versionDto(UUID versionId, UUID branchId) {
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
        List.of(ingredient("beef")),
        List.of(),
        null,
        null,
        List.of());
  }

  private static IngredientDto ingredient(String key) {
    return new IngredientDto(
        UUID.randomUUID(), 0, key, key, BigDecimal.ONE, "unit", null, false, false, BigDecimal.ONE);
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

  private static final class Wiring {
    final AdaptationJobRepository jobRepository = mock(AdaptationJobRepository.class);
    final PendingChangeRepository pendingChangeRepository = mock(PendingChangeRepository.class);
    final AdaptationTraceRepository traceRepository = mock(AdaptationTraceRepository.class);
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
    final AdaptationServiceImpl service;

    Wiring() {
      this(3);
    }

    Wiring(int pendingChangeBudgetPerWeek) {
      AdaptationConfig config =
          new AdaptationConfig(
              5,
              10_000,
              8_000,
              12_000,
              3,
              pendingChangeBudgetPerWeek,
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
              pendingChangeRepository,
              traceRepository,
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
              new PendingChangeMapper() {},
              config,
              contextAssembler,
              plannerHintEmitter,
              fingerprintRefresher,
              new AdaptationJobMapper() {},
              new AdaptationTraceMapper() {},
              new PlannerHintMapper() {},
              new AdaptationLockAcquirer(lockService, jobRepository),
              new RebaseOrchestrator(recipeWriteApi, config),
              filter);
    }

    /** The sync trigger entries call themselves through the Spring self proxy; here it is us. */
    void injectSelfProxy() {
      try {
        var field = AdaptationServiceImpl.class.getDeclaredField("self");
        field.setAccessible(true);
        field.set(service, service);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    /** saveAndFlush hands the row back and findById finds it, like the real repository would. */
    void stubRowRoundTrip() {
      AtomicReference<AdaptationJob> lastSaved = new AtomicReference<>();
      when(jobRepository.saveAndFlush(any(AdaptationJob.class)))
          .thenAnswer(
              inv -> {
                lastSaved.set(inv.getArgument(0));
                return inv.getArgument(0);
              });
      when(jobRepository.findById(any(UUID.class)))
          .thenAnswer(
              inv -> {
                AdaptationJob j = lastSaved.get();
                return j != null && j.getId().equals(inv.getArgument(0))
                    ? Optional.of(j)
                    : Optional.empty();
              });
    }

    void stubHappyPipeline() {
      when(lockService.tryAcquire(any(LockKey.class))).thenReturn(true);
      when(filter.checkRecipe(any(), any(), anyList(), any()))
          .thenReturn(new FilterResult(true, List.of()));
      when(contextAssembler.assemble(any(), anyList(), any(TriggerInputs.class)))
          .thenReturn(contextWithVersion(versionDto(UUID.randomUUID(), UUID.randomUUID())));
      ObjectNode diff = swapDiff("beef", "chicken");
      when(candidateGenerator.generate(any(), any())).thenReturn(List.of(candidate(0, diff)));
      when(scoringEngine.selectTopN(anyList())).thenAnswer(inv -> inv.getArgument(0));
      when(scoringEngine.shouldAutoSkipStageC(any())).thenReturn(false);
      when(llmInvoker.invoke(any(), any(AdaptationContext.class)))
          .thenReturn(
              new RecipeAdaptationResponse(
                  0,
                  AdaptationClassification.VERSION,
                  "picked",
                  "",
                  new BigDecimal("0.9"),
                  new BigDecimal("0.9"),
                  null,
                  diff,
                  List.of()));
      when(pendingChangeStore.create(any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(UUID.randomUUID());
    }

    void stubHappyPlanOverlayPipeline() {
      stubHappyPipeline();
      when(llmInvoker.invoke(any(), any(AdaptationContext.class)))
          .thenReturn(
              new RecipeAdaptationResponse(
                  0,
                  AdaptationClassification.SUBSTITUTION,
                  "picked",
                  "",
                  new BigDecimal("0.9"),
                  new BigDecimal("0.9"),
                  null,
                  swapDiff("beef", "chicken"),
                  List.of()));
      when(recipeWriteApi.saveAdaptedSubstitution(any(SaveAdaptedSubstitutionCommand.class)))
          .thenReturn(substitutionDto(UUID.randomUUID()));
    }

    /** Step 3 lets the shortlist through; the Step 6 re-check rejects the final diff. */
    void stubPipelineWhereFinalDiffViolatesHardConstraints() {
      stubHappyPipeline();
      ObjectNode unsafe = swapDiff("beef", "tofu");
      when(llmInvoker.invoke(any(), any(AdaptationContext.class)))
          .thenReturn(
              new RecipeAdaptationResponse(
                  0,
                  AdaptationClassification.VERSION,
                  "picked",
                  "",
                  new BigDecimal("0.9"),
                  new BigDecimal("0.9"),
                  null,
                  unsafe,
                  List.of()));
      when(filter.checkRecipe(any(), any(), anyList(), any()))
          .thenAnswer(
              inv -> {
                List<String> keys = inv.getArgument(2);
                return keys.contains("tofu")
                    ? new FilterResult(false, List.of())
                    : new FilterResult(true, List.of());
              });
    }
  }
}
