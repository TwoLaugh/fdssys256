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
import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
      AdaptationLockAcquirer lockAcquirer) {
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
            .inputs(emptyInputs())
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
            .inputs(emptyInputs())
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
              .inputs(emptyInputs())
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
            .inputs(emptyInputs())
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

      // Step 3 — Stage A candidate generation.
      List<AdaptationCandidateDto> candidates = candidateGenerator.generate(job, context);
      if (candidates.isEmpty()) {
        handleFailure(job, JobFailureReason.HARD_FILTER, "no-candidates", startMillis);
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

      // Step 6 — Validation gates.
      ValidationResult confidenceResult = confidenceFloorGate.evaluate(response);
      boolean forceBranch;
      try {
        forceBranch = characterPreservationGate.evaluateAndForceBranch(response);
      } catch (AdaptationCharacterBreakException e) {
        handleFailure(job, JobFailureReason.CHARACTER_BREAK, e.getMessage(), startMillis);
        throw e;
      }

      // Step 7 — Apply path: in 01c we always go via PendingChangeStore for safety.
      ApprovalPolicy effective =
          (confidenceResult == ValidationResult.LOW_CONFIDENCE)
              ? ApprovalPolicy.PENDING_CHANGE
              : job.getApprovalPolicy();
      OutcomeKind outcome;
      UUID outcomeTargetId = null;
      AdaptationClassification finalClassification =
          forceBranch ? AdaptationClassification.BRANCH : response.classification();
      if (effective == ApprovalPolicy.PENDING_CHANGE) {
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
      } else {
        // DIRECT / PLAN_OVERLAY apply paths land in 01d / 01e. For 01c the trace records NO_OP.
        outcome = OutcomeKind.NO_OP;
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
   * Parse the source-specific {@link TriggerInputs} from the job's {@code inputs} JSONB. 01d
   * persists an empty inputs object today, so every variant is constructed null/empty-tolerant; the
   * assembler folds nulls into the context cleanly via the {@code TriggerInputs} accessor defaults.
   */
  TriggerInputs triggerInputsFromJob(AdaptationJob job) {
    com.fasterxml.jackson.databind.JsonNode inputs = job.getInputs();
    return switch (job.getSource()) {
      case FEEDBACK -> {
        String text =
            inputs != null && inputs.hasNonNull("feedbackText")
                ? inputs.get("feedbackText").asText()
                : null;
        yield new TriggerInputs.FeedbackTriggerInputs(text, null);
      }
      case PLAN_TIME -> new TriggerInputs.PlanTimeTriggerInputs(null, null);
      case DATA_MODEL_CHANGE -> new TriggerInputs.DataModelTriggerInputs(null, inputs);
      case IMPORT -> new TriggerInputs.ImportTriggerInputs(inputs);
    };
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
   * Empty JSONB stub for {@code AdaptationJob.inputs} on insert. 01e enriches with real prompt
   * payloads; for 01d we just need a non-null insert.
   */
  private com.fasterxml.jackson.databind.JsonNode emptyInputs() {
    return JsonNodeFactory.instance.objectNode();
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
