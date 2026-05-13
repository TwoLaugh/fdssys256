package com.example.mealprep.adaptation.domain.service;

import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.AdaptationTraceDto;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeListItemDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Single implementation of both {@link AdaptationService} and {@link AdaptationQueryService} per
 * LLD line 485 ("Public services implemented by a single {@code AdaptationServiceImpl}").
 *
 * <p>01b ships the skeleton: every method throws {@link UnsupportedOperationException} naming the
 * sub-ticket that will fill it in (01c/01d/01e/01f). The single exception is {@link
 * #sweepExpiredPendingChanges()} which returns {@code 0} — it's idempotent and safe for the
 * (01d-shipping) {@code @Scheduled} cron job to call even before the body lands, avoiding spurious
 * errors during the inter-ticket window.
 *
 * <p>The bean wires correctly into Spring's DI graph (sibling integration tests can
 * {@code @MockBean} over it) while the UOE messages signal clearly that the implementation is
 * unfinished. No {@code @Primary} / {@code @Lazy} — it's the only impl of both interfaces.
 *
 * <p>01c/01d/01e/01f will extend the constructor with helper components (LockService,
 * RecipeWriteApi, CandidateGenerator, ScoringEngine, AdaptationLlmInvoker, PendingChangeStore,
 * RebaseOrchestrator, etc.) as those classes land. The repository set is the bedrock — every method
 * reads or writes at least one of these.
 */
@Service
public class AdaptationServiceImpl implements AdaptationService, AdaptationQueryService {

  private static final String UOE_TICKET_01C = "ticket-01c";
  private static final String UOE_TICKET_01D = "ticket-01d";
  private static final String UOE_TICKET_01E = "ticket-01e";
  private static final String UOE_TICKET_01F = "ticket-01f";

  @SuppressWarnings("unused")
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

  public AdaptationServiceImpl(
      AdaptationJobRepository jobRepository,
      PendingChangeRepository pendingChangeRepository,
      AdaptationTraceRepository traceRepository,
      AdaptationFingerprintRepository fingerprintRepository,
      PlannerHintRecordRepository plannerHintRepository,
      NutritionalKnowledgeRepository nutritionalKnowledgeRepository) {
    this.jobRepository = jobRepository;
    this.pendingChangeRepository = pendingChangeRepository;
    this.traceRepository = traceRepository;
    this.fingerprintRepository = fingerprintRepository;
    this.plannerHintRepository = plannerHintRepository;
    this.nutritionalKnowledgeRepository = nutritionalKnowledgeRepository;
  }

  // ---------------------------------------------------------------------------
  // AdaptationService — write surface
  // ---------------------------------------------------------------------------

  /** Implemented by ticket-01d (Trigger-1 import listener + worker pipeline entry). */
  @Override
  public UUID enqueueImportJob(ImportJobRequest request) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  /** Implemented by ticket-01c (Trigger-2 sync flow — feedback module entry). */
  @Override
  public AdaptationResultDto enqueueFeedbackJob(FeedbackJobRequest request) {
    throw new UnsupportedOperationException(UOE_TICKET_01C);
  }

  /** Implemented by ticket-01d (Trigger-3 async batch — data-model change listener). */
  @Override
  public List<UUID> enqueueDataModelChangeJobs(DataModelJobRequest request) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  /** Implemented by ticket-01c (Trigger-4 sync — plan-time Stage D entry). */
  @Override
  public AdaptationResultDto runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest request) {
    throw new UnsupportedOperationException(UOE_TICKET_01C);
  }

  /** Implemented by ticket-01d (pending-change lifecycle + RecipeWriteApi handoff). */
  @Override
  public PendingChangeDto acceptPendingChange(
      UUID pendingChangeId, AcceptPendingChangeRequest request, UUID actorUserId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  /** Implemented by ticket-01d (pending-change lifecycle). */
  @Override
  public PendingChangeDto rejectPendingChange(
      UUID pendingChangeId, RejectPendingChangeRequest request, UUID actorUserId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  /** Implemented by ticket-01f (PlannerHintEmitter). */
  @Override
  public PlannerHintDto emitPlannerHint(PlannerHintRequest request, UUID actorUserId) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  /**
   * Returns {@code 0} as a safe no-op until ticket-01d wires the real expiry sweep. The
   * (01d-shipping) {@code @Scheduled} {@code pendingExpirySweepCron} can call this freely during
   * the inter-ticket window without generating spurious failures — if no PENDING rows are expired,
   * the real implementation would also return zero.
   */
  @Override
  public int sweepExpiredPendingChanges() {
    return 0;
  }

  // ---------------------------------------------------------------------------
  // AdaptationQueryService — read fan-out
  // ---------------------------------------------------------------------------

  /** Implemented by ticket-01d (PendingChangesController read path). */
  @Override
  public List<PendingChangeListItemDto> listPendingForUser(UUID userId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  /** Implemented by ticket-01d. */
  @Override
  public List<PendingChangeListItemDto> listPendingHistoryForRecipe(UUID recipeId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  /** Implemented by ticket-01d. */
  @Override
  public Optional<PendingChangeDto> getPendingChange(UUID pendingChangeId) {
    throw new UnsupportedOperationException(UOE_TICKET_01D);
  }

  /** Implemented by ticket-01f (AdaptationAdminController). */
  @Override
  public Page<AdaptationJobDto> getJobsForRecipe(UUID recipeId, Pageable pageable) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  /** Implemented by ticket-01f. */
  @Override
  public Page<AdaptationJobDto> getActiveJobsForUser(UUID userId, Pageable pageable) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  /** Implemented by ticket-01f. */
  @Override
  public Page<AdaptationTraceDto> getTracesForRecipe(UUID recipeId, Pageable pageable) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  /** Implemented by ticket-01f. */
  @Override
  public Page<AdaptationTraceDto> getTracesForPromptVersion(
      String name, String version, Pageable pageable) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  /** Implemented by ticket-01f. */
  @Override
  public Optional<AdaptationTraceDto> getTraceForJob(UUID jobId) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  /** Implemented by ticket-01f (PlannerHintEmitter read path). */
  @Override
  public List<PlannerHintDto> getActiveHintsForVersion(UUID versionId) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  /** Implemented by ticket-01f. */
  @Override
  public Map<UUID, List<PlannerHintDto>> getActiveHintsForVersions(List<UUID> versionIds) {
    throw new UnsupportedOperationException(UOE_TICKET_01F);
  }

  /** Implemented by ticket-01c (writes the AdaptationResult on every job completion). */
  @Override
  public Optional<AdaptationResultDto> getMostRecentResultForRecipe(UUID recipeId) {
    throw new UnsupportedOperationException(UOE_TICKET_01C);
  }
}
