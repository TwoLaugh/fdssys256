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
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

  private final FeedbackEntryRepository feedbackEntryRepository;
  private final RoutingLogRepository routingLogRepository;
  private final ClarificationQueryRepository clarificationQueryRepository;
  private final FeedbackEntryMapper entryMapper;
  private final RoutingLogMapper routingLogMapper;
  private final ApplicationEventPublisher eventPublisher;

  FeedbackServiceImpl(
      FeedbackEntryRepository feedbackEntryRepository,
      RoutingLogRepository routingLogRepository,
      ClarificationQueryRepository clarificationQueryRepository,
      FeedbackEntryMapper entryMapper,
      RoutingLogMapper routingLogMapper,
      ApplicationEventPublisher eventPublisher) {
    this.feedbackEntryRepository = feedbackEntryRepository;
    this.routingLogRepository = routingLogRepository;
    this.clarificationQueryRepository = clarificationQueryRepository;
    this.entryMapper = entryMapper;
    this.routingLogMapper = routingLogMapper;
    this.eventPublisher = eventPublisher;
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

  @Override
  public SubmitFeedbackResponse answerClarificationQuery(
      UUID userId, UUID queryId, AnswerClarificationRequest request) {
    throw new UnsupportedOperationException("feedback-01e impl pending — see ticket");
  }

  @Override
  public void retryStuckClassifications() {
    throw new UnsupportedOperationException("feedback-01g impl pending — see ticket");
  }

  @Override
  public void expireOldClarificationQueries() {
    throw new UnsupportedOperationException("feedback-01g impl pending — see ticket");
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
  public Page<ClarificationQueryDto> listClarificationQueries(
      UUID userId, ClarificationStatus status, Pageable pageable) {
    throw new UnsupportedOperationException("feedback-01e impl pending — see ticket");
  }

  @Override
  public Optional<ClarificationQueryDto> getClarificationQuery(UUID userId, UUID queryId) {
    throw new UnsupportedOperationException("feedback-01e impl pending — see ticket");
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
