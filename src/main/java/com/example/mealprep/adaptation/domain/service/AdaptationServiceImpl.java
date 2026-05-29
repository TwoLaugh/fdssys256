package com.example.mealprep.adaptation.domain.service;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.AdaptationContextAssembler;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.TriggerInputs;
import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.AdaptationTraceDto;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
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
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
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
import com.example.mealprep.adaptation.domain.service.internal.JobReadyEvent;
import com.example.mealprep.adaptation.domain.service.internal.PendingChangeStore;
import com.example.mealprep.adaptation.domain.service.internal.PlannerHintEmitter;
import com.example.mealprep.adaptation.domain.service.internal.RebaseOrchestrator;
import com.example.mealprep.adaptation.domain.service.internal.ScoringEngine;
import com.example.mealprep.adaptation.event.AdaptationCandidateProducedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobCompletedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobFailedEvent;
import com.example.mealprep.adaptation.event.PendingChangeAcceptedEvent;
import com.example.mealprep.adaptation.event.PendingChangeRejectedEvent;
import com.example.mealprep.adaptation.exception.AdaptationAiResponseInvalidException;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.adaptation.exception.AdaptationCharacterBreakException;
import com.example.mealprep.adaptation.exception.AdaptationException;
import com.example.mealprep.adaptation.exception.AdaptationHardConstraintViolationException;
import com.example.mealprep.adaptation.exception.AdaptationJobNotFoundException;
import com.example.mealprep.adaptation.exception.AdaptationJobNotRetryableException;
import com.example.mealprep.adaptation.exception.PendingChangeExpiredException;
import com.example.mealprep.adaptation.exception.PendingChangeNotFoundException;
import com.example.mealprep.adaptation.exception.PendingChangeNotPendingException;
import com.example.mealprep.adaptation.exception.PendingChangeSupersededException;
import com.example.mealprep.adaptation.exception.RebaseExhaustedException;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.SubstitutionItemRequest;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedBranchCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedSubstitutionCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of both {@link AdaptationService} and {@link AdaptationQueryService}.
 *
 * <p>01c fills in {@link #processJob(AdaptationJob)} (private worker entry point) and {@link
 * #transitionJobStatus(UUID, JobStatus, JobFailureReason, String)} (status writer with {@code
 * noRollbackFor = AdaptationException.class}). The four public trigger methods stay UOE — 01d wires
 * them, calling {@link #processJob} after enqueuing.
 *
 * <p>Per ticket 01c §Worker entry point and §Hard constraints — every step in the pipeline opens
 * its own short transaction; the worker itself is NOT {@code @Transactional}.
 */
@Service
public class AdaptationServiceImpl implements AdaptationService, AdaptationQueryService {

  private static final Logger LOG = LoggerFactory.getLogger(AdaptationServiceImpl.class);

  // Pure JSON tree-building for the inputs JSONB serialise/parse round-trip (adaptation source-bias
  // payload). No Spring config dependency — a vanilla mapper is sufficient for record <-> tree.
  private static final ObjectMapper INPUTS_MAPPER = new ObjectMapper();

  private static final String UOE_TICKET_01C = "ticket-01c";
  private static final String UOE_TICKET_01D = "ticket-01d";
  private static final String UOE_TICKET_01E = "ticket-01e";
  private static final String UOE_TICKET_01F = "ticket-01f";

  private final AdaptationJobRepository jobRepository;

  @SuppressWarnings("unused")
  private final PendingChangeRepository pendingChangeRepository;

  @SuppressWarnings("unused")
  private final AdaptationTraceRepository traceRepository;

  @SuppressWarnings("unused")
  private final AdaptationFingerprintRepository fingerprintRepository;

  @SuppressWarnings("unused")
  private final PlannerHintRecordRepository plannerHintRepository;

  @SuppressWarnings("unused")
  private final NutritionalKnowledgeRepository nutritionalKnowledgeRepository;

  // 01c helper components — injected lazily so 01b skeleton tests can still construct the bean
  // with only the original repository args via a separate constructor.
  private final CandidateGenerator candidateGenerator;
  private final ScoringEngine scoringEngine;
  private final AdaptationLlmInvoker llmInvoker;
  private final ConfidenceFloorGate confidenceFloorGate;
  private final CharacterPreservationGate characterPreservationGate;
  private final PendingChangeStore pendingChangeStore;
  private final ChangeDimensionResolver dimensionResolver;
  private final AdaptationTraceWriter traceWriter;

  private final DecisionLogService decisionLogService;
  private final ApplicationEventPublisher events;

  // 01d helpers — pending-change lifecycle + read fan-out. Nullable in skeleton ctor.
  private final RecipeWriteApi recipeWriteApi;
  private final PendingChangeMapper pendingChangeMapper;
  private final AdaptationConfig adaptationConfig;

  // Apply-path + safety helpers. DIRECT (SYSTEM-catalogue) writes route THROUGH the orchestrator
  // for
  // RecipeVersionConflict rebase-retry / REBASE_EXHAUSTED handling (never raw RecipeWriteApi); the
  // hard-constraint filter is the deterministic allergy/dietary safety net invoked at Step 3 (drop
  // infeasible candidates before scoring) and Step 6 (re-check the final chosen diff). Both are
  // nullable in the older skeleton ctors — those wirings never reach the apply / filter paths
  // (PENDING_CHANGE-only smoke wirings, or candidate-set fixtures that exercise scoring alone).
  private final RebaseOrchestrator rebaseOrchestrator;
  private final HardConstraintFilterService hardConstraintFilterService;

  // 01e — real context loader. Nullable in older ctors; processJob falls back to the 01c
  // placeholder loader when absent (keeps the 01b/01c skeleton + smoke tests green).
  private final AdaptationContextAssembler contextAssembler;

  // 01f — planner-hint emit + fingerprint refresh + read-fan-out mappers. Nullable in the older
  // skeleton ctors (the 12 query/emit bodies guard or are only reachable via the full wiring).
  private final PlannerHintEmitter plannerHintEmitter;
  private final FingerprintRefresher fingerprintRefresher;
  private final AdaptationJobMapper jobMapper;
  private final AdaptationTraceMapper traceMapper;
  private final PlannerHintMapper plannerHintMapper;

  // Step-1 advisory-lock acquire lives on a dedicated bean so the call from processJob()
  // (intentionally non-transactional) crosses a bean boundary, the proxy fires, and the
  // @Transactional(REQUIRED) advice on acquireLockOrFailJob applies by construction. Nullable
  // in the older skeleton ctors — those wirings never exercise the lock-acquire path.
  private final AdaptationLockAcquirer lockAcquirer;

  // Self-proxy so the two sync trigger entries can invoke their job-row inserts THROUGH the Spring
  // proxy (a plain this.* call bypasses the proxy, so the @Transactional advice never fires). @Lazy
  // breaks the self-referential injection cycle. Mirrors the pre-extraction AdaptationLockAcquirer
  // workaround. NOT constructor-injected: that would be a hard cycle even with @Lazy on the field.
  @org.springframework.beans.factory.annotation.Autowired
  @org.springframework.context.annotation.Lazy
  private AdaptationServiceImpl self;

  /** Skeleton constructor — retained for backwards-compatibility with 01b unit tests. */
  public AdaptationServiceImpl(
      AdaptationJobRepository jobRepository,
      PendingChangeRepository pendingChangeRepository,
      AdaptationTraceRepository traceRepository,
      AdaptationFingerprintRepository fingerprintRepository,
      PlannerHintRecordRepository plannerHintRepository,
      NutritionalKnowledgeRepository nutritionalKnowledgeRepository) {
    this(
        jobRepository,
        pendingChangeRepository,
        traceRepository,
        fingerprintRepository,
        plannerHintRepository,
        nutritionalKnowledgeRepository,
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
        null,
        null);
  }

  /**
   * 01c full constructor — Spring binds against this when all helper beans are present. Any null
   * helper falls back to a UOE-style failure when the worker tries to use it.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  @org.springframework.beans.factory.annotation.Autowired
  public AdaptationServiceImpl(
      AdaptationJobRepository jobRepository,
      PendingChangeRepository pendingChangeRepository,
      AdaptationTraceRepository traceRepository,
      AdaptationFingerprintRepository fingerprintRepository,
      PlannerHintRecordRepository plannerHintRepository,
      NutritionalKnowledgeRepository nutritionalKnowledgeRepository,
      CandidateGenerator candidateGenerator,
      ScoringEngine scoringEngine,
      AdaptationLlmInvoker llmInvoker,
      ConfidenceFloorGate confidenceFloorGate,
      CharacterPreservationGate characterPreservationGate,
      PendingChangeStore pendingChangeStore,
      ChangeDimensionResolver dimensionResolver,
      AdaptationTraceWriter traceWriter,
      DecisionLogService decisionLogService,
      ApplicationEventPublisher events,
      RecipeWriteApi recipeWriteApi,
      PendingChangeMapper pendingChangeMapper,
      AdaptationConfig adaptationConfig,
      AdaptationContextAssembler contextAssembler,
      PlannerHintEmitter plannerHintEmitter,
      FingerprintRefresher fingerprintRefresher,
      AdaptationJobMapper jobMapper,
      AdaptationTraceMapper traceMapper,
      PlannerHintMapper plannerHintMapper,
      AdaptationLockAcquirer lockAcquirer,
      RebaseOrchestrator rebaseOrchestrator,
      HardConstraintFilterService hardConstraintFilterService) {
    this.jobRepository = jobRepository;
    this.pendingChangeRepository = pendingChangeRepository;
    this.traceRepository = traceRepository;
    this.fingerprintRepository = fingerprintRepository;
    this.plannerHintRepository = plannerHintRepository;
    this.nutritionalKnowledgeRepository = nutritionalKnowledgeRepository;
    this.candidateGenerator = candidateGenerator;
    this.scoringEngine = scoringEngine;
    this.llmInvoker = llmInvoker;
    this.confidenceFloorGate = confidenceFloorGate;
    this.characterPreservationGate = characterPreservationGate;
    this.pendingChangeStore = pendingChangeStore;
    this.dimensionResolver = dimensionResolver;
    this.traceWriter = traceWriter;
    this.decisionLogService = decisionLogService;
    this.events = events;
    this.recipeWriteApi = recipeWriteApi;
    this.pendingChangeMapper = pendingChangeMapper;
    this.adaptationConfig = adaptationConfig;
    this.contextAssembler = contextAssembler;
    this.plannerHintEmitter = plannerHintEmitter;
    this.fingerprintRefresher = fingerprintRefresher;
    this.jobMapper = jobMapper;
    this.traceMapper = traceMapper;
    this.plannerHintMapper = plannerHintMapper;
    this.lockAcquirer = lockAcquirer;
    this.rebaseOrchestrator = rebaseOrchestrator;
    this.hardConstraintFilterService = hardConstraintFilterService;
  }

  // ---------------------------------------------------------------------------
  // AdaptationService — write surface (public trigger entries stay UOE in 01c)
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // 01d — Trigger entry methods (Trigger 1/2/3/4)
  // ---------------------------------------------------------------------------

  @Override
  @Transactional
  public UUID enqueueImportJob(ImportJobRequest request) {
    return enqueueImportJob(request, JobPriority.ASYNC);
  }

  @Override
  @Transactional
  public UUID enqueueImportJob(ImportJobRequest request, JobPriority priority) {
    ApprovalPolicy approval =
        request.catalogue() == Catalogue.USER
            ? ApprovalPolicy.PENDING_CHANGE
            : ApprovalPolicy.DIRECT;
    UUID jobId = UUID.randomUUID();
    UUID traceId = request.parentTraceId() == null ? UUID.randomUUID() : request.parentTraceId();
    AdaptationJob job =
        AdaptationJob.builder()
            .id(jobId)
            .recipeId(request.recipeId())
            .userId(request.userId())
            .catalogue(request.catalogue())
            .source(JobSource.IMPORT)
            .priority(priority)
            .approvalPolicy(approval)
            .status(JobStatus.PENDING)
            .inputs(importInputs(request))
            .traceId(traceId)
            .enqueuedAt(Instant.now())
            .build();
    jobRepository.saveAndFlush(job);
    // ASYNC: publish INSIDE the tx — TransactionalEventListener(AFTER_COMMIT) is robust to that —
    // so the worker picks the row up immediately. BATCH jobs are picked up by BatchJobOrchestrator
    // on its cron, so no JobReadyEvent is published (mirrors enqueueDataModelChangeJobs).
    if (priority == JobPriority.ASYNC) {
      events.publishEvent(new JobReadyEvent(jobId));
    }
    return jobId;
  }

  @Override
  public AdaptationResultDto enqueueFeedbackJob(FeedbackJobRequest request) {
    UUID jobId = self.enqueueFeedbackJobRow(request);
    AdaptationJob job = jobRepository.findById(jobId).orElseThrow();
    return processSyncJob(job);
  }

  /**
   * Inserts the FEEDBACK job row in its own tx so the row is visible before the synchronous worker
   * pipeline (which writes FK-referencing children: {@code adaptation_traces} + {@code
   * adaptation_pending_changes}, both {@code job_id -> adaptation_jobs(id)}). Invoked via the
   * {@link #self} proxy and annotated {@code REQUIRES_NEW} so the insert genuinely commits in its
   * own transaction — a plain self-call (or {@code REQUIRED}, which would merely join the caller's
   * active dispatch tx) would leave the row uncommitted and invisible to the worker's sub-tx
   * writes, causing intermittent {@code ..._job_id_fkey} violations. The async IMPORT /
   * DATA_MODEL_CHANGE paths don't need this — their job row commits via the listener / orchestrator
   * tx before the worker runs. Split per ticket §Trigger-2 step 8.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID enqueueFeedbackJobRow(FeedbackJobRequest request) {
    UUID jobId = UUID.randomUUID();
    AdaptationJob job =
        AdaptationJob.builder()
            .id(jobId)
            .recipeId(request.recipeId())
            .userId(request.userId())
            .catalogue(Catalogue.USER)
            .source(JobSource.FEEDBACK)
            .priority(JobPriority.SYNC)
            .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
            .status(JobStatus.PENDING)
            .inputs(feedbackInputs(request))
            .traceId(request.traceId())
            .parentDecisionId(request.parentDecisionId())
            .enqueuedAt(Instant.now())
            .build();
    jobRepository.saveAndFlush(job);
    return jobId;
  }

  @Override
  @Transactional
  public List<UUID> enqueueDataModelChangeJobs(DataModelJobRequest request) {
    Instant now = Instant.now();
    List<UUID> ids = new java.util.ArrayList<>(request.affectedRecipeIds().size());
    List<AdaptationJob> jobs = new java.util.ArrayList<>(request.affectedRecipeIds().size());
    for (UUID recipeId : request.affectedRecipeIds()) {
      UUID jobId = UUID.randomUUID();
      jobs.add(
          AdaptationJob.builder()
              .id(jobId)
              .recipeId(recipeId)
              .userId(request.userId())
              .catalogue(Catalogue.USER)
              .source(JobSource.DATA_MODEL_CHANGE)
              .priority(JobPriority.BATCH)
              .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
              .status(JobStatus.PENDING)
              .inputs(dataModelInputs(request))
              .traceId(request.traceId())
              .enqueuedAt(now)
              .build());
      ids.add(jobId);
    }
    jobRepository.saveAll(jobs);
    // BATCH jobs are picked up by BatchJobOrchestrator on its cron — no JobReadyEvent.
    return ids;
  }

  @Override
  public AdaptationResultDto runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest request) {
    UUID jobId = self.enqueuePlanTimeJobRow(request);
    AdaptationJob job = jobRepository.findById(jobId).orElseThrow();
    return processSyncJob(job);
  }

  /**
   * Inserts the PLAN_TIME job row in its own tx so the row is visible before the synchronous worker
   * pipeline (which writes FK-referencing children: {@code adaptation_traces} + {@code
   * adaptation_pending_changes}, both {@code job_id -> adaptation_jobs(id)}). Invoked via the
   * {@link #self} proxy and annotated {@code REQUIRES_NEW} so the insert genuinely commits in its
   * own transaction — a plain self-call (or {@code REQUIRED}, which would merely join the caller's
   * active dispatch tx) would leave the row uncommitted and invisible to the worker's sub-tx
   * writes, causing intermittent {@code ..._job_id_fkey} violations. The async IMPORT /
   * DATA_MODEL_CHANGE paths don't need this — their job row commits via the listener / orchestrator
   * tx before the worker runs.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID enqueuePlanTimeJobRow(PlanTimeRefineDirectiveRequest request) {
    UUID jobId = UUID.randomUUID();
    AdaptationJob job =
        AdaptationJob.builder()
            .id(jobId)
            .recipeId(request.recipeId())
            .userId(request.userId())
            .catalogue(Catalogue.USER)
            .source(JobSource.PLAN_TIME)
            .priority(JobPriority.SYNC)
            .approvalPolicy(ApprovalPolicy.PLAN_OVERLAY)
            .status(JobStatus.PENDING)
            .inputs(planTimeInputs(request))
            .traceId(request.traceId())
            .parentDecisionId(request.parentDecisionId())
            .enqueuedAt(Instant.now())
            .build();
    jobRepository.saveAndFlush(job);
    return jobId;
  }

  // ---------------------------------------------------------------------------
  // 01d — Pending-change lifecycle
  // ---------------------------------------------------------------------------

  @Override
  @Transactional(
      noRollbackFor = {
        PendingChangeExpiredException.class,
        PendingChangeNotPendingException.class,
        PendingChangeSupersededException.class
      })
  public PendingChangeDto acceptPendingChange(
      UUID pendingChangeId, AcceptPendingChangeRequest request, UUID actorUserId) {
    PendingChange pc =
        pendingChangeRepository
            .findById(pendingChangeId)
            .orElseThrow(
                () ->
                    new PendingChangeNotFoundException(
                        "pending change not found: " + pendingChangeId));
    // Don't leak other users' rows: 404 rather than 403.
    if (!pc.getUserId().equals(actorUserId)) {
      throw new PendingChangeNotFoundException("pending change not found: " + pendingChangeId);
    }
    Instant now = Instant.now();
    if (pc.getStatus() != PendingChangeStatus.PENDING) {
      // resolved_at left as-is if previously resolved; never re-stamp.
      throw new PendingChangeNotPendingException(
          "pending change is " + pc.getStatus() + ": " + pendingChangeId);
    }
    if (pc.getExpiresAt() != null && pc.getExpiresAt().isBefore(now)) {
      pc.setStatus(PendingChangeStatus.EXPIRED);
      pc.setResolvedAt(now);
      pendingChangeRepository.saveAndFlush(pc);
      throw new PendingChangeExpiredException("pending change expired: " + pendingChangeId);
    }
    if (pc.getSupersededBy() != null) {
      throw new PendingChangeSupersededException(
          "pending change superseded by " + pc.getSupersededBy());
    }
    if (request.expectedOptimisticVersion() != pc.getOptimisticVersion()) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          PendingChange.class, pendingChangeId);
    }

    // Apply via RecipeWriteApi.saveAdaptedVersion — null/zero-fill the heavy fields (01e refines).
    SaveAdaptedVersionCommand cmd =
        new SaveAdaptedVersionCommand(
            pc.getRecipeId(),
            pc.getBaseBranchId(),
            0,
            pc.getBaseVersionId(),
            java.util.List.of(),
            java.util.List.of(),
            null,
            null,
            (CharacterFingerprintDto) null,
            pc.getProposedDiff(),
            pc.getReasoning(),
            pc.getTraceId());
    RecipeVersionDto resultVersion = recipeWriteApi.saveAdaptedVersion(cmd);

    boolean wasModified = request.userEdits() != null;
    pc.setStatus(wasModified ? PendingChangeStatus.MODIFIED : PendingChangeStatus.ACCEPTED);
    pc.setAcceptedVersionId(resultVersion.id());
    pc.setUserEdits(request.userEdits());
    pc.setResolvedAt(now);
    pendingChangeRepository.saveAndFlush(pc);

    events.publishEvent(
        new PendingChangeAcceptedEvent(
            pc.getId(),
            pc.getRecipeId(),
            pc.getUserId(),
            resultVersion.id(),
            wasModified,
            pc.getTraceId(),
            now));
    return pendingChangeMapper.toDto(pc);
  }

  @Override
  @Transactional(noRollbackFor = PendingChangeNotPendingException.class)
  public PendingChangeDto rejectPendingChange(
      UUID pendingChangeId, RejectPendingChangeRequest request, UUID actorUserId) {
    PendingChange pc =
        pendingChangeRepository
            .findById(pendingChangeId)
            .orElseThrow(
                () ->
                    new PendingChangeNotFoundException(
                        "pending change not found: " + pendingChangeId));
    if (!pc.getUserId().equals(actorUserId)) {
      throw new PendingChangeNotFoundException("pending change not found: " + pendingChangeId);
    }
    if (pc.getStatus() != PendingChangeStatus.PENDING) {
      throw new PendingChangeNotPendingException(
          "pending change is " + pc.getStatus() + ": " + pendingChangeId);
    }
    Instant now = Instant.now();
    pc.setStatus(PendingChangeStatus.REJECTED);
    pc.setResolvedAt(now);
    pendingChangeRepository.saveAndFlush(pc);
    events.publishEvent(
        new PendingChangeRejectedEvent(
            pc.getId(), pc.getRecipeId(), pc.getUserId(), pc.getTraceId(), now));
    return pendingChangeMapper.toDto(pc);
  }

  /**
   * Persist a planner-noticed hint. {@code @Transactional(REQUIRED)} — the {@link
   * PlannerHintEmitter} insert + auto-invalidate + event publish all run in one tx so {@code
   * PlannerHintEmittedEvent} (an {@code AFTER_COMMIT} listener target) is published inside an
   * active tx. {@code emittedByJobId} is taken from the request (null for planner-noticed hints).
   */
  @Override
  @Transactional
  public PlannerHintDto emitPlannerHint(PlannerHintRequest request, UUID actorUserId) {
    var record = plannerHintEmitter.emit(request, request.emittedByJobId());
    return plannerHintMapper.toDto(record);
  }

  @Override
  @Transactional
  public int sweepExpiredPendingChanges() {
    List<PendingChange> expired =
        pendingChangeRepository.findExpiredPending(Instant.now(), PageRequest.of(0, 500));
    Instant now = Instant.now();
    for (PendingChange pc : expired) {
      pc.setStatus(PendingChangeStatus.EXPIRED);
      pc.setResolvedAt(now);
    }
    // Hibernate flushes dirty managed entities on tx commit; no explicit saveAll needed.
    // Per-run cap of 500 keeps the tx short — the next cron tick picks up any remainder.
    return expired.size();
  }

  @Override
  @Transactional
  public AdaptationJobDto retryFailedJob(UUID jobId) {
    AdaptationJob old =
        jobRepository
            .findById(jobId)
            .orElseThrow(
                () -> new AdaptationJobNotFoundException("adaptation job not found: " + jobId));
    if (old.getStatus() != JobStatus.FAILED) {
      throw new AdaptationJobNotRetryableException(
          "job is " + old.getStatus() + ", only FAILED jobs may be retried: " + jobId);
    }
    UUID newId = UUID.randomUUID();
    AdaptationJob retry =
        AdaptationJob.builder()
            .id(newId)
            .recipeId(old.getRecipeId())
            .userId(old.getUserId())
            .catalogue(old.getCatalogue())
            .source(old.getSource())
            .priority(old.getPriority())
            .approvalPolicy(old.getApprovalPolicy())
            .status(JobStatus.PENDING)
            .inputs(old.getInputs())
            .traceId(UUID.randomUUID())
            .parentDecisionId(old.getId())
            .enqueuedAt(Instant.now())
            .build();
    jobRepository.saveAndFlush(retry);
    // BATCH-priority jobs are picked up by BatchJobOrchestrator's cron — no JobReadyEvent.
    if (old.getPriority() != JobPriority.BATCH) {
      events.publishEvent(new JobReadyEvent(newId));
    }
    return jobMapper.toDto(retry);
  }

  // ---------------------------------------------------------------------------
  // 01c — private worker pipeline (processJob + transitionJobStatus)
  // ---------------------------------------------------------------------------

  /**
   * 10-step worker pipeline per LLD §Shared worker pipeline (lines 738-770). Not transactional —
   * each DB-touching step opens its own short tx. Callable from 01d's trigger entry methods.
   *
   * <p>Visibility is public so 01d's internal listeners (in {@code internal.*} sub-package) can
   * call it, AND so unit tests can exercise it directly without touching the public surface.
   */
  public void processJob(AdaptationJob job) {
    LOG.info(
        "processJob start jobId={} recipeId={} source={}",
        job.getId(),
        job.getRecipeId(),
        job.getSource());
    long startMillis = System.currentTimeMillis();
    try {
      // Step 1 — Acquire advisory lock via the dedicated bean. The cross-bean call hits the
      // Spring proxy, so @Transactional(REQUIRED) on AdaptationLockAcquirer#acquireLockOrFailJob
      // fires by construction — processJob itself stays non-transactional.
      lockAcquirer.acquireLockOrFailJob(job);

      // Step 2 — Load context via the 01e assembler (falls back to the 01c placeholder when the
      // assembler bean is absent, e.g. older skeleton-constructor unit wirings).
      AdaptationContext context = loadContext(job);

      // Step 3 — Stage A candidate generation, then the hard-constraint safety net BEFORE scoring.
      // The deterministic allergy/dietary filter is invariant per HLD §Guardrails — it is never
      // bypassed; the LLM only ever sees a pre-vetted shortlist. Candidates whose resulting
      // ingredient set would violate the owner's hard constraints are dropped here so the LLM
      // cannot
      // pick one. Empty-after-filter (no feasible candidate, e.g. every swap reintroduces an
      // allergen) fails the job HARD_FILTER per LLD Step 3 / JobFailureReason.HARD_FILTER.
      List<AdaptationCandidateDto> generated = candidateGenerator.generate(job, context);
      List<AdaptationCandidateDto> candidates = filterFeasibleCandidates(job, context, generated);
      if (candidates.isEmpty()) {
        String excerpt =
            generated.isEmpty()
                ? "no-candidates"
                : "hard-filter: all " + generated.size() + " candidates infeasible";
        handleFailure(job, JobFailureReason.HARD_FILTER, excerpt, startMillis);
        return;
      }

      // Step 4 — Stage B scoring + top-N.
      List<AdaptationCandidateDto> topN = scoringEngine.selectTopN(candidates);
      AdaptationContext withCandidates = context.withCandidates(topN);
      publishCandidateProduced(job, topN);

      // Step 5 — Stage C dispatch (with auto-skip).
      RecipeAdaptationResponse response;
      boolean autoSkipped = scoringEngine.shouldAutoSkipStageC(topN);
      if (autoSkipped) {
        response = synthesiseAutoSkipResponse(topN);
        LOG.info("auto-skip Stage C for jobId={} (top score 2x rule)", job.getId());
      } else {
        try {
          response = llmInvoker.invoke(job, withCandidates);
        } catch (AdaptationAiUnavailableException e) {
          // Deferrable AI failure (upstream-down / cost-cap) — graceful-degrade reason.
          handleFailure(job, JobFailureReason.AI_UNAVAILABLE, e.getMessage(), startMillis);
          throw e;
        } catch (AdaptationAiResponseInvalidException e) {
          // Terminal AI failure (malformed/unparseable output or 4xx caller-bug). Previously these
          // bare ai.exception.* escaped processJob's adaptation-only catches and left the job stuck
          // RUNNING forever; route to a terminal LLM_ERROR so the job reaches FAILED + one
          // AdaptationJobFailedEvent.
          handleFailure(job, JobFailureReason.LLM_ERROR, e.getMessage(), startMillis);
          throw e;
        }
      }

      // Step 6 — Validation gates (in order, all must pass).
      ValidationResult confidenceResult = confidenceFloorGate.evaluate(response);
      boolean forceBranch;
      try {
        forceBranch = characterPreservationGate.evaluateAndForceBranch(response);
      } catch (AdaptationCharacterBreakException e) {
        handleFailure(job, JobFailureReason.CHARACTER_BREAK, e.getMessage(), startMillis);
        throw e;
      }
      // Step 6 (final gate) — re-run the hard-constraint filter against the FINAL chosen diff.
      // Guards against the LLM stitching together a candidate post-hoc (refinedDiff / a free-form
      // finalDiff) that reintroduces an allergen the Step-3 shortlist had excluded. Never bypassed.
      // Skipped only when the LLM declined entirely (NO_CHANGE) — there is no diff to apply.
      AdaptationClassification finalClassification =
          forceBranch ? AdaptationClassification.BRANCH : response.classification();
      boolean noChange =
          response.classification() == AdaptationClassification.NO_CHANGE
              || response.chosenCandidateIndex() < 0;
      if (!noChange) {
        try {
          recheckFinalDiff(job, context, withCandidates, response);
        } catch (AdaptationHardConstraintViolationException e) {
          handleFailure(job, JobFailureReason.HARD_FILTER, e.getMessage(), startMillis);
          throw e;
        }
      }

      // Step 7 — Apply per approval_policy (LLD §Shared worker pipeline step 7). A LOW_CONFIDENCE
      // result is defensively downgraded to PENDING_CHANGE for user review even on a SYSTEM
      // catalogue (HLD failure mode).
      ApprovalPolicy effective =
          (confidenceResult == ValidationResult.LOW_CONFIDENCE)
              ? ApprovalPolicy.PENDING_CHANGE
              : job.getApprovalPolicy();
      OutcomeKind outcome;
      UUID outcomeTargetId = null;
      if (effective == ApprovalPolicy.PENDING_CHANGE) {
        // PENDING_CHANGE (USER catalogue / LOW_CONFIDENCE downgrade) is a propose/approve outcome:
        // store the pending change for the user to review REGARDLESS of the NO_CHANGE
        // classification
        // (LLD §Shared worker pipeline step 7 — the PENDING_CHANGE branch is not classification-
        // gated; it never auto-applies). A NO_CHANGE response simply yields an empty-diff pending
        // record (PendingChangeStore folds finalDiffJson==null to an empty node). The NO_CHANGE →
        // NO_OP short-circuit applies ONLY to the apply policies below, where there is genuinely no
        // diff to write. This is the propose/approve invariant: a FEEDBACK-triggered adaptation of
        // a
        // USER's own recipe must reach AWAITING_USER_APPROVAL, never auto-APPLIED.
        ChangeDimension dim = dimensionResolver.resolve(job, withCandidates, response);
        UUID pcId =
            pendingChangeStore.create(
                job,
                response,
                dim,
                context.currentVersion() == null
                    ? job.getRecipeId()
                    : context.currentVersion().id(),
                context.currentVersion() == null
                    ? job.getRecipeId()
                    : context.currentVersion().branchId(),
                job.getPromptTemplateVersion() == null ? "v0" : job.getPromptTemplateVersion());
        outcome = OutcomeKind.PENDING_CREATED;
        outcomeTargetId = pcId;
      } else if (noChange) {
        // Apply policies (DIRECT / PLAN_OVERLAY) only: the LLM declined to recommend any candidate
        // (infeasibility / low coherence) — there is no diff to apply, so record NO_OP and write
        // nothing. (The PENDING_CHANGE branch above is intentionally NOT gated on noChange.)
        outcome = OutcomeKind.NO_OP;
      } else if (effective == ApprovalPolicy.PLAN_OVERLAY) {
        // Trigger 4 (plan-time refine): outcome is ALWAYS a substitution overlay — plan-scoped, the
        // master recipe never mutates (LLD §Decisions §9). No RebaseOrchestrator: a substitution is
        // an additive overlay, not a head-moving version write, so it can't lose a version race.
        UUID subId = applyPlanOverlay(job, context, withCandidates, response);
        outcome = OutcomeKind.SUBSTITUTION_CREATED;
        outcomeTargetId = subId;
      } else {
        // DIRECT (SYSTEM catalogue): write a new VERSION or BRANCH straight through, routed via the
        // RebaseOrchestrator so a RecipeVersionConflictException retries up to maxRebaseAttempts
        // and
        // then fails REBASE_EXHAUSTED (LLD §Shared worker pipeline step 7 / §Concurrency).
        ApplyOutcome applied = applyDirect(job, context, response, finalClassification);
        outcome = applied.outcome();
        outcomeTargetId = applied.targetId();
      }

      // Step 7b (01f) — post-apply hooks. On a new-version / branch write the prior version's
      // planner hints are stale (body changed) → invalidate them; on a BRANCH the AI response
      // carries the fingerprint inline (LLD §Decisions §3) → refresh it. Guarded on the helpers
      // being wired (skeleton-ctor unit tests pass them null) and a known current version.
      applyPostWriteHooks(job, response, finalClassification, context, outcome, outcomeTargetId);

      // Step 8 — Trace + decision log.
      int durationMs = (int) (System.currentTimeMillis() - startMillis);
      traceWriter.write(
          new AdaptationTraceWriter.TraceData(
              job.getId(),
              job.getRecipeId(),
              job.getTraceId(),
              job.getSource(),
              "adaptation/recipe-adaptation",
              job.getPromptTemplateVersion() == null ? "v0" : job.getPromptTemplateVersion(),
              null,
              JsonNodeFactory.instance.objectNode(),
              autoSkipped ? null : JsonNodeFactory.instance.objectNode(),
              JsonNodeFactory.instance.arrayNode(),
              autoSkipped ? null : response.chosenCandidateIndex(),
              finalClassification,
              response.finalDiffJson(),
              response.confidence(),
              response.characterPreservationScore(),
              autoSkipped ? ValidationResult.PASSED : confidenceResult,
              outcome,
              outcomeTargetId,
              durationMs));

      try {
        decisionLogService.write(
            new DecisionLogWriteRequest(
                job.getTraceId(),
                job.getParentDecisionId(),
                "recipe",
                job.getRecipeId(),
                DecisionLogScale.RECIPE,
                mapSource(job.getSource()),
                job.getUserId(),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.arrayNode(),
                JsonNodeFactory.instance.objectNode(),
                autoSkipped ? "auto-skip: top score 2x" : response.reasoning(),
                null,
                0,
                durationMs));
      } catch (RuntimeException e) {
        LOG.warn("DecisionLogService.write failed (non-fatal): {}", e.getMessage());
      }

      // Step 10 — Status DONE + completion event.
      transitionJobStatus(job.getId(), JobStatus.DONE, null, null);
      events.publishEvent(
          new AdaptationJobCompletedEvent(
              job.getId(),
              job.getRecipeId(),
              outcome,
              outcomeTargetId,
              finalClassification,
              response.confidence() == null ? BigDecimal.ZERO : response.confidence(),
              job.getTraceId(),
              Instant.now()));
    } catch (RebaseExhaustedException e) {
      handleFailure(job, JobFailureReason.REBASE_EXHAUSTED, e.getMessage(), startMillis);
      throw e;
    } catch (AdaptationException e) {
      // Already-handled adaptation-specific failures rethrow for sync callers (01d wires).
      throw e;
    }
  }

  /**
   * Status writer with {@code noRollbackFor = AdaptationException.class}: the audit-status write
   * commits even when the caller is about to throw a domain exception that will map to a 4xx / 5xx
   * response. Per ticket 01c §Step transitionJobStatus + agent-prompt-template gotcha 256.
   */
  @Transactional(propagation = Propagation.REQUIRED, noRollbackFor = AdaptationException.class)
  public void transitionJobStatus(
      UUID jobId, JobStatus newStatus, JobFailureReason reason, String excerpt) {
    AdaptationJob job = jobRepository.findById(jobId).orElse(null);
    if (job == null) {
      LOG.warn("transitionJobStatus: job not found id={}", jobId);
      return;
    }
    job.setStatus(newStatus);
    if (reason != null) {
      job.setFailureReason(reason);
    }
    if (excerpt != null) {
      job.setFailureExcerpt(excerpt.length() > 512 ? excerpt.substring(0, 512) : excerpt);
    }
    if (newStatus == JobStatus.DONE || newStatus == JobStatus.FAILED) {
      job.setCompletedAt(Instant.now());
    }
    jobRepository.saveAndFlush(job);
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  /**
   * 01e context load. Delegates to {@link AdaptationContextAssembler} (real peer-module reads) when
   * the bean is wired; falls back to {@link #loadContextPlaceholder} for the older skeleton-ctor
   * unit wirings that pass a null assembler. The trigger-specific payload is parsed from {@code
   * job.getInputs()} per source.
   */
  AdaptationContext loadContext(AdaptationJob job) {
    if (contextAssembler == null) {
      return loadContextPlaceholder(job);
    }
    return contextAssembler.assemble(job, List.of(), triggerInputsFromJob(job));
  }

  /**
   * Parse the source-specific {@link TriggerInputs} from the job's {@code inputs} JSONB. The
   * enqueue methods now persist the full trigger payload (ratingDelta, directive, feedbackText,
   * dataModelChange), so this parse hydrates the live source-bias inputs that {@code
   * CandidateGenerator} reads — they are no longer dead at runtime. Each branch is null-tolerant:
   * an absent field yields {@code null} and the assembler folds it cleanly into the context.
   */
  TriggerInputs triggerInputsFromJob(AdaptationJob job) {
    JsonNode inputs = job.getInputs();
    return switch (job.getSource()) {
      case FEEDBACK -> {
        String text =
            inputs != null && inputs.hasNonNull("feedbackText")
                ? inputs.get("feedbackText").asText()
                : null;
        FeedbackJobRequest.RatingDeltaDto ratingDelta =
            parseNode(
                inputs == null ? null : inputs.get("ratingDelta"),
                FeedbackJobRequest.RatingDeltaDto.class);
        yield new TriggerInputs.FeedbackTriggerInputs(text, ratingDelta);
      }
      case PLAN_TIME -> {
        PlanTimeRefineDirectiveRequest.RefineDirectiveDto directive =
            parseNode(
                inputs == null ? null : inputs.get("directive"),
                PlanTimeRefineDirectiveRequest.RefineDirectiveDto.class);
        yield new TriggerInputs.PlanTimeTriggerInputs(directive, null);
      }
      case DATA_MODEL_CHANGE -> {
        JsonNode summary = inputs == null ? null : inputs.get("changeSummary");
        yield new TriggerInputs.DataModelTriggerInputs(null, summary == null ? inputs : summary);
      }
      case IMPORT -> {
        JsonNode raw = inputs == null ? null : inputs.get("rawImportContext");
        yield new TriggerInputs.ImportTriggerInputs(raw);
      }
    };
  }

  /** Null-tolerant tree → record parse; returns {@code null} on missing/blank/malformed nodes. */
  private static <T> T parseNode(JsonNode node, Class<T> type) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      return INPUTS_MAPPER.treeToValue(node, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      LOG.warn(
          "trigger-inputs parse of {} failed (non-fatal): {}",
          type.getSimpleName(),
          e.getMessage());
      return null;
    }
  }

  // --- inputs JSONB builders (adaptation source-bias payload persisted per trigger)
  // ---------------

  /** IMPORT inputs: raw scrape/import context (may be null for a manual create). */
  private static JsonNode importInputs(ImportJobRequest request) {
    var node = JsonNodeFactory.instance.objectNode();
    if (request.rawImportContext() != null) {
      node.set("rawImportContext", request.rawImportContext());
    }
    return node;
  }

  /** FEEDBACK inputs: verbatim text + structured rating delta (biases CandidateGenerator). */
  private static JsonNode feedbackInputs(FeedbackJobRequest request) {
    var node = JsonNodeFactory.instance.objectNode();
    if (request.feedbackText() != null) {
      node.put("feedbackText", request.feedbackText());
    }
    if (request.ratingDelta() != null) {
      node.set("ratingDelta", INPUTS_MAPPER.valueToTree(request.ratingDelta()));
    }
    return node;
  }

  /** DATA_MODEL_CHANGE inputs: which surface changed + the JSON summary. */
  private static JsonNode dataModelInputs(DataModelJobRequest request) {
    var node = JsonNodeFactory.instance.objectNode();
    if (request.changeType() != null) {
      node.put("changeType", request.changeType().name());
    }
    if (request.changeSummary() != null) {
      node.set("changeSummary", request.changeSummary());
    }
    return node;
  }

  /** PLAN_TIME inputs: the refine directive (DirectiveKind biases Stage A) + plan constraints. */
  private static JsonNode planTimeInputs(PlanTimeRefineDirectiveRequest request) {
    var node = JsonNodeFactory.instance.objectNode();
    if (request.directive() != null) {
      node.set("directive", INPUTS_MAPPER.valueToTree(request.directive()));
    }
    if (request.constraints() != null) {
      node.set("constraints", INPUTS_MAPPER.valueToTree(request.constraints()));
    }
    return node;
  }

  /**
   * Placeholder loader. 01e ships the real {@code AdaptationContextAssembler} that calls the peer
   * QueryServices. 01c only needs enough context to drive Stage A — the strategies tolerate
   * null/empty fields. Retained as the fallback for the older skeleton-ctor unit wirings.
   */
  AdaptationContext loadContextPlaceholder(AdaptationJob job) {
    return new AdaptationContext(
        job.getSource().name(),
        null,
        null,
        null,
        List.of(),
        null,
        "v0",
        null,
        new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()),
        null,
        null,
        null,
        null);
  }

  /**
   * 01f post-apply hooks (LLD line 769). Only fires when the version write actually moved the body:
   * a {@code PENDING_CREATED} outcome did NOT write a new version (it awaits user accept) so hints
   * stay valid; a direct {@code VERSION_CREATED}/{@code BRANCH_CREATED} (01e apply paths) DID.
   * Guarded null-safe so the skeleton-ctor unit wirings (helpers null) are unaffected.
   */
  private void applyPostWriteHooks(
      AdaptationJob job,
      RecipeAdaptationResponse response,
      AdaptationClassification finalClassification,
      AdaptationContext context,
      OutcomeKind outcome,
      UUID outcomeTargetId) {
    if (outcome != OutcomeKind.VERSION_CREATED && outcome != OutcomeKind.BRANCH_CREATED) {
      return; // no body-moving write happened (PENDING / NO_OP / FAILED) — hints stay valid.
    }
    RecipeVersionDto current = context == null ? null : context.currentVersion();
    if (plannerHintEmitter != null && current != null) {
      plannerHintEmitter.invalidateHintsForOldVersion(current.id());
    }
    if (fingerprintRefresher != null
        && finalClassification == AdaptationClassification.BRANCH
        && response.finalDiffJson() != null
        && outcomeTargetId != null
        && current != null) {
      // BRANCH outcome: outcomeTargetId is the new branch's v1 versionId; the fingerprint rides
      // inline on the AI response (no second AI call per LLD §Decisions §3).
      fingerprintRefresher.refreshOnBranch(
          job.getRecipeId(),
          outcomeTargetId,
          outcomeTargetId,
          response.finalDiffJson(),
          response.finalDiffJson().toString(),
          job.getId());
    }
  }

  // ---------------------------------------------------------------------------
  // Hard-constraint safety net (Step 3 pre-scoring filter + Step 6 final-diff re-check)
  // ---------------------------------------------------------------------------

  /**
   * Step 3 safety net — drop every candidate whose RESULTING ingredient set would violate the
   * owner's hard constraints, BEFORE scoring, so the LLM only ever picks from a pre-vetted
   * shortlist (HLD §Guardrails: the deterministic filter is invariant; the LLM never touches hard
   * constraints). Each candidate's resulting key set is the current version's keys with the diff's
   * removals/additions applied; {@link HardConstraintFilterService#checkRecipe} then validates it.
   *
   * <p>Null-safe fallback: when the filter is not wired (older skeleton-ctor unit wirings) the
   * generated list passes through unchanged — those wirings never exercise the safety path.
   */
  List<AdaptationCandidateDto> filterFeasibleCandidates(
      AdaptationJob job, AdaptationContext context, List<AdaptationCandidateDto> generated) {
    if (hardConstraintFilterService == null || generated.isEmpty()) {
      return generated;
    }
    List<String> baseKeys = currentVersionKeys(context);
    List<AdaptationCandidateDto> feasible = new ArrayList<>(generated.size());
    for (AdaptationCandidateDto c : generated) {
      List<String> resultingKeys = resultingIngredientKeys(baseKeys, c.proposedDiff());
      FilterResult result =
          hardConstraintFilterService.checkRecipe(
              job.getUserId(), job.getRecipeId(), resultingKeys);
      if (result.passes()) {
        feasible.add(c);
      } else {
        LOG.info(
            "Step-3 hard-filter dropped candidate index={} jobId={} (violations={})",
            c.index(),
            job.getId(),
            result.violations() == null ? 0 : result.violations().size());
      }
    }
    return feasible;
  }

  /**
   * Step 6 final gate — re-run the hard-constraint filter against the diff actually about to be
   * applied (the LLM's {@code finalDiffJson}/{@code refinedDiff} if present, else the chosen
   * candidate's pre-vetted diff). Catches the LLM stitching together a post-hoc diff that
   * reintroduces an allergen the Step-3 shortlist excluded. Never bypassed.
   *
   * @throws AdaptationHardConstraintViolationException when the final diff violates a hard
   *     constraint
   */
  void recheckFinalDiff(
      AdaptationJob job,
      AdaptationContext context,
      AdaptationContext withCandidates,
      RecipeAdaptationResponse response) {
    if (hardConstraintFilterService == null) {
      return;
    }
    JsonNode finalDiff = resolveFinalDiff(withCandidates, response);
    List<String> resultingKeys = resultingIngredientKeys(currentVersionKeys(context), finalDiff);
    FilterResult result =
        hardConstraintFilterService.checkRecipe(job.getUserId(), job.getRecipeId(), resultingKeys);
    if (!result.passes()) {
      int violationCount = result.violations() == null ? 0 : result.violations().size();
      throw new AdaptationHardConstraintViolationException(
          "final-diff re-check failed: "
              + violationCount
              + " hard-constraint violation(s) on recipe "
              + job.getRecipeId());
    }
  }

  /**
   * The diff that will actually be applied: the LLM's free-form {@code finalDiffJson} when present,
   * else the chosen candidate's pre-vetted {@code proposedDiff}. Falls back to an empty node when
   * neither is resolvable (defensive — the resulting key set then equals the base keys).
   */
  private JsonNode resolveFinalDiff(
      AdaptationContext withCandidates, RecipeAdaptationResponse response) {
    if (response.finalDiffJson() != null) {
      return response.finalDiffJson();
    }
    int idx = response.chosenCandidateIndex();
    List<AdaptationCandidateDto> candidates =
        withCandidates == null ? List.of() : withCandidates.candidates();
    for (AdaptationCandidateDto c : candidates) {
      if (c.index() == idx) {
        return c.proposedDiff();
      }
    }
    return JsonNodeFactory.instance.objectNode();
  }

  /** Distinct ingredient mapping keys on the recipe's current version (empty when no body). */
  private List<String> currentVersionKeys(AdaptationContext context) {
    RecipeVersionDto version = context == null ? null : context.currentVersion();
    if (version == null || version.ingredients() == null) {
      return List.of();
    }
    return version.ingredients().stream()
        .map(IngredientDto::ingredientMappingKey)
        .filter(k -> k != null && !k.isBlank())
        .distinct()
        .toList();
  }

  /**
   * Compute the ingredient-mapping-key set that RESULTS from applying {@code diff} to the recipe's
   * current keys. Mirrors the deterministic v1 candidate-diff shapes emitted by the Stage-A
   * strategies:
   *
   * <ul>
   *   <li>{@code ingredient-swap {from,to}} — remove {@code from}, add {@code to} (the swap-in is
   *       the key that could reintroduce an allergen).
   *   <li>{@code ingredient-remove {key}} — remove {@code key}.
   *   <li>{@code portion-adjust} / {@code method-*} — no ingredient-set change.
   * </ul>
   *
   * <p>Also tolerates an {@code ingredientChanges[]} array (the {@link
   * com.example.mealprep.recipe.api.dto.RecipeDiffDto} shape an LLM {@code finalDiffJson} may
   * carry): any node with a {@code to.ingredientMappingKey} adds that key. Conservative by
   * construction — an unrecognised diff leaves the base set intact, so the filter still checks the
   * existing recipe.
   */
  List<String> resultingIngredientKeys(List<String> baseKeys, JsonNode diff) {
    Set<String> keys = new LinkedHashSet<>(baseKeys);
    if (diff == null || diff.isNull()) {
      return List.copyOf(keys);
    }
    String kind = diff.hasNonNull("kind") ? diff.get("kind").asText() : null;
    if ("ingredient-swap".equals(kind)) {
      if (diff.hasNonNull("from")) {
        keys.remove(diff.get("from").asText());
      }
      if (diff.hasNonNull("to")) {
        keys.add(diff.get("to").asText());
      }
    } else if ("ingredient-remove".equals(kind)) {
      if (diff.hasNonNull("key")) {
        keys.remove(diff.get("key").asText());
      }
    }
    // Free-form LLM diff carrying ingredientChanges[] — fold in any introduced keys defensively so
    // a post-hoc-stitched substitution still gets re-checked.
    JsonNode changes = diff.get("ingredientChanges");
    if (changes != null && changes.isArray()) {
      for (JsonNode ch : changes) {
        JsonNode to = ch.get("to");
        if (to != null && to.hasNonNull("ingredientMappingKey")) {
          keys.add(to.get("ingredientMappingKey").asText());
        }
        JsonNode from = ch.get("from");
        if (from != null
            && from.hasNonNull("ingredientMappingKey")
            && ch.hasNonNull("action")
            && "REMOVED".equalsIgnoreCase(ch.get("action").asText())) {
          keys.remove(from.get("ingredientMappingKey").asText());
        }
      }
    }
    return List.copyOf(keys);
  }

  // ---------------------------------------------------------------------------
  // Apply paths (Step 7): DIRECT (version/branch via RebaseOrchestrator) + PLAN_OVERLAY
  // (substitution)
  // ---------------------------------------------------------------------------

  /** Outcome of a DIRECT apply: which kind was written and the new target id. */
  private record ApplyOutcome(OutcomeKind outcome, UUID targetId) {}

  /**
   * DIRECT apply (SYSTEM catalogue) — write a new VERSION on the current branch, or a new BRANCH
   * when the gates forced a branch (character-preservation BRANCH candidate). Routed through {@link
   * RebaseOrchestrator} so a {@code RecipeVersionConflictException} retries up to {@code
   * maxRebaseAttempts} and then surfaces {@code RebaseExhaustedException} → REBASE_EXHAUSTED.
   */
  ApplyOutcome applyDirect(
      AdaptationJob job,
      AdaptationContext context,
      RecipeAdaptationResponse response,
      AdaptationClassification finalClassification) {
    RecipeVersionDto current = context == null ? null : context.currentVersion();
    JsonNode finalDiff =
        response.finalDiffJson() == null
            ? JsonNodeFactory.instance.objectNode()
            : response.finalDiffJson();
    UUID traceId = job.getTraceId();

    if (finalClassification == AdaptationClassification.BRANCH) {
      SaveAdaptedBranchCommand branchCmd =
          new SaveAdaptedBranchCommand(
              job.getRecipeId(),
              current == null ? null : current.branchId(),
              current == null ? null : current.id(),
              "adaptation-" + traceId,
              "adapted",
              response.reasoning(),
              List.<CreateIngredientRequest>of(),
              List.<CreateMethodStepRequest>of(),
              null,
              null,
              (CharacterFingerprintDto) null,
              traceId);
      RecipeBranchDto branch =
          rebaseOrchestrator.saveAdaptedBranchWithRebase(
              branchCmd, c -> rebaseBranchCommand(c, job));
      return new ApplyOutcome(OutcomeKind.BRANCH_CREATED, branch.id());
    }

    SaveAdaptedVersionCommand versionCmd =
        new SaveAdaptedVersionCommand(
            job.getRecipeId(),
            current == null ? null : current.branchId(),
            current == null ? 0 : current.versionNumber(),
            current == null ? null : current.id(),
            List.<CreateIngredientRequest>of(),
            List.<CreateMethodStepRequest>of(),
            null,
            null,
            (CharacterFingerprintDto) null,
            finalDiff,
            response.reasoning(),
            traceId);
    RecipeVersionDto version =
        rebaseOrchestrator.saveAdaptedVersionWithRebase(
            versionCmd, c -> rebaseVersionCommand(c, job));
    return new ApplyOutcome(OutcomeKind.VERSION_CREATED, version.id());
  }

  /**
   * Rebase a version command after a conflict: refresh the parent-version expectations from the
   * catalogue's CURRENT head, keeping the same diff/reason/trace. The orchestrator re-attempts the
   * write against the moved head.
   */
  private SaveAdaptedVersionCommand rebaseVersionCommand(
      SaveAdaptedVersionCommand prev, AdaptationJob job) {
    Optional<RecipeVersionDto> head = currentHead(job.getRecipeId());
    return new SaveAdaptedVersionCommand(
        prev.recipeId(),
        head.map(RecipeVersionDto::branchId).orElse(prev.branchId()),
        head.map(RecipeVersionDto::versionNumber).orElse(prev.expectedParentVersionNumber()),
        head.map(RecipeVersionDto::id).orElse(prev.expectedParentVersionId()),
        prev.ingredients(),
        prev.method(),
        prev.metadata(),
        prev.tags(),
        prev.characterFingerprint(),
        prev.changeDiff(),
        prev.changeReason(),
        prev.adapterTraceId());
  }

  /** Rebase a branch command after a conflict: refresh the branch-point version from the head. */
  private SaveAdaptedBranchCommand rebaseBranchCommand(
      SaveAdaptedBranchCommand prev, AdaptationJob job) {
    Optional<RecipeVersionDto> head = currentHead(job.getRecipeId());
    return new SaveAdaptedBranchCommand(
        prev.recipeId(),
        head.map(RecipeVersionDto::branchId).orElse(prev.parentBranchId()),
        head.map(RecipeVersionDto::id).orElse(prev.branchPointVersionId()),
        prev.name(),
        prev.label(),
        prev.reason(),
        prev.ingredients(),
        prev.method(),
        prev.metadata(),
        prev.tags(),
        prev.characterFingerprint(),
        prev.adapterTraceId());
  }

  /** Re-read the recipe's current-version head for a rebase attempt (null-safe). */
  private Optional<RecipeVersionDto> currentHead(UUID recipeId) {
    if (contextAssembler == null) {
      return Optional.empty();
    }
    // The assembler reads recipe + current version via RecipeQueryService; reuse it so we don't add
    // a second cross-module read seam. A fresh placeholder job carries only the recipeId we need.
    AdaptationContext fresh =
        contextAssembler.assemble(
            AdaptationJob.builder()
                .id(UUID.randomUUID())
                .recipeId(recipeId)
                .userId(UUID.randomUUID())
                .source(JobSource.IMPORT)
                .build(),
            List.of(),
            new TriggerInputs.ImportTriggerInputs(null));
    return Optional.ofNullable(fresh.currentVersion());
  }

  /**
   * PLAN_OVERLAY apply (Trigger 4) — persist a plan-scoped substitution overlay. The master recipe
   * never mutates (LLD §Decisions §9). The substitution swaps the directive's targeted ingredient
   * (the chosen candidate's {@code from}) for its replacement ({@code to}); a DIETARY_TEMP reason
   * marks the overlay as plan-scoped.
   */
  UUID applyPlanOverlay(
      AdaptationJob job,
      AdaptationContext context,
      AdaptationContext withCandidates,
      RecipeAdaptationResponse response) {
    RecipeVersionDto current = context == null ? null : context.currentVersion();
    JsonNode diff = resolveFinalDiff(withCandidates, response);
    String fromKey = diff != null && diff.hasNonNull("from") ? diff.get("from").asText() : null;
    String toKey = diff != null && diff.hasNonNull("to") ? diff.get("to").asText() : null;
    if (fromKey == null || toKey == null) {
      // Defensive: a plan-time directive without a resolvable ingredient swap is a no-op overlay
      // candidate — surface as NO_CHANGE upstream rather than writing a malformed substitution.
      throw new AdaptationHardConstraintViolationException(
          "plan-overlay diff missing from/to ingredient keys for recipe " + job.getRecipeId());
    }
    SaveAdaptedSubstitutionCommand cmd =
        new SaveAdaptedSubstitutionCommand(
            job.getRecipeId(),
            current == null ? job.getRecipeId() : current.id(),
            new SubstitutionItemRequest(fromKey, BigDecimal.ONE, "unit"),
            new SubstitutionItemRequest(toKey, BigDecimal.ONE, "unit"),
            SubstitutionReason.DIETARY_TEMP,
            null,
            List.of(),
            response.reasoning(),
            true,
            job.getTraceId());
    RecipeSubstitutionDto sub = recipeWriteApi.saveAdaptedSubstitution(cmd);
    return sub.id();
  }

  private void publishCandidateProduced(AdaptationJob job, List<AdaptationCandidateDto> topN) {
    BigDecimal topScore =
        topN.isEmpty() ? BigDecimal.ZERO : topN.get(0).rollup().tasteAlignmentScore();
    events.publishEvent(
        new AdaptationCandidateProducedEvent(
            job.getId(),
            job.getRecipeId(),
            topN.size(),
            topScore,
            job.getTraceId(),
            Instant.now()));
  }

  private RecipeAdaptationResponse synthesiseAutoSkipResponse(List<AdaptationCandidateDto> topN) {
    AdaptationCandidateDto top = topN.get(0);
    return new RecipeAdaptationResponse(
        top.index(),
        top.proposedClassification(),
        "auto-skip: top score 2x",
        "",
        top.estimatedConfidence(),
        top.characterPreservationScore(),
        null,
        top.proposedDiff(),
        List.of());
  }

  private void handleFailure(
      AdaptationJob job, JobFailureReason reason, String excerpt, long startMillis) {
    int durationMs = (int) (System.currentTimeMillis() - startMillis);
    try {
      traceWriter.write(
          new AdaptationTraceWriter.TraceData(
              job.getId(),
              job.getRecipeId(),
              job.getTraceId(),
              job.getSource(),
              "adaptation/recipe-adaptation",
              job.getPromptTemplateVersion() == null ? "v0" : job.getPromptTemplateVersion(),
              null,
              JsonNodeFactory.instance.objectNode(),
              null,
              JsonNodeFactory.instance.arrayNode(),
              null,
              null,
              null,
              null,
              null,
              ValidationResult.FAILED_HARD,
              OutcomeKind.FAILED,
              null,
              durationMs));
    } catch (RuntimeException e) {
      LOG.warn("traceWriter.write on failure path: {}", e.getMessage());
    }
    transitionJobStatus(job.getId(), JobStatus.FAILED, reason, excerpt);
    events.publishEvent(
        new AdaptationJobFailedEvent(
            job.getId(), job.getRecipeId(), reason, excerpt, job.getTraceId(), Instant.now()));
  }

  /**
   * Sync-trigger helper (Trigger 2 + Trigger 4): runs {@link #processJob} OUTSIDE any wrapping tx,
   * then assembles an {@link AdaptationResultDto} from the trace row. {@code LockTimeoutException}
   * and other {@code AdaptationException}s propagate (controller layer maps each to its 4xx/5xx).
   *
   * <p>For PLAN_TIME jobs, infeasibility surfaces as a {@code classification = NO_CHANGE} result
   * (caught here) rather than throwing — the planner reads NO_CHANGE as the infeasibility signal
   * per LLD line 812.
   */
  AdaptationResultDto processSyncJob(AdaptationJob job) {
    try {
      processJob(job);
    } catch (AdaptationCharacterBreakException
        | com.example.mealprep.adaptation.exception.AdaptationHardConstraintViolationException
        | com.example.mealprep.adaptation.exception.AdaptationLowConfidenceException e) {
      // Plan-time + feedback: infeasibility surfaces as NO_CHANGE for planner consumers.
      if (job.getSource() == JobSource.PLAN_TIME) {
        return new AdaptationResultDto(
            job.getId(),
            job.getRecipeId(),
            AdaptationClassification.NO_CHANGE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            JsonNodeFactory.instance.objectNode(),
            e.getMessage(),
            null,
            false,
            List.of(),
            job.getTraceId(),
            BigDecimal.ZERO);
      }
      throw e;
    }
    return loadResultFromTrace(job);
  }

  /** Build an {@link AdaptationResultDto} from the persisted trace row for a sync-trigger job. */
  AdaptationResultDto loadResultFromTrace(AdaptationJob job) {
    var traceOpt = traceRepository.findByJobId(job.getId());
    if (traceOpt.isEmpty()) {
      return new AdaptationResultDto(
          job.getId(),
          job.getRecipeId(),
          AdaptationClassification.NO_CHANGE,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          JsonNodeFactory.instance.objectNode(),
          "no-trace",
          null,
          false,
          List.of(),
          job.getTraceId(),
          BigDecimal.ZERO);
    }
    var trace = traceOpt.get();
    AdaptationClassification classification =
        trace.getClassificationDecision() == null
            ? AdaptationClassification.NO_CHANGE
            : trace.getClassificationDecision();
    Optional<UUID> versionId = Optional.empty();
    Optional<UUID> branchId = Optional.empty();
    Optional<UUID> substitutionId = Optional.empty();
    Optional<UUID> pendingId = Optional.empty();
    switch (trace.getOutcomeKind()) {
      case PENDING_CREATED -> pendingId = Optional.ofNullable(trace.getOutcomeTargetId());
      case VERSION_CREATED -> versionId = Optional.ofNullable(trace.getOutcomeTargetId());
      case BRANCH_CREATED -> branchId = Optional.ofNullable(trace.getOutcomeTargetId());
      case SUBSTITUTION_CREATED -> substitutionId = Optional.ofNullable(trace.getOutcomeTargetId());
      default -> {
        // NO_OP / FAILED — all four optionals stay empty.
      }
    }
    boolean requiresApproval =
        job.getApprovalPolicy() == ApprovalPolicy.PENDING_CHANGE && pendingId.isPresent();
    return new AdaptationResultDto(
        job.getId(),
        job.getRecipeId(),
        classification,
        versionId,
        branchId,
        substitutionId,
        pendingId,
        trace.getFinalDiff() == null ? JsonNodeFactory.instance.objectNode() : trace.getFinalDiff(),
        "",
        null,
        requiresApproval,
        List.of(),
        job.getTraceId(),
        trace.getConfidence() == null ? BigDecimal.ZERO : trace.getConfidence());
  }

  private static String mapSource(JobSource source) {
    return switch (source) {
      case IMPORT -> "user";
      case FEEDBACK -> "feedback";
      case DATA_MODEL_CHANGE -> "data-model-change";
      case PLAN_TIME -> "refine-directive";
    };
  }

  // ---------------------------------------------------------------------------
  // AdaptationQueryService — read fan-out (still UOE in 01c)
  // ---------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public List<PendingChangeListItemDto> listPendingForUser(UUID userId) {
    // Rank-at-read: top-N PENDING by (impactScore DESC, confidence DESC, createdAt ASC).
    // Per LLD §Decisions §2 — the 3-per-week budget is enforced here, not at write time.
    int cap =
        adaptationConfig == null || adaptationConfig.pendingChangeBudgetPerWeek() <= 0
            ? 3
            : adaptationConfig.pendingChangeBudgetPerWeek();
    Pageable topN = PageRequest.of(0, cap);
    return pendingChangeRepository.findRankedPending(userId, topN).stream()
        .map(pendingChangeMapper::toListItem)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<PendingChangeListItemDto> listPendingHistoryForRecipe(UUID recipeId) {
    // Page-less wrapper (an admin/full-history dashboard caller paginates via the controller-side
    // method instead). Default ordering: createdAt desc.
    Pageable page = PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdAt"));
    return pendingChangeRepository.findByRecipeIdOrderByCreatedAtDesc(recipeId, page).stream()
        .map(pendingChangeMapper::toListItem)
        .toList();
  }

  /**
   * Paged history of pending changes for a recipe — backs the controller's pending-history
   * endpoint.
   */
  @Transactional(readOnly = true)
  public Page<PendingChangeListItemDto> listPendingHistoryForRecipe(UUID recipeId, Pageable page) {
    return pendingChangeRepository
        .findByRecipeIdOrderByCreatedAtDesc(recipeId, page)
        .map(pendingChangeMapper::toListItem);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PendingChangeDto> getPendingChange(UUID pendingChangeId) {
    return pendingChangeRepository.findById(pendingChangeId).map(pendingChangeMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AdaptationJobDto> getJob(UUID jobId) {
    return jobRepository.findById(jobId).map(jobMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AdaptationJobDto> getJobsForRecipe(UUID recipeId, Pageable pageable) {
    return jobRepository
        .findByRecipeIdOrderByEnqueuedAtDesc(recipeId, pageable)
        .map(jobMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AdaptationJobDto> getActiveJobsForUser(UUID userId, Pageable pageable) {
    return jobRepository
        .findByUserIdAndStatusInOrderByEnqueuedAtDesc(
            userId, java.util.Set.of(JobStatus.PENDING, JobStatus.RUNNING), pageable)
        .map(jobMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AdaptationTraceDto> getTracesForRecipe(UUID recipeId, Pageable pageable) {
    return traceRepository
        .findByRecipeIdOrderByCreatedAtDesc(recipeId, pageable)
        .map(traceMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AdaptationTraceDto> getTracesForPromptVersion(
      String name, String version, Pageable pageable) {
    return traceRepository
        .findByPromptTemplateNameAndPromptTemplateVersionOrderByCreatedAtDesc(
            name, version, pageable)
        .map(traceMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AdaptationTraceDto> getTraceForJob(UUID jobId) {
    return traceRepository.findByJobId(jobId).map(traceMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlannerHintDto> getActiveHintsForVersion(UUID versionId) {
    return plannerHintRepository.findActiveForVersion(versionId).stream()
        .map(plannerHintMapper::toDto)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, List<PlannerHintDto>> getActiveHintsForVersions(List<UUID> versionIds) {
    if (versionIds == null || versionIds.isEmpty()) {
      return Map.of();
    }
    // Single IN query (N+1 protection); group by versionId. Versions with no active hints are
    // absent from the map — no empty-list entries.
    return plannerHintRepository.findActiveForVersions(versionIds).stream()
        .collect(
            java.util.stream.Collectors.groupingBy(
                com.example.mealprep.adaptation.domain.entity.PlannerHintRecord::getVersionId,
                java.util.stream.Collectors.mapping(
                    plannerHintMapper::toDto, java.util.stream.Collectors.toList())));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AdaptationResultDto> getMostRecentResultForRecipe(UUID recipeId) {
    List<AdaptationJob> done =
        jobRepository.findMostRecentDoneForRecipe(recipeId, PageRequest.of(0, 1));
    if (done.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(loadResultFromTrace(done.get(0)));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AdaptationJobDto> getRunHistory(
      JobSource source, Instant from, Instant to, Pageable pageable) {
    return jobRepository
        .findBySourceAndEnqueuedAtBetweenOrderByEnqueuedAtDesc(source, from, to, pageable)
        .map(jobMapper::toDto);
  }
}
