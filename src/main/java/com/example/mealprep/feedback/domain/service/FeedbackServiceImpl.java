package com.example.mealprep.feedback.domain.service;

import com.example.mealprep.feedback.api.dto.AnswerClarificationRequest;
import com.example.mealprep.feedback.api.dto.ClarificationQueryDto;
import com.example.mealprep.feedback.api.dto.CorrectionRequest;
import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.MisclassificationCorrectionDto;
import com.example.mealprep.feedback.api.dto.RoutingDecisionDto;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackRequest;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackResponse;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.api.mapper.ClarificationQueryMapper;
import com.example.mealprep.feedback.api.mapper.FeedbackEntryMapper;
import com.example.mealprep.feedback.api.mapper.MisclassificationCorrectionMapper;
import com.example.mealprep.feedback.api.mapper.RoutingLogMapper;
import com.example.mealprep.feedback.config.FeedbackRetrySweepProperties;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.MisclassificationCorrection;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.MisclassificationCorrectionRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.domain.service.internal.ClarificationExpirer;
import com.example.mealprep.feedback.domain.service.internal.ConfidenceGate;
import com.example.mealprep.feedback.domain.service.internal.CorrectionReplayer;
import com.example.mealprep.feedback.domain.service.internal.FeedbackRouter;
import com.example.mealprep.feedback.domain.service.internal.StuckClassificationRetrier;
import com.example.mealprep.feedback.event.FeedbackMisclassificationCorrectedEvent;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import com.example.mealprep.feedback.exception.ClarificationQueryAlreadyAnsweredException;
import com.example.mealprep.feedback.exception.ClarificationQueryExpiredException;
import com.example.mealprep.feedback.exception.ClarificationQueryNotFoundException;
import com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException;
import com.example.mealprep.feedback.exception.InvalidCorrectionTargetException;
import com.example.mealprep.feedback.exception.RoutingDecisionNotFoundException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackReverter;
import com.example.mealprep.feedback.spi.PreferenceFeedbackReverter;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackReverter;
import com.example.mealprep.feedback.spi.RecipeFeedbackReverter;
import com.example.mealprep.feedback.spi.RevertContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single impl of {@link FeedbackQueryService} and {@link FeedbackUpdateService}, per the LLD style
 * guide §Service Interfaces convention. Package-private — cross-module callers inject the
 * interfaces.
 *
 * <p>01b implements: {@link #submitFeedback}, {@link #getById}, {@link #getByIds}, {@link
 * #listByUser}, {@link #getRoutingDecision}. The other interface methods throw {@code
 * UnsupportedOperationException} pending feedback-01e/01f/01g.
 *
 * <p>{@code submitFeedback} publishes {@link FeedbackSubmittedEvent} via the standard publisher;
 * Spring's {@code @TransactionalEventListener(phase = AFTER_COMMIT)} consumers (01c) see the event
 * only after the {@code feedback_entries} row is durably committed.
 */
@Service
class FeedbackServiceImpl implements FeedbackQueryService, FeedbackUpdateService {

  private static final Logger log = LoggerFactory.getLogger(FeedbackServiceImpl.class);

  private final FeedbackEntryRepository feedbackEntryRepository;
  private final RoutingLogRepository routingLogRepository;
  private final ClarificationQueryRepository clarificationQueryRepository;
  private final MisclassificationCorrectionRepository misclassificationCorrectionRepository;
  private final FeedbackEntryMapper entryMapper;
  private final RoutingLogMapper routingLogMapper;
  private final ClarificationQueryMapper clarificationQueryMapper;
  private final MisclassificationCorrectionMapper misclassificationCorrectionMapper;
  private final ClarificationExpirer clarificationExpirer;
  private final StuckClassificationRetrier stuckClassificationRetrier;
  private final FeedbackRetrySweepProperties retrySweepProperties;
  private final CorrectionReplayer correctionReplayer;
  private final PreferenceFeedbackReverter preferenceReverter;
  private final NutritionFeedbackReverter nutritionReverter;
  private final ProvisionsFeedbackReverter provisionsReverter;
  private final RecipeFeedbackReverter recipeReverter;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  FeedbackServiceImpl(
      FeedbackEntryRepository feedbackEntryRepository,
      RoutingLogRepository routingLogRepository,
      ClarificationQueryRepository clarificationQueryRepository,
      MisclassificationCorrectionRepository misclassificationCorrectionRepository,
      FeedbackEntryMapper entryMapper,
      RoutingLogMapper routingLogMapper,
      ClarificationQueryMapper clarificationQueryMapper,
      MisclassificationCorrectionMapper misclassificationCorrectionMapper,
      ClarificationExpirer clarificationExpirer,
      StuckClassificationRetrier stuckClassificationRetrier,
      FeedbackRetrySweepProperties retrySweepProperties,
      CorrectionReplayer correctionReplayer,
      PreferenceFeedbackReverter preferenceReverter,
      NutritionFeedbackReverter nutritionReverter,
      ProvisionsFeedbackReverter provisionsReverter,
      RecipeFeedbackReverter recipeReverter,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.feedbackEntryRepository = feedbackEntryRepository;
    this.routingLogRepository = routingLogRepository;
    this.clarificationQueryRepository = clarificationQueryRepository;
    this.misclassificationCorrectionRepository = misclassificationCorrectionRepository;
    this.entryMapper = entryMapper;
    this.routingLogMapper = routingLogMapper;
    this.clarificationQueryMapper = clarificationQueryMapper;
    this.misclassificationCorrectionMapper = misclassificationCorrectionMapper;
    this.clarificationExpirer = clarificationExpirer;
    this.stuckClassificationRetrier = stuckClassificationRetrier;
    this.retrySweepProperties = retrySweepProperties;
    this.correctionReplayer = correctionReplayer;
    this.preferenceReverter = preferenceReverter;
    this.nutritionReverter = nutritionReverter;
    this.provisionsReverter = provisionsReverter;
    this.recipeReverter = recipeReverter;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // ---------------- FeedbackUpdateService ----------------

  @Override
  @Transactional
  public SubmitFeedbackResponse submitFeedback(UUID userId, SubmitFeedbackRequest request) {
    UUID feedbackId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    UiContextDocument doc = toDocument(request.context());
    FeedbackEntry entry =
        FeedbackEntry.builder()
            .id(feedbackId)
            .userId(userId)
            .text(request.text())
            .uiContext(doc)
            .submissionStatus(SubmissionStatus.RECEIVED)
            .classificationAttempts(0)
            .traceId(traceId)
            .routingLog(new ArrayList<>())
            .build();
    feedbackEntryRepository.save(entry);
    eventPublisher.publishEvent(
        new FeedbackSubmittedEvent(feedbackId, userId, doc.screen(), traceId, Instant.now()));
    return new SubmitFeedbackResponse(
        feedbackId, traceId, SubmissionStatus.RECEIVED, List.of(), null);
  }

  /**
   * Flow 4 (lld/feedback.md lines 788-816): user-driven correction of a misclassified routing.
   * Best-effort undo of the original destination's write, mark the original {@code CORRECTED_AWAY},
   * persist the ground-truth {@link MisclassificationCorrection} row ({@code PENDING_REPLAY}),
   * re-fire the corrected destination via the router's {@code REQUIRES_NEW} replay path, stamp the
   * outcome, recompute the entry's {@code submissionStatus}, and publish {@link
   * FeedbackMisclassificationCorrectedEvent} INSIDE this tx so AFTER_COMMIT listeners fire (wave-3
   * retro: events with no active tx are silently dropped).
   *
   * <p>{@code @Transactional} (default REQUIRED) for the bookkeeping; the synthetic routing fires
   * in the router's own {@code REQUIRES_NEW} so a destination failure does not roll back the
   * correction record.
   */
  @Override
  @Transactional
  public SubmitFeedbackResponse correctMisclassification(
      UUID userId, UUID feedbackId, UUID routingId, CorrectionRequest request) {

    FeedbackEntry entry =
        feedbackEntryRepository
            .findWithRoutingByIdAndUserId(feedbackId, userId)
            .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));

    RoutingLogEntry original =
        entry.getRoutingLog().stream()
            .filter(r -> r.getId().equals(routingId))
            .findFirst()
            .orElseThrow(() -> new RoutingDecisionNotFoundException(routingId));

    validatePreconditions(entry, original, request);

    bestEffortRevert(entry, original);

    original.setStatus(RoutingStatus.CORRECTED_AWAY);
    original.setCompletedAt(clock.instant());
    routingLogRepository.save(original);

    MisclassificationCorrection correction =
        MisclassificationCorrection.builder()
            .id(UUID.randomUUID())
            .feedbackEntry(entry)
            .originalRoutingId(original.getId())
            .correctedDestination(request.newDestination())
            .userCorrectionNote(request.userCorrectionNote())
            .actorUserId(userId)
            .originalConfidence(original.getConfidence())
            .originalDestination(original.getDestination())
            .replayRoutingId(null)
            .replayStatus(CorrectionReplayStatus.PENDING_REPLAY)
            .occurredAt(clock.instant())
            .build();
    misclassificationCorrectionRepository.save(correction);
    // TODO(feedback-01g): increment a `feedback.corrections.recorded` Micrometer counter once
    // Actuator/Micrometer is on the classpath (not currently a project dependency — ticket §8).

    ConfidenceGate.ScoredClassification synthetic =
        correctionReplayer.buildSynthetic(entry, request);
    FeedbackRouter.RouteReplayResult replay = correctionReplayer.replay(entry, synthetic);

    original.setSupersededById(replay.newRoutingLogId());
    routingLogRepository.save(original);

    correction.setReplayRoutingId(replay.newRoutingLogId());
    correction.setReplayStatus(
        correctionReplayer.mapReplayStatus(replay.status(), replay.failureKind()));
    misclassificationCorrectionRepository.save(correction);

    // Re-read the routing log directly: the replay wrote a NEW row in a separate REQUIRES_NEW tx,
    // so the entry aggregate's lazy collection (already initialised on the first-level-cached
    // entry) is a stale snapshot and would miss the replay row. A direct JPQL query on the log
    // returns the freshly-committed replay row plus the in-context originals (which carry our
    // pending CORRECTED_AWAY + supersededBy mutations). Wave-3 retro: stale-snapshot post-replay.
    List<RoutingLogEntry> currentLog =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    SubmissionStatus next = recomputeSubmissionStatus(currentLog);
    int rows = feedbackEntryRepository.updateSubmissionStatus(feedbackId, next);
    if (rows == 0) {
      throw new FeedbackEntryNotFoundException(feedbackId);
    }

    eventPublisher.publishEvent(
        new FeedbackMisclassificationCorrectedEvent(
            feedbackId,
            original.getId(),
            replay.newRoutingLogId(),
            correction.getOriginalDestination(),
            request.newDestination(),
            correction.getOriginalConfidence(),
            userId,
            entry.getTraceId(),
            clock.instant()));

    return new SubmitFeedbackResponse(
        feedbackId, entry.getTraceId(), next, routingLogMapper.toDtos(currentLog), null);
  }

  /** Pre-condition gate (lld/feedback.md §Flow 4 step 2-4). Each failure → 422. */
  private void validatePreconditions(
      FeedbackEntry entry, RoutingLogEntry original, CorrectionRequest request) {
    if (request.newDestination() == original.getDestination()) {
      throw new InvalidCorrectionTargetException("new destination matches original (no-op)");
    }
    if (original.getStatus() == RoutingStatus.CORRECTED_AWAY
        || original.getStatus() == RoutingStatus.REPLAYED) {
      throw new InvalidCorrectionTargetException(
          "original routing already corrected; correction chains are not supported in v1");
    }
    if (request.newDestination() == Destination.RECIPE) {
      boolean hasRecipeIdInContext =
          entry.getUiContext() != null && entry.getUiContext().recipeId() != null;
      boolean hasRecipeIdInPayload =
          original.getStructuredPayload() != null
              && !original.getStructuredPayload().path("recipeId").asText("").isEmpty();
      if (!hasRecipeIdInContext && !hasRecipeIdInPayload) {
        throw new InvalidCorrectionTargetException(
            "cannot correct to RECIPE; no recipe attached to this feedback");
      }
    }
  }

  /**
   * Best-effort undo of the original destination's write (lld/feedback.md §Flow 4 step 3). Reverter
   * is Noop by default until the wave-2 destination ships its real impl. Never blocks the
   * correction — a thrown reverter is logged WARN and the flow proceeds.
   */
  private void bestEffortRevert(FeedbackEntry entry, RoutingLogEntry original) {
    RevertContext revertCtx =
        new RevertContext(
            original.getId(),
            entry.getUserId(),
            entry.getTraceId(),
            original.getDestination(),
            original.getStructuredPayload(),
            original.getDestinationResultJson());
    try {
      switch (original.getDestination()) {
        case RECIPE -> recipeReverter.revert(revertCtx);
        case PREFERENCE -> preferenceReverter.revert(revertCtx);
        case NUTRITION -> nutritionReverter.revert(revertCtx);
        case PROVISIONS -> provisionsReverter.revert(revertCtx);
      }
    } catch (Exception revertFail) {
      log.warn(
          "Revert of original routing {} failed; proceeding with correction record",
          original.getId(),
          revertFail);
    }
  }

  /**
   * Flow 3 step 7 reconciliation with the corrected route counting (lld/feedback.md §Flow 4 step
   * 8). {@code CORRECTED_AWAY} rows are excluded; the all-applied happy path yields {@code
   * CORRECTED} (LLD line 218 — not {@code ROUTED} — since this is a correction).
   */
  private SubmissionStatus recomputeSubmissionStatus(List<RoutingLogEntry> routingLog) {
    List<RoutingLogEntry> active =
        routingLog.stream().filter(r -> r.getStatus() != RoutingStatus.CORRECTED_AWAY).toList();
    if (active.isEmpty()) {
      return SubmissionStatus.FAILED;
    }
    boolean anyFailed = active.stream().anyMatch(r -> r.getStatus() == RoutingStatus.FAILED);
    boolean anyNonFailed = active.stream().anyMatch(r -> r.getStatus() != RoutingStatus.FAILED);
    if (anyFailed && anyNonFailed) {
      return SubmissionStatus.PARTIALLY_FAILED;
    }
    if (anyFailed) {
      return SubmissionStatus.FAILED;
    }
    return SubmissionStatus.CORRECTED;
  }

  /**
   * Flow 5 (LLD lines 818-833): the user answers a pending clarification; the entry becomes
   * re-eligible for classification and a fresh {@link FeedbackSubmittedEvent} re-triggers 01c's
   * async classifier with the answered-clarification context.
   *
   * <p>The query is mutated through Hibernate so its {@code @Version} guards against a concurrent
   * answer (second writer → {@code OptimisticLockingFailureException} → 409). The parent entry's
   * status flip uses the native UPDATE (round-8 retro: avoids the {@code @Version} race when 01c's
   * AFTER_COMMIT listener re-reads the entry). The event is published <em>inside</em> this
   * transaction so {@code @TransactionalEventListener(AFTER_COMMIT)} consumers fire on commit —
   * Spring silently drops AFTER_COMMIT events published with no active transaction (01c gotcha).
   */
  @Override
  @Transactional
  public SubmitFeedbackResponse answerClarificationQuery(
      UUID userId, UUID queryId, AnswerClarificationRequest request) {

    ClarificationQuery query =
        clarificationQueryRepository
            .findByIdAndFeedbackEntryUserId(queryId, userId)
            .orElseThrow(() -> new ClarificationQueryNotFoundException(queryId));

    if (query.getStatus() == ClarificationStatus.EXPIRED) {
      throw new ClarificationQueryExpiredException(queryId, query.getFeedbackEntry().getId());
    }
    if (query.getStatus() == ClarificationStatus.ANSWERED) {
      throw new ClarificationQueryAlreadyAnsweredException(queryId);
    }
    if (!request.isAtLeastOneProvided()) {
      // Defensive guard for a record reconstructed outside Jakarta validation (e.g. reflection).
      throw new IllegalArgumentException(
          "answer must provide selectedDestination or userClarificationText");
    }

    query.setStatus(ClarificationStatus.ANSWERED);
    query.setSelectedDestination(request.selectedDestination());
    query.setUserClarificationText(request.userClarificationText());
    query.setAnsweredAt(clock.instant());
    clarificationQueryRepository.save(query);

    FeedbackEntry entry = query.getFeedbackEntry();
    // Re-eligible for classification (LLD line 826). classificationAttempts is intentionally NOT
    // touched here — 01c's listener increments it on its RECEIVED → CLASSIFYING transition.
    int rows =
        feedbackEntryRepository.updateSubmissionStatus(entry.getId(), SubmissionStatus.RECEIVED);
    if (rows == 0) {
      throw new ClarificationQueryNotFoundException(queryId);
    }

    eventPublisher.publishEvent(
        new FeedbackSubmittedEvent(
            entry.getId(),
            entry.getUserId(),
            entry.getUiContext().screen(),
            entry.getTraceId(), // SAME traceId across all attempts — keeps the decision-log linked.
            clock.instant()));

    return new SubmitFeedbackResponse(
        entry.getId(), entry.getTraceId(), SubmissionStatus.RECEIVED, List.of(), null);
  }

  /**
   * Recovery half of the classifier's graceful-degrade model (LLD lines 570-581). Every 2 minutes
   * (LLD line 514) it sweeps entries stuck in {@code RECEIVED}/{@code CLASSIFYING} past the 5-min
   * window — the listener reverts to {@code RECEIVED} and walks away when the AI is unavailable,
   * and without this sweep those entries would sit there forever. Entries older than 24h escalate
   * to {@code FAILED}; the rest are re-classified by re-publishing {@link FeedbackSubmittedEvent}
   * (same {@code traceId}) onto the proven async pipeline.
   *
   * <p>Parser-failed entries are already {@code FAILED} in the listener ("the prompt is the bug,
   * not the runtime", LLD line 579), so the {@code RECEIVED}/{@code CLASSIFYING} status filter
   * naturally excludes them — no extra guard.
   *
   * <p>Reads {@code clock.instant()} (never {@code Instant.now()} — time-bomb gotcha). Each entry
   * is dispatched to a sibling {@link StuckClassificationRetrier} that owns its own {@code
   * REQUIRES_NEW} tx (Spring self-invocation gotcha + per-item isolation, mirrors {@link
   * #expireOldClarificationQueries}); a per-item failure is logged WARN and the sweep continues.
   *
   * <p>{@code @Scheduled(fixedDelay)} cadence is config-overridable via {@code
   * mealprep.feedback.retry-sweep.fixed-delay-ms} (default 120000). The {@code initialDelay} lets
   * the test profile push the first auto-fire far out so ITs invoke {@code
   * retryStuckClassifications} deterministically without a background tick racing the seed.
   */
  @Override
  @Scheduled(
      fixedDelayString = "${mealprep.feedback.retry-sweep.fixed-delay-ms:120000}",
      initialDelayString = "${mealprep.feedback.retry-sweep.initial-delay-ms:120000}")
  public void retryStuckClassifications() {
    Instant cutoff = clock.instant().minus(retrySweepProperties.stuckAfter());
    List<FeedbackEntry> stuck =
        feedbackEntryRepository.findStuckForRetry(
            Set.of(SubmissionStatus.RECEIVED, SubmissionStatus.CLASSIFYING), cutoff);
    for (FeedbackEntry entry : stuck) {
      try {
        stuckClassificationRetrier.retryOne(entry.getId());
      } catch (RuntimeException ex) {
        log.warn(
            "Failed to retry stuck classification {}; will retry next sweep", entry.getId(), ex);
      }
    }
  }

  /**
   * Daily 04:00 UTC sweep (LLD line 515). Flips PENDING clarifications past their 7-day TTL to
   * EXPIRED and fails the parent entry. {@code Instant.now()} via the injected {@link Clock} (never
   * a hardcoded date — time-bomb gotcha). Each query expires in its own {@code REQUIRES_NEW}
   * transaction via {@link ClarificationExpirer} (sibling bean — Spring self-invocation gotcha).
   */
  @Override
  @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
  public void expireOldClarificationQueries() {
    Instant cutoff = clock.instant();
    List<ClarificationQuery> expired =
        clarificationQueryRepository.findByStatusAndExpiresAtBefore(
            ClarificationStatus.PENDING, cutoff);
    for (ClarificationQuery q : expired) {
      try {
        clarificationExpirer.expireOne(q.getId());
      } catch (RuntimeException ex) {
        log.warn("Failed to expire clarification {}; will retry next sweep", q.getId(), ex);
      }
    }
  }

  // ---------------- FeedbackQueryService ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<FeedbackEntryDto> getById(UUID userId, UUID feedbackId) {
    return feedbackEntryRepository
        .findWithRoutingByIdAndUserId(feedbackId, userId)
        .map(
            entry -> {
              FeedbackEntryDto dto = entryMapper.toDto(entry);
              UUID pendingId =
                  clarificationQueryRepository
                      .findFirstByFeedbackEntryIdAndStatus(feedbackId, ClarificationStatus.PENDING)
                      .map(ClarificationQuery::getId)
                      .orElse(null);
              return dto.withPendingClarificationQueryId(pendingId);
            });
  }

  @Override
  @Transactional(readOnly = true)
  public List<FeedbackEntryDto> getByIds(UUID userId, List<UUID> feedbackIds) {
    if (feedbackIds == null || feedbackIds.isEmpty()) {
      return List.of();
    }
    List<FeedbackEntryDto> out = new ArrayList<>(feedbackIds.size());
    for (UUID id : feedbackIds) {
      getById(userId, id).ifPresent(out::add);
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<FeedbackEntryDto> listByUser(UUID userId, Pageable pageable) {
    return feedbackEntryRepository
        .findByUserIdOrderByCreatedAtDesc(userId, pageable)
        .map(entryMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RoutingDecisionDto> getRoutingDecision(UUID userId, UUID routingId) {
    return routingLogRepository
        .findByIdAndFeedbackEntryUserId(routingId, userId)
        .map(routingLogMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ClarificationQueryDto> listClarificationQueries(
      UUID userId, ClarificationStatus status, Pageable pageable) {
    Page<ClarificationQuery> page =
        status == null
            ? clarificationQueryRepository.findByFeedbackEntryUserIdOrderByCreatedAtAsc(
                userId, pageable)
            : clarificationQueryRepository.findByFeedbackEntryUserIdAndStatusOrderByCreatedAtAsc(
                userId, status, pageable);
    return page.map(clarificationQueryMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ClarificationQueryDto> getClarificationQuery(UUID userId, UUID queryId) {
    return clarificationQueryRepository
        .findByIdAndFeedbackEntryUserId(queryId, userId)
        .map(clarificationQueryMapper::toDto);
  }

  /** 01f — paginated misclassification corrections for the caller, newest-first (ticket §17). */
  @Override
  @Transactional(readOnly = true)
  public Page<MisclassificationCorrectionDto> listCorrections(UUID userId, Pageable pageable) {
    return misclassificationCorrectionRepository
        .findByFeedbackEntryUserIdOrderByOccurredAtDesc(userId, pageable)
        .map(misclassificationCorrectionMapper::toDto);
  }

  // ---------------- helpers ----------------

  /**
   * Inline DTO → document conversion. Per the ticket, this is deliberately not a MapStruct mapper:
   * the record copy is trivial and the document type lives in {@code domain/document/} where
   * keeping {@code api/mapper/} import-free is preferable.
   */
  private static UiContextDocument toDocument(UiContextDto dto) {
    return new UiContextDocument(
        dto.screen(),
        dto.recipeId(),
        dto.recipeVersion(),
        dto.mealSlotId(),
        dto.planId(),
        dto.referenceDate());
  }
}
