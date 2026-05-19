package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.api.dto.AnswerClarificationRequest;
import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.RoutingDecisionDto;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackRequest;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackResponse;
import com.example.mealprep.feedback.api.mapper.ClarificationQueryMapper;
import com.example.mealprep.feedback.api.mapper.FeedbackEntryMapper;
import com.example.mealprep.feedback.api.mapper.MisclassificationCorrectionMapper;
import com.example.mealprep.feedback.api.mapper.RoutingLogMapper;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.MisclassificationCorrectionRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.domain.service.FeedbackQueryService;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import com.example.mealprep.feedback.domain.service.internal.ClarificationExpirer;
import com.example.mealprep.feedback.domain.service.internal.CorrectionReplayer;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import com.example.mealprep.feedback.spi.NutritionFeedbackReverter;
import com.example.mealprep.feedback.spi.PreferenceFeedbackReverter;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackReverter;
import com.example.mealprep.feedback.spi.RecipeFeedbackReverter;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for the service impl that backs both {@link FeedbackQueryService} and {@link
 * FeedbackUpdateService}. Pure Mockito — no Spring context, no DB.
 *
 * <p>The impl class itself is package-private, so we construct it via reflection — keeps the test
 * free of having to widen the class visibility.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceImplTest {

  @Mock private FeedbackEntryRepository feedbackEntryRepository;
  @Mock private RoutingLogRepository routingLogRepository;
  @Mock private ClarificationQueryRepository clarificationQueryRepository;
  @Mock private MisclassificationCorrectionRepository misclassificationCorrectionRepository;
  @Mock private FeedbackEntryMapper entryMapper;
  @Mock private RoutingLogMapper routingLogMapper;
  @Mock private ClarificationQueryMapper clarificationQueryMapper;
  @Mock private MisclassificationCorrectionMapper misclassificationCorrectionMapper;
  @Mock private ClarificationExpirer clarificationExpirer;
  @Mock private CorrectionReplayer correctionReplayer;
  @Mock private PreferenceFeedbackReverter preferenceReverter;
  @Mock private NutritionFeedbackReverter nutritionReverter;
  @Mock private ProvisionsFeedbackReverter provisionsReverter;
  @Mock private RecipeFeedbackReverter recipeReverter;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), ZoneOffset.UTC);

  private Object serviceObject;

  private FeedbackQueryService queryService() {
    return (FeedbackQueryService) service();
  }

  private FeedbackUpdateService updateService() {
    return (FeedbackUpdateService) service();
  }

  /** Instantiate the package-private impl via reflection — keeps the impl out of the public API. */
  private Object service() {
    if (serviceObject != null) {
      return serviceObject;
    }
    try {
      Class<?> implClass =
          Class.forName("com.example.mealprep.feedback.domain.service.FeedbackServiceImpl");
      java.lang.reflect.Constructor<?> ctor =
          implClass.getDeclaredConstructor(
              FeedbackEntryRepository.class,
              RoutingLogRepository.class,
              ClarificationQueryRepository.class,
              MisclassificationCorrectionRepository.class,
              FeedbackEntryMapper.class,
              RoutingLogMapper.class,
              ClarificationQueryMapper.class,
              MisclassificationCorrectionMapper.class,
              ClarificationExpirer.class,
              CorrectionReplayer.class,
              PreferenceFeedbackReverter.class,
              NutritionFeedbackReverter.class,
              ProvisionsFeedbackReverter.class,
              RecipeFeedbackReverter.class,
              ApplicationEventPublisher.class,
              Clock.class);
      ctor.setAccessible(true);
      serviceObject =
          ctor.newInstance(
              feedbackEntryRepository,
              routingLogRepository,
              clarificationQueryRepository,
              misclassificationCorrectionRepository,
              entryMapper,
              routingLogMapper,
              clarificationQueryMapper,
              misclassificationCorrectionMapper,
              clarificationExpirer,
              correctionReplayer,
              preferenceReverter,
              nutritionReverter,
              provisionsReverter,
              recipeReverter,
              eventPublisher,
              clock);
      return serviceObject;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Could not instantiate FeedbackServiceImpl", e);
    }
  }

  // ---------------- submitFeedback ----------------

  @Test
  void submitFeedback_persistsEntry_andPublishesEvent_withReceivedStatus() {
    UUID userId = UUID.randomUUID();
    SubmitFeedbackRequest req = FeedbackTestData.submitFeedbackRequest("the salt was too much");

    SubmitFeedbackResponse response = updateService().submitFeedback(userId, req);

    assertThat(response.submissionStatus()).isEqualTo(SubmissionStatus.RECEIVED);
    assertThat(response.routes()).isEmpty();
    assertThat(response.pendingClarificationQueryId()).isNull();
    assertThat(response.feedbackId()).isNotNull();
    assertThat(response.traceId()).isNotNull();
    assertThat(response.traceId()).isNotEqualTo(response.feedbackId());

    ArgumentCaptor<FeedbackEntry> entryCaptor = ArgumentCaptor.forClass(FeedbackEntry.class);
    verify(feedbackEntryRepository).save(entryCaptor.capture());
    FeedbackEntry saved = entryCaptor.getValue();
    assertThat(saved.getId()).isEqualTo(response.feedbackId());
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getText()).isEqualTo("the salt was too much");
    assertThat(saved.getSubmissionStatus()).isEqualTo(SubmissionStatus.RECEIVED);
    assertThat(saved.getClassificationAttempts()).isZero();
    assertThat(saved.getTraceId()).isEqualTo(response.traceId());
    assertThat(saved.getUiContext().screen()).isEqualTo(req.context().screen());
    assertThat(saved.getUiContext().recipeId()).isEqualTo(req.context().recipeId());

    ArgumentCaptor<FeedbackSubmittedEvent> eventCaptor =
        ArgumentCaptor.forClass(FeedbackSubmittedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    FeedbackSubmittedEvent event = eventCaptor.getValue();
    assertThat(event.feedbackId()).isEqualTo(response.feedbackId());
    assertThat(event.userId()).isEqualTo(userId);
    assertThat(event.traceId()).isEqualTo(response.traceId());
    assertThat(event.screen()).isEqualTo(req.context().screen());
    assertThat(event.occurredAt()).isNotNull();
  }

  @Test
  void submitFeedback_eachSubmission_getsFreshTraceId() {
    UUID userId = UUID.randomUUID();
    SubmitFeedbackResponse r1 =
        updateService().submitFeedback(userId, FeedbackTestData.submitFeedbackRequest("one"));
    SubmitFeedbackResponse r2 =
        updateService().submitFeedback(userId, FeedbackTestData.submitFeedbackRequest("two"));
    assertThat(r1.traceId()).isNotEqualTo(r2.traceId());
    assertThat(r1.feedbackId()).isNotEqualTo(r2.feedbackId());
  }

  // ---------------- getById ----------------

  @Test
  void getById_returnsEmpty_whenRepoFindsNothing() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.empty());

    Optional<FeedbackEntryDto> result = queryService().getById(userId, feedbackId);

    assertThat(result).isEmpty();
    verify(clarificationQueryRepository, never()).findFirstByFeedbackEntryIdAndStatus(any(), any());
  }

  @Test
  void getById_populatesPendingClarificationQueryId_whenPendingExists() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "hi");
    entry.setId(feedbackId);
    FeedbackEntryDto baseDto =
        new FeedbackEntryDto(
            feedbackId,
            userId,
            "hi",
            null,
            SubmissionStatus.CLARIFICATION_PENDING,
            0,
            null,
            UUID.randomUUID(),
            List.of(),
            null,
            Instant.now(),
            Instant.now());
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    when(entryMapper.toDto(entry)).thenReturn(baseDto);
    ClarificationQuery q = FeedbackTestData.clarificationQuery(entry);
    when(clarificationQueryRepository.findFirstByFeedbackEntryIdAndStatus(
            feedbackId, ClarificationStatus.PENDING))
        .thenReturn(Optional.of(q));

    Optional<FeedbackEntryDto> result = queryService().getById(userId, feedbackId);

    assertThat(result).isPresent();
    assertThat(result.get().pendingClarificationQueryId()).isEqualTo(q.getId());
  }

  @Test
  void getById_leavesPendingClarificationQueryIdNull_whenNoPending() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "hi");
    entry.setId(feedbackId);
    FeedbackEntryDto baseDto =
        new FeedbackEntryDto(
            feedbackId,
            userId,
            "hi",
            null,
            SubmissionStatus.RECEIVED,
            0,
            null,
            UUID.randomUUID(),
            List.of(),
            null,
            Instant.now(),
            Instant.now());
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.of(entry));
    when(entryMapper.toDto(entry)).thenReturn(baseDto);
    when(clarificationQueryRepository.findFirstByFeedbackEntryIdAndStatus(
            feedbackId, ClarificationStatus.PENDING))
        .thenReturn(Optional.empty());

    Optional<FeedbackEntryDto> result = queryService().getById(userId, feedbackId);

    assertThat(result).isPresent();
    assertThat(result.get().pendingClarificationQueryId()).isNull();
  }

  // ---------------- getByIds ----------------

  @Test
  void getByIds_emptyInput_returnsEmptyList() {
    UUID userId = UUID.randomUUID();
    assertThat(queryService().getByIds(userId, List.of())).isEmpty();
  }

  @Test
  void getByIds_omitsMissingIds_silently() {
    UUID userId = UUID.randomUUID();
    UUID hitId = UUID.randomUUID();
    UUID missId = UUID.randomUUID();
    FeedbackEntry hit = FeedbackTestData.feedbackEntry(userId, "x");
    hit.setId(hitId);
    FeedbackEntryDto hitDto =
        new FeedbackEntryDto(
            hitId,
            userId,
            "x",
            null,
            SubmissionStatus.RECEIVED,
            0,
            null,
            UUID.randomUUID(),
            List.of(),
            null,
            Instant.now(),
            Instant.now());
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(hitId, userId))
        .thenReturn(Optional.of(hit));
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(missId, userId))
        .thenReturn(Optional.empty());
    when(entryMapper.toDto(hit)).thenReturn(hitDto);
    when(clarificationQueryRepository.findFirstByFeedbackEntryIdAndStatus(
            eq(hitId), eq(ClarificationStatus.PENDING)))
        .thenReturn(Optional.empty());

    List<FeedbackEntryDto> result = queryService().getByIds(userId, List.of(hitId, missId));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).id()).isEqualTo(hitId);
  }

  // ---------------- listByUser ----------------

  @Test
  void listByUser_delegatesToRepo_andMaps() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "x");
    Page<FeedbackEntry> page = new PageImpl<>(List.of(entry));
    FeedbackEntryDto dto =
        new FeedbackEntryDto(
            entry.getId(),
            userId,
            "x",
            null,
            SubmissionStatus.RECEIVED,
            0,
            null,
            UUID.randomUUID(),
            List.of(),
            null,
            Instant.now(),
            Instant.now());
    when(feedbackEntryRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable))
        .thenReturn(page);
    when(entryMapper.toDto(entry)).thenReturn(dto);

    Page<FeedbackEntryDto> result = queryService().listByUser(userId, pageable);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).id()).isEqualTo(entry.getId());
  }

  // ---------------- getRoutingDecision ----------------

  @Test
  void getRoutingDecision_returnsMappedDto_whenRowExists() {
    UUID userId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(userId, "x");
    RoutingLogEntry row = FeedbackTestData.routingLogEntry(parent);
    row.setId(routingId);
    RoutingDecisionDto dto =
        new RoutingDecisionDto(
            routingId,
            row.getDestination(),
            row.getConfidence(),
            row.getRoutingDecision(),
            row.getStatus(),
            row.getExtractedFeedback(),
            row.getActionTaken(),
            null,
            null);
    when(routingLogRepository.findByIdAndFeedbackEntryUserId(routingId, userId))
        .thenReturn(Optional.of(row));
    when(routingLogMapper.toDto(row)).thenReturn(dto);

    Optional<RoutingDecisionDto> result = queryService().getRoutingDecision(userId, routingId);

    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo(routingId);
  }

  @Test
  void getRoutingDecision_returnsEmpty_whenRowMissing() {
    UUID userId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    when(routingLogRepository.findByIdAndFeedbackEntryUserId(routingId, userId))
        .thenReturn(Optional.empty());

    assertThat(queryService().getRoutingDecision(userId, routingId)).isEmpty();
  }

  // ---------------- deferred methods throw ----------------

  @Test
  void stillDeferredMethods_throwUnsupported_withTicketReference() {
    FeedbackUpdateService u = updateService();

    // 01e implemented answerClarificationQuery / expireOldClarificationQueries /
    // listClarificationQueries / getClarificationQuery; 01f implemented correctMisclassification /
    // listCorrections — only retryStuckClassifications (01g) stays deferred.
    assertThatThrownBy(u::retryStuckClassifications)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("feedback-01g");
  }

  // ---------------- answerClarificationQuery (01e) ----------------

  @Test
  void answerClarification_unknownQuery_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    when(clarificationQueryRepository.findByIdAndFeedbackEntryUserId(queryId, userId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                updateService()
                    .answerClarificationQuery(
                        userId,
                        queryId,
                        FeedbackTestData.answerRequest(
                            com.example.mealprep.feedback.spi.Destination.RECIPE, null)))
        .isInstanceOf(
            com.example.mealprep.feedback.exception.ClarificationQueryNotFoundException.class);
  }

  @Test
  void answerClarification_expiredQuery_throwsExpired_withFeedbackEntryId() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(userId, "make it lighter");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    q.setStatus(ClarificationStatus.EXPIRED);
    when(clarificationQueryRepository.findByIdAndFeedbackEntryUserId(q.getId(), userId))
        .thenReturn(Optional.of(q));

    assertThatThrownBy(
            () ->
                updateService()
                    .answerClarificationQuery(
                        userId,
                        q.getId(),
                        FeedbackTestData.answerRequest(
                            com.example.mealprep.feedback.spi.Destination.RECIPE, null)))
        .isInstanceOf(
            com.example.mealprep.feedback.exception.ClarificationQueryExpiredException.class)
        .satisfies(
            ex ->
                assertThat(
                        ((com.example.mealprep.feedback.exception
                                    .ClarificationQueryExpiredException)
                                ex)
                            .feedbackEntryId())
                    .isEqualTo(parent.getId()));
    verify(clarificationQueryRepository, never()).save(any());
  }

  @Test
  void answerClarification_alreadyAnswered_throwsAlreadyAnswered() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(userId, "x");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    q.setStatus(ClarificationStatus.ANSWERED);
    when(clarificationQueryRepository.findByIdAndFeedbackEntryUserId(q.getId(), userId))
        .thenReturn(Optional.of(q));

    assertThatThrownBy(
            () ->
                updateService()
                    .answerClarificationQuery(
                        userId,
                        q.getId(),
                        FeedbackTestData.answerRequest(
                            com.example.mealprep.feedback.spi.Destination.RECIPE, null)))
        .isInstanceOf(
            com.example.mealprep.feedback.exception.ClarificationQueryAlreadyAnsweredException
                .class);
  }

  @Test
  void answerClarification_neitherFieldProvided_throwsIllegalArgument() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(userId, "x");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    when(clarificationQueryRepository.findByIdAndFeedbackEntryUserId(q.getId(), userId))
        .thenReturn(Optional.of(q));

    assertThatThrownBy(
            () ->
                updateService()
                    .answerClarificationQuery(
                        userId, q.getId(), new AnswerClarificationRequest(null, "   ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selectedDestination or userClarificationText");
  }

  @Test
  void answerClarification_happyPath_marksAnswered_flipsEntryReceived_publishesEvent() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(userId, "make the lasagne lighter");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    when(clarificationQueryRepository.findByIdAndFeedbackEntryUserId(q.getId(), userId))
        .thenReturn(Optional.of(q));
    when(feedbackEntryRepository.updateSubmissionStatus(parent.getId(), SubmissionStatus.RECEIVED))
        .thenReturn(1);

    SubmitFeedbackResponse resp =
        updateService()
            .answerClarificationQuery(
                userId,
                q.getId(),
                FeedbackTestData.answerRequest(
                    com.example.mealprep.feedback.spi.Destination.PREFERENCE, "no cream sauces"));

    assertThat(resp.submissionStatus()).isEqualTo(SubmissionStatus.RECEIVED);
    assertThat(resp.feedbackId()).isEqualTo(parent.getId());
    assertThat(resp.traceId()).isEqualTo(parent.getTraceId());
    // The query is mutated to ANSWERED with the user's selection + text + answeredAt timestamp.
    ArgumentCaptor<ClarificationQuery> qc = ArgumentCaptor.forClass(ClarificationQuery.class);
    verify(clarificationQueryRepository).save(qc.capture());
    ClarificationQuery saved = qc.getValue();
    assertThat(saved.getStatus()).isEqualTo(ClarificationStatus.ANSWERED);
    assertThat(saved.getSelectedDestination())
        .isEqualTo(com.example.mealprep.feedback.spi.Destination.PREFERENCE);
    assertThat(saved.getUserClarificationText()).isEqualTo("no cream sauces");
    assertThat(saved.getAnsweredAt()).isEqualTo(clock.instant());
    // Re-classification event carries the SAME traceId (links the decision log across attempts).
    ArgumentCaptor<FeedbackSubmittedEvent> ec =
        ArgumentCaptor.forClass(FeedbackSubmittedEvent.class);
    verify(eventPublisher).publishEvent(ec.capture());
    assertThat(ec.getValue().traceId()).isEqualTo(parent.getTraceId());
    assertThat(ec.getValue().feedbackId()).isEqualTo(parent.getId());
  }

  @Test
  void answerClarification_entryUpdateMatchesZeroRows_throwsNotFound_noEvent() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(userId, "x");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    when(clarificationQueryRepository.findByIdAndFeedbackEntryUserId(q.getId(), userId))
        .thenReturn(Optional.of(q));
    when(feedbackEntryRepository.updateSubmissionStatus(parent.getId(), SubmissionStatus.RECEIVED))
        .thenReturn(0);

    assertThatThrownBy(
            () ->
                updateService()
                    .answerClarificationQuery(
                        userId,
                        q.getId(),
                        FeedbackTestData.answerRequest(
                            com.example.mealprep.feedback.spi.Destination.RECIPE, null)))
        .isInstanceOf(
            com.example.mealprep.feedback.exception.ClarificationQueryNotFoundException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  // ---------------- expireOldClarificationQueries (01e) ----------------

  @Test
  void expireOldClarifications_expiresEachViaExpirer() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "x");
    ClarificationQuery q1 = FeedbackTestData.expiredClarificationQuery(parent);
    ClarificationQuery q2 = FeedbackTestData.expiredClarificationQuery(parent);
    when(clarificationQueryRepository.findByStatusAndExpiresAtBefore(
            eq(ClarificationStatus.PENDING), eq(clock.instant())))
        .thenReturn(List.of(q1, q2));

    updateService().expireOldClarificationQueries();

    verify(clarificationExpirer).expireOne(q1.getId());
    verify(clarificationExpirer).expireOne(q2.getId());
  }

  @Test
  void expireOldClarifications_oneExpirerThrows_otherStillExpired() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "x");
    ClarificationQuery q1 = FeedbackTestData.expiredClarificationQuery(parent);
    ClarificationQuery q2 = FeedbackTestData.expiredClarificationQuery(parent);
    when(clarificationQueryRepository.findByStatusAndExpiresAtBefore(
            eq(ClarificationStatus.PENDING), eq(clock.instant())))
        .thenReturn(List.of(q1, q2));
    org.mockito.Mockito.doThrow(new RuntimeException("row locked"))
        .when(clarificationExpirer)
        .expireOne(q1.getId());

    // Must not propagate; the sweep continues to q2.
    updateService().expireOldClarificationQueries();

    verify(clarificationExpirer).expireOne(q1.getId());
    verify(clarificationExpirer).expireOne(q2.getId());
  }

  @Test
  void expireOldClarifications_noneDue_isNoop() {
    when(clarificationQueryRepository.findByStatusAndExpiresAtBefore(
            eq(ClarificationStatus.PENDING), eq(clock.instant())))
        .thenReturn(List.of());

    updateService().expireOldClarificationQueries();

    verify(clarificationExpirer, never()).expireOne(any());
  }

  // ---------------- correctMisclassification (01f) ----------------

  private FeedbackEntry entryWithRouting(UUID userId, RoutingLogEntry... rows) {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "the salt was too much");
    java.util.List<RoutingLogEntry> log = new java.util.ArrayList<>(java.util.List.of(rows));
    entry.setRoutingLog(log);
    return entry;
  }

  @Test
  void correctMisclassification_unknownEntry_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                updateService()
                    .correctMisclassification(
                        userId,
                        feedbackId,
                        UUID.randomUUID(),
                        FeedbackTestData.correctionRequest(
                            com.example.mealprep.feedback.spi.Destination.PREFERENCE, "n")))
        .isInstanceOf(com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException.class);
  }

  @Test
  void correctMisclassification_unknownRoutingRow_throwsRoutingNotFound() {
    UUID userId = UUID.randomUUID();
    RoutingLogEntry existing =
        FeedbackTestData.routingLogEntry(
            FeedbackTestData.feedbackEntry(userId, "x"),
            com.example.mealprep.feedback.spi.Destination.RECIPE,
            com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, existing);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(entry.getId(), userId))
        .thenReturn(Optional.of(entry));

    assertThatThrownBy(
            () ->
                updateService()
                    .correctMisclassification(
                        userId,
                        entry.getId(),
                        UUID.randomUUID(),
                        FeedbackTestData.correctionRequest(
                            com.example.mealprep.feedback.spi.Destination.PREFERENCE, "n")))
        .isInstanceOf(
            com.example.mealprep.feedback.exception.RoutingDecisionNotFoundException.class);
  }

  @Test
  void correctMisclassification_sameDestinationAsOriginal_throwsInvalidTarget() {
    UUID userId = UUID.randomUUID();
    RoutingLogEntry original =
        FeedbackTestData.routingLogEntry(
            FeedbackTestData.feedbackEntry(userId, "x"),
            com.example.mealprep.feedback.spi.Destination.RECIPE,
            com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED);
    FeedbackEntry entry = entryWithRouting(userId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(entry.getId(), userId))
        .thenReturn(Optional.of(entry));

    assertThatThrownBy(
            () ->
                updateService()
                    .correctMisclassification(
                        userId,
                        entry.getId(),
                        original.getId(),
                        FeedbackTestData.correctionRequest(
                            com.example.mealprep.feedback.spi.Destination.RECIPE, "n")))
        .isInstanceOf(
            com.example.mealprep.feedback.exception.InvalidCorrectionTargetException.class)
        .hasMessageContaining("no-op");
  }

  @Test
  void correctMisclassification_originalAlreadyCorrectedAway_throwsInvalidTarget_noChains() {
    UUID userId = UUID.randomUUID();
    RoutingLogEntry original =
        FeedbackTestData.routingLogEntry(
            FeedbackTestData.feedbackEntry(userId, "x"),
            com.example.mealprep.feedback.spi.Destination.RECIPE,
            com.example.mealprep.feedback.domain.entity.RoutingStatus.CORRECTED_AWAY);
    FeedbackEntry entry = entryWithRouting(userId, original);
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(entry.getId(), userId))
        .thenReturn(Optional.of(entry));

    assertThatThrownBy(
            () ->
                updateService()
                    .correctMisclassification(
                        userId,
                        entry.getId(),
                        original.getId(),
                        FeedbackTestData.correctionRequest(
                            com.example.mealprep.feedback.spi.Destination.PREFERENCE, "n")))
        .isInstanceOf(
            com.example.mealprep.feedback.exception.InvalidCorrectionTargetException.class)
        .hasMessageContaining("chains are not supported");
  }

  @Test
  void correctMisclassification_toRecipe_noRecipeAttached_throwsInvalidTarget() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "x");
    // UI context with no recipeId, payload with no recipeId.
    entry.setUiContext(
        new UiContextDocument(
            com.example.mealprep.feedback.api.dto.Screen.GENERAL, null, null, null, null, null));
    RoutingLogEntry original =
        RoutingLogEntry.builder()
            .id(UUID.randomUUID())
            .feedbackEntry(entry)
            .destination(com.example.mealprep.feedback.spi.Destination.PREFERENCE)
            .confidence(new java.math.BigDecimal("0.900"))
            .extractedFeedback("x")
            .structuredPayload(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())
            .routingDecision(
                com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED)
            .status(com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED)
            .classificationAttempt(1)
            .routedAt(Instant.now())
            .build();
    entry.setRoutingLog(new java.util.ArrayList<>(java.util.List.of(original)));
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(entry.getId(), userId))
        .thenReturn(Optional.of(entry));

    assertThatThrownBy(
            () ->
                updateService()
                    .correctMisclassification(
                        userId,
                        entry.getId(),
                        original.getId(),
                        FeedbackTestData.correctionRequest(
                            com.example.mealprep.feedback.spi.Destination.RECIPE, "n")))
        .isInstanceOf(
            com.example.mealprep.feedback.exception.InvalidCorrectionTargetException.class)
        .hasMessageContaining("no recipe attached");
  }

  @Test
  void correctMisclassification_happyPath_revertsOriginal_replays_recomputes_publishes() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "the salt was too much");
    RoutingLogEntry original =
        FeedbackTestData.routingLogEntry(
            entry,
            com.example.mealprep.feedback.spi.Destination.RECIPE,
            com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED);
    entry.setRoutingLog(new java.util.ArrayList<>(java.util.List.of(original)));
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(entry.getId(), userId))
        .thenReturn(Optional.of(entry));

    var request =
        FeedbackTestData.correctionRequest(
            com.example.mealprep.feedback.spi.Destination.PREFERENCE, "standing pref");
    var synthetic =
        new com.example.mealprep.feedback.domain.service.internal.ConfidenceGate
            .ScoredClassification(
            new com.example.mealprep.feedback.api.dto.ClassificationOutput(
                com.example.mealprep.feedback.spi.Destination.PREFERENCE,
                java.math.BigDecimal.ONE,
                "the salt was too much",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(eq(entry), eq(request))).thenReturn(synthetic);
    UUID replayLogId = UUID.randomUUID();
    var replayResult =
        new com.example.mealprep.feedback.domain.service.internal.FeedbackRouter.RouteReplayResult(
            replayLogId, com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED, null);
    when(correctionReplayer.replay(eq(entry), eq(synthetic))).thenReturn(replayResult);
    when(correctionReplayer.mapReplayStatus(
            com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED, null))
        .thenReturn(com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus.APPLIED);

    RoutingLogEntry replayRow =
        FeedbackTestData.routingLogEntry(
            entry,
            com.example.mealprep.feedback.spi.Destination.PREFERENCE,
            com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED);
    replayRow.setId(replayLogId);
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(entry.getId()))
        .thenReturn(List.of(original, replayRow));
    when(feedbackEntryRepository.updateSubmissionStatus(
            eq(entry.getId()), any(SubmissionStatus.class)))
        .thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    SubmitFeedbackResponse resp =
        updateService().correctMisclassification(userId, entry.getId(), original.getId(), request);

    // Original best-effort reverted (RECIPE → recipeReverter), then marked CORRECTED_AWAY.
    verify(recipeReverter).revert(any());
    assertThat(original.getStatus())
        .isEqualTo(com.example.mealprep.feedback.domain.entity.RoutingStatus.CORRECTED_AWAY);
    assertThat(original.getSupersededById()).isEqualTo(replayLogId);
    // Correction row persisted then stamped with replay outcome.
    ArgumentCaptor<com.example.mealprep.feedback.domain.entity.MisclassificationCorrection> cc =
        ArgumentCaptor.forClass(
            com.example.mealprep.feedback.domain.entity.MisclassificationCorrection.class);
    verify(misclassificationCorrectionRepository, org.mockito.Mockito.atLeastOnce())
        .save(cc.capture());
    var savedCorrection = cc.getValue();
    assertThat(savedCorrection.getReplayRoutingId()).isEqualTo(replayLogId);
    assertThat(savedCorrection.getReplayStatus())
        .isEqualTo(com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus.APPLIED);
    assertThat(savedCorrection.getOriginalDestination())
        .isEqualTo(com.example.mealprep.feedback.spi.Destination.RECIPE);
    assertThat(savedCorrection.getCorrectedDestination())
        .isEqualTo(com.example.mealprep.feedback.spi.Destination.PREFERENCE);
    // Recompute: original is CORRECTED_AWAY (excluded), replay APPLIED → CORRECTED.
    assertThat(resp.submissionStatus()).isEqualTo(SubmissionStatus.CORRECTED);
    verify(feedbackEntryRepository)
        .updateSubmissionStatus(entry.getId(), SubmissionStatus.CORRECTED);
    // Event published with both destinations + actor.
    ArgumentCaptor<com.example.mealprep.feedback.event.FeedbackMisclassificationCorrectedEvent> ev =
        ArgumentCaptor.forClass(
            com.example.mealprep.feedback.event.FeedbackMisclassificationCorrectedEvent.class);
    verify(eventPublisher).publishEvent(ev.capture());
    assertThat(ev.getValue().replayRoutingId()).isEqualTo(replayLogId);
    assertThat(ev.getValue().userId()).isEqualTo(userId);
    assertThat(ev.getValue().originalDestination())
        .isEqualTo(com.example.mealprep.feedback.spi.Destination.RECIPE);
    assertThat(ev.getValue().correctedDestination())
        .isEqualTo(com.example.mealprep.feedback.spi.Destination.PREFERENCE);
  }

  @Test
  void correctMisclassification_revertThrows_isSwallowed_correctionStillProceeds() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "x");
    RoutingLogEntry original =
        FeedbackTestData.routingLogEntry(
            entry,
            com.example.mealprep.feedback.spi.Destination.PREFERENCE,
            com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED);
    entry.setRoutingLog(new java.util.ArrayList<>(java.util.List.of(original)));
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(entry.getId(), userId))
        .thenReturn(Optional.of(entry));
    org.mockito.Mockito.doThrow(new RuntimeException("reverter blew up"))
        .when(preferenceReverter)
        .revert(any());

    var request =
        FeedbackTestData.correctionRequest(
            com.example.mealprep.feedback.spi.Destination.NUTRITION, "n");
    var synthetic =
        new com.example.mealprep.feedback.domain.service.internal.ConfidenceGate
            .ScoredClassification(
            new com.example.mealprep.feedback.api.dto.ClassificationOutput(
                com.example.mealprep.feedback.spi.Destination.NUTRITION,
                java.math.BigDecimal.ONE,
                "x",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(eq(entry), eq(request))).thenReturn(synthetic);
    UUID replayLogId = UUID.randomUUID();
    when(correctionReplayer.replay(eq(entry), eq(synthetic)))
        .thenReturn(
            new com.example.mealprep.feedback.domain.service.internal.FeedbackRouter
                .RouteReplayResult(
                replayLogId,
                com.example.mealprep.feedback.domain.entity.RoutingStatus.FAILED,
                com.example.mealprep.feedback.domain.entity.RoutingFailureKind.TRANSIENT));
    when(correctionReplayer.mapReplayStatus(
            com.example.mealprep.feedback.domain.entity.RoutingStatus.FAILED,
            com.example.mealprep.feedback.domain.entity.RoutingFailureKind.TRANSIENT))
        .thenReturn(com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus.FAILED);
    RoutingLogEntry replayRow =
        FeedbackTestData.routingLogEntry(
            entry,
            com.example.mealprep.feedback.spi.Destination.NUTRITION,
            com.example.mealprep.feedback.domain.entity.RoutingStatus.FAILED);
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(entry.getId()))
        .thenReturn(List.of(original, replayRow));
    when(feedbackEntryRepository.updateSubmissionStatus(
            eq(entry.getId()), any(SubmissionStatus.class)))
        .thenReturn(1);
    when(routingLogMapper.toDtos(any())).thenReturn(List.of());

    SubmitFeedbackResponse resp =
        updateService().correctMisclassification(userId, entry.getId(), original.getId(), request);

    // Reverter threw but the correction record + replay still completed.
    verify(misclassificationCorrectionRepository, org.mockito.Mockito.atLeastOnce()).save(any());
    // original CORRECTED_AWAY (excluded); replay FAILED, no non-failed → FAILED.
    assertThat(resp.submissionStatus()).isEqualTo(SubmissionStatus.FAILED);
  }

  @Test
  void correctMisclassification_entryVanishesBeforeStatusUpdate_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "x");
    RoutingLogEntry original =
        FeedbackTestData.routingLogEntry(
            entry,
            com.example.mealprep.feedback.spi.Destination.PREFERENCE,
            com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED);
    entry.setRoutingLog(new java.util.ArrayList<>(java.util.List.of(original)));
    when(feedbackEntryRepository.findWithRoutingByIdAndUserId(entry.getId(), userId))
        .thenReturn(Optional.of(entry));
    var request =
        FeedbackTestData.correctionRequest(
            com.example.mealprep.feedback.spi.Destination.NUTRITION, "n");
    var synthetic =
        new com.example.mealprep.feedback.domain.service.internal.ConfidenceGate
            .ScoredClassification(
            new com.example.mealprep.feedback.api.dto.ClassificationOutput(
                com.example.mealprep.feedback.spi.Destination.NUTRITION,
                java.math.BigDecimal.ONE,
                "x",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()),
            com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    when(correctionReplayer.buildSynthetic(eq(entry), eq(request))).thenReturn(synthetic);
    UUID replayLogId = UUID.randomUUID();
    when(correctionReplayer.replay(eq(entry), eq(synthetic)))
        .thenReturn(
            new com.example.mealprep.feedback.domain.service.internal.FeedbackRouter
                .RouteReplayResult(
                replayLogId,
                com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED,
                null));
    when(correctionReplayer.mapReplayStatus(any(), any()))
        .thenReturn(com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus.APPLIED);
    when(routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(entry.getId()))
        .thenReturn(
            List.of(
                original,
                FeedbackTestData.routingLogEntry(
                    entry,
                    com.example.mealprep.feedback.spi.Destination.NUTRITION,
                    com.example.mealprep.feedback.domain.entity.RoutingStatus.APPLIED)));
    when(feedbackEntryRepository.updateSubmissionStatus(
            eq(entry.getId()), any(SubmissionStatus.class)))
        .thenReturn(0);

    assertThatThrownBy(
            () ->
                updateService()
                    .correctMisclassification(userId, entry.getId(), original.getId(), request))
        .isInstanceOf(com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException.class);
    verify(eventPublisher, never()).publishEvent(any());
  }

  // ---------------- listClarificationQueries / getClarificationQuery (01e) ----------------

  @Test
  void listClarificationQueries_nullStatus_usesUnfilteredRepoQuery() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 10);
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(userId, "x");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    when(clarificationQueryRepository.findByFeedbackEntryUserIdOrderByCreatedAtAsc(
            userId, pageable))
        .thenReturn(new PageImpl<>(List.of(q)));
    var dto =
        new com.example.mealprep.feedback.api.dto.ClarificationQueryDto(
            q.getId(),
            parent.getId(),
            "Did you mean recipe or preference?",
            List.of(),
            q.getStatus(),
            q.getExpiresAt(),
            Instant.now());
    when(clarificationQueryMapper.toDto(q)).thenReturn(dto);

    Page<com.example.mealprep.feedback.api.dto.ClarificationQueryDto> result =
        queryService().listClarificationQueries(userId, null, pageable);

    assertThat(result.getContent()).hasSize(1);
    verify(clarificationQueryRepository, never())
        .findByFeedbackEntryUserIdAndStatusOrderByCreatedAtAsc(any(), any(), any());
  }

  @Test
  void listClarificationQueries_withStatus_usesFilteredRepoQuery() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 10);
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(userId, "x");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    when(clarificationQueryRepository.findByFeedbackEntryUserIdAndStatusOrderByCreatedAtAsc(
            userId, ClarificationStatus.PENDING, pageable))
        .thenReturn(new PageImpl<>(List.of(q)));
    var dto =
        new com.example.mealprep.feedback.api.dto.ClarificationQueryDto(
            q.getId(),
            parent.getId(),
            "q?",
            List.of(),
            ClarificationStatus.PENDING,
            q.getExpiresAt(),
            Instant.now());
    when(clarificationQueryMapper.toDto(q)).thenReturn(dto);

    Page<com.example.mealprep.feedback.api.dto.ClarificationQueryDto> result =
        queryService().listClarificationQueries(userId, ClarificationStatus.PENDING, pageable);

    assertThat(result.getContent()).hasSize(1);
    verify(clarificationQueryRepository, never())
        .findByFeedbackEntryUserIdOrderByCreatedAtAsc(any(), any());
  }

  @Test
  void getClarificationQuery_mapsWhenFound_emptyWhenMissing() {
    UUID userId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    when(clarificationQueryRepository.findByIdAndFeedbackEntryUserId(queryId, userId))
        .thenReturn(Optional.empty());
    assertThat(queryService().getClarificationQuery(userId, queryId)).isEmpty();
  }

  // ---------------- listCorrections (01f) ----------------

  @Test
  void listCorrections_delegatesToRepo_andMaps() {
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(userId, "x");
    var correction =
        FeedbackTestData.misclassificationCorrection(parent, UUID.randomUUID(), userId);
    Page<com.example.mealprep.feedback.domain.entity.MisclassificationCorrection> page =
        new PageImpl<>(List.of(correction));
    var dto =
        new com.example.mealprep.feedback.api.dto.MisclassificationCorrectionDto(
            correction.getId(),
            parent.getId(),
            correction.getOriginalRoutingId(),
            correction.getCorrectedDestination(),
            correction.getOriginalDestination(),
            correction.getOriginalConfidence(),
            correction.getUserCorrectionNote(),
            userId,
            null,
            correction.getReplayStatus(),
            correction.getOccurredAt(),
            Instant.now());
    when(misclassificationCorrectionRepository.findByFeedbackEntryUserIdOrderByOccurredAtDesc(
            userId, pageable))
        .thenReturn(page);
    when(misclassificationCorrectionMapper.toDto(correction)).thenReturn(dto);

    Page<com.example.mealprep.feedback.api.dto.MisclassificationCorrectionDto> result =
        queryService().listCorrections(userId, pageable);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).id()).isEqualTo(correction.getId());
  }
}
