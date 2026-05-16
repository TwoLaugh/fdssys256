package com.example.mealprep.adaptation.domain.service;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
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
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationLlmInvoker;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationTraceWriter;
import com.example.mealprep.adaptation.domain.service.internal.CandidateGenerator;
import com.example.mealprep.adaptation.domain.service.internal.ChangeDimensionResolver;
import com.example.mealprep.adaptation.domain.service.internal.CharacterPreservationGate;
import com.example.mealprep.adaptation.domain.service.internal.ConfidenceFloorGate;
import com.example.mealprep.adaptation.domain.service.internal.PendingChangeStore;
import com.example.mealprep.adaptation.domain.service.internal.ScoringEngine;
import com.example.mealprep.adaptation.event.AdaptationCandidateProducedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobCompletedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobFailedEvent;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.adaptation.exception.AdaptationCharacterBreakException;
import com.example.mealprep.adaptation.exception.AdaptationException;
import com.example.mealprep.adaptation.exception.LockTimeoutException;
import com.example.mealprep.adaptation.exception.RebaseExhaustedException;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
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
import org.springframework.data.domain.Pageable;
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
  private final LockService lockService;
  private final DecisionLogService decisionLogService;
  private final ApplicationEventPublisher events;

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
      LockService lockService,
      DecisionLogService decisionLogService,
      ApplicationEventPublisher events) {
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
    this.lockService = lockService;
    this.decisionLogService = decisionLogService;
    this.events = events;
  }

  // ---------------------------------------------------------------------------
  // AdaptationService — write surface (public trigger entries stay UOE in 01c)
  // ---------------------------------------------------------------------------

  @Override
  public UUID enqueueImportJob(ImportJobRequest request) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  @Override
  public AdaptationResultDto enqueueFeedbackJob(FeedbackJobRequest request) {
    throw new UnsupportedOperationException(UOE_TICKET_01C);
  }

  @Override
  public List<UUID> enqueueDataModelChangeJobs(DataModelJobRequest request) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  @Override
  public AdaptationResultDto runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest request) {
    throw new UnsupportedOperationException(UOE_TICKET_01C);
  }

  @Override
  public PendingChangeDto acceptPendingChange(
      UUID pendingChangeId, AcceptPendingChangeRequest request, UUID actorUserId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  @Override
  public PendingChangeDto rejectPendingChange(
      UUID pendingChangeId, RejectPendingChangeRequest request, UUID actorUserId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  @Override
  public PlannerHintDto emitPlannerHint(PlannerHintRequest request, UUID actorUserId) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  @Override
  public int sweepExpiredPendingChanges() {
    return 0;
  }

  // ---------------------------------------------------------------------------
  // 01c — private worker pipeline (processJob + transitionJobStatus)
  // ---------------------------------------------------------------------------

  /**
   * 10-step worker pipeline per LLD §Shared worker pipeline (lines 738-770). Not transactional —
   * each DB-touching step opens its own short tx. Callable from 01d's trigger entry methods.
   *
   * <p>Visibility is package-private so 01d's trigger entries (in the same package) can call it,
   * AND so unit tests can exercise it directly without touching the public surface.
   */
  void processJob(AdaptationJob job) {
    LOG.info(
        "processJob start jobId={} recipeId={} source={}",
        job.getId(),
        job.getRecipeId(),
        job.getSource());
    long startMillis = System.currentTimeMillis();
    try {
      // Step 1 — Acquire advisory lock.
      acquireLockOrFailJob(job);

      // Step 2 — Load context (placeholder — 01e refines the loader).
      AdaptationContext context = loadContextPlaceholder(job);

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
          handleFailure(job, JobFailureReason.AI_UNAVAILABLE, e.getMessage(), startMillis);
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
   * Step 1 wrapper. {@code REQUIRED, noRollbackFor = LockTimeoutException.class} so the IMPORT /
   * DATA_MODEL_CHANGE failure-excerpt write commits even when we throw on lock failure (LLD line
   * 880).
   */
  @Transactional(propagation = Propagation.REQUIRED, noRollbackFor = LockTimeoutException.class)
  protected void acquireLockOrFailJob(AdaptationJob job) {
    boolean ok = lockService.tryAcquire(LockKey.forRecipe(job.getRecipeId()));
    if (ok) {
      return;
    }
    switch (job.getSource()) {
      case IMPORT -> {
        transitionJobStatus(
            job.getId(), JobStatus.PENDING, JobFailureReason.UNKNOWN, "lock-deferred");
        throw new LockTimeoutException("lock-deferred:IMPORT");
      }
      case DATA_MODEL_CHANGE -> {
        transitionJobStatus(
            job.getId(), JobStatus.PENDING, JobFailureReason.UNKNOWN, "lock-deferred-batch");
        throw new LockTimeoutException("lock-deferred-batch:DATA_MODEL_CHANGE");
      }
      case FEEDBACK, PLAN_TIME ->
          throw new LockTimeoutException("lock-acquire-failed:" + job.getSource());
      default -> throw new LockTimeoutException("lock-acquire-failed");
    }
  }

  /**
   * Placeholder loader. 01e ships the real {@code AdaptationContextAssembler} that calls the peer
   * QueryServices. 01c only needs enough context to drive Stage A — the strategies tolerate
   * null/empty fields.
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
  public List<PendingChangeListItemDto> listPendingForUser(UUID userId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  @Override
  public List<PendingChangeListItemDto> listPendingHistoryForRecipe(UUID recipeId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  @Override
  public Optional<PendingChangeDto> getPendingChange(UUID pendingChangeId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  @Override
  public Page<AdaptationJobDto> getJobsForRecipe(UUID recipeId, Pageable pageable) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  @Override
  public Page<AdaptationJobDto> getActiveJobsForUser(UUID userId, Pageable pageable) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  @Override
  public Page<AdaptationTraceDto> getTracesForRecipe(UUID recipeId, Pageable pageable) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  @Override
  public Page<AdaptationTraceDto> getTracesForPromptVersion(
      String name, String version, Pageable pageable) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  @Override
  public Optional<AdaptationTraceDto> getTraceForJob(UUID jobId) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  @Override
  public List<PlannerHintDto> getActiveHintsForVersion(UUID versionId) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  @Override
  public Map<UUID, List<PlannerHintDto>> getActiveHintsForVersions(List<UUID> versionIds) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  @Override
  public Optional<AdaptationResultDto> getMostRecentResultForRecipe(UUID recipeId) {
    throw new UnsupportedOperationException(UOE_TICKET_01C);
  }
}
