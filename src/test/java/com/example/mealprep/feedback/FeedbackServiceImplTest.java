package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.RoutingDecisionDto;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackRequest;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackResponse;
import com.example.mealprep.feedback.api.mapper.FeedbackEntryMapper;
import com.example.mealprep.feedback.api.mapper.RoutingLogMapper;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.domain.service.FeedbackQueryService;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import java.time.Instant;
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
  @Mock private FeedbackEntryMapper entryMapper;
  @Mock private RoutingLogMapper routingLogMapper;
  @Mock private ApplicationEventPublisher eventPublisher;

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
              FeedbackEntryMapper.class,
              RoutingLogMapper.class,
              ApplicationEventPublisher.class);
      ctor.setAccessible(true);
      serviceObject =
          ctor.newInstance(
              feedbackEntryRepository,
              routingLogRepository,
              clarificationQueryRepository,
              entryMapper,
              routingLogMapper,
              eventPublisher);
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
  void deferredMethods_throwUnsupported_withTicketReference() {
    UUID userId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    FeedbackUpdateService u = updateService();
    FeedbackQueryService q = queryService();

    assertThatThrownBy(() -> u.correctMisclassification(userId, id, id, /* request= */ null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("feedback-01f");
    assertThatThrownBy(() -> u.answerClarificationQuery(userId, id, /* request= */ null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("feedback-01e");
    assertThatThrownBy(u::retryStuckClassifications)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("feedback-01g");
    assertThatThrownBy(u::expireOldClarificationQueries)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("feedback-01g");
    assertThatThrownBy(
            () ->
                q.listClarificationQueries(userId, ClarificationStatus.PENDING, Pageable.unpaged()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("feedback-01e");
    assertThatThrownBy(() -> q.getClarificationQuery(userId, id))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("feedback-01e");
    assertThatThrownBy(() -> q.listCorrections(userId, Pageable.unpaged()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("feedback-01f");
  }
}
