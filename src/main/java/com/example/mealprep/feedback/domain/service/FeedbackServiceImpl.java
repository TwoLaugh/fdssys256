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
import com.example.mealprep.feedback.api.mapper.RoutingLogMapper;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.domain.service.internal.ClarificationExpirer;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import com.example.mealprep.feedback.exception.ClarificationQueryAlreadyAnsweredException;
import com.example.mealprep.feedback.exception.ClarificationQueryExpiredException;
import com.example.mealprep.feedback.exception.ClarificationQueryNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
  private final FeedbackEntryMapper entryMapper;
  private final RoutingLogMapper routingLogMapper;
  private final ClarificationQueryMapper clarificationQueryMapper;
  private final ClarificationExpirer clarificationExpirer;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  FeedbackServiceImpl(
      FeedbackEntryRepository feedbackEntryRepository,
      RoutingLogRepository routingLogRepository,
      ClarificationQueryRepository clarificationQueryRepository,
      FeedbackEntryMapper entryMapper,
      RoutingLogMapper routingLogMapper,
      ClarificationQueryMapper clarificationQueryMapper,
      ClarificationExpirer clarificationExpirer,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.feedbackEntryRepository = feedbackEntryRepository;
    this.routingLogRepository = routingLogRepository;
    this.clarificationQueryRepository = clarificationQueryRepository;
    this.entryMapper = entryMapper;
    this.routingLogMapper = routingLogMapper;
    this.clarificationQueryMapper = clarificationQueryMapper;
    this.clarificationExpirer = clarificationExpirer;
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

  @Override
  public SubmitFeedbackResponse correctMisclassification(
      UUID userId, UUID feedbackId, UUID routingId, CorrectionRequest request) {
    throw new UnsupportedOperationException("feedback-01f impl pending — see ticket");
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

  @Override
  public void retryStuckClassifications() {
    throw new UnsupportedOperationException("feedback-01g impl pending — see ticket");
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

  @Override
  public Page<MisclassificationCorrectionDto> listCorrections(UUID userId, Pageable pageable) {
    throw new UnsupportedOperationException("feedback-01f impl pending — see ticket");
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
