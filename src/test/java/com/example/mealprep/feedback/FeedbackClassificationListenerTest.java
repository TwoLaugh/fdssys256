package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.service.internal.ConfidenceGate;
import com.example.mealprep.feedback.domain.service.internal.FeedbackClassificationContext;
import com.example.mealprep.feedback.domain.service.internal.FeedbackClassificationListener;
import com.example.mealprep.feedback.domain.service.internal.FeedbackClassifier;
import com.example.mealprep.feedback.domain.service.internal.FeedbackRouter;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests covering the six outcome paths of the classification listener flow. Each test wires
 * synthetic stubs and asserts (a) entity-state writes, (b) clarification-row writes, (c)
 * event-publish behaviour, (d) router hand-off.
 */
class FeedbackClassificationListenerTest {

  private FeedbackClassifier classifier;
  private ConfidenceGate gate;
  private FeedbackEntryRepository entryRepository;
  private ClarificationQueryRepository clarificationRepository;
  private FeedbackRouter router;
  private ApplicationEventPublisher events;
  private ObjectMapper objectMapper;
  private Clock clock;

  private FeedbackClassificationListener listener;

  private UUID feedbackId;
  private UUID userId;
  private UUID traceId;
  private FeedbackEntry entry;

  @BeforeEach
  void setUp() {
    classifier = Mockito.mock(FeedbackClassifier.class);
    gate = new ConfidenceGate();
    entryRepository = Mockito.mock(FeedbackEntryRepository.class);
    clarificationRepository = Mockito.mock(ClarificationQueryRepository.class);
    router = Mockito.mock(FeedbackRouter.class);
    events = Mockito.mock(ApplicationEventPublisher.class);
    objectMapper = new ObjectMapper();
    clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), ZoneOffset.UTC);

    listener =
        new FeedbackClassificationListener(
            classifier,
            gate,
            entryRepository,
            clarificationRepository,
            router,
            events,
            inlineRequiresNewTemplate(),
            objectMapper,
            clock);

    feedbackId = UUID.randomUUID();
    userId = UUID.randomUUID();
    traceId = UUID.randomUUID();
    entry =
        FeedbackEntry.builder()
            .id(feedbackId)
            .userId(userId)
            .traceId(traceId)
            .text("the salt was too much")
            .uiContext(
                new UiContextDocument(Screen.RECIPE_DETAIL, UUID.randomUUID(), 1, null, null, null))
            .submissionStatus(SubmissionStatus.RECEIVED)
            .classificationAttempts(0)
            .routingLog(new ArrayList<>())
            .build();

    when(entryRepository.findById(feedbackId)).thenReturn(Optional.of(entry));
    when(entryRepository.save(any(FeedbackEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0, FeedbackEntry.class));
    // The listener now flips submission_status via native UPDATEs (round-8 retro: avoids the
    // @Version race with the publisher's persistence context). For the Mockito-backed unit test,
    // stub the new repo methods to simulate the in-place mutation on the test entity + return
    // rowcount 1 so the listener's "rows == 0 → NotFound" guard is not tripped.
    when(entryRepository.updateSubmissionStatusAndIncrementAttempts(
            org.mockito.ArgumentMatchers.eq(feedbackId), any(SubmissionStatus.class)))
        .thenAnswer(
            inv -> {
              entry.setSubmissionStatus(inv.getArgument(1, SubmissionStatus.class));
              entry.setClassificationAttempts(entry.getClassificationAttempts() + 1);
              return 1;
            });
    when(entryRepository.updateSubmissionStatusAndDecrementAttempts(
            org.mockito.ArgumentMatchers.eq(feedbackId), any(SubmissionStatus.class)))
        .thenAnswer(
            inv -> {
              entry.setSubmissionStatus(inv.getArgument(1, SubmissionStatus.class));
              entry.setClassificationAttempts(Math.max(0, entry.getClassificationAttempts() - 1));
              return 1;
            });
    when(entryRepository.updateSubmissionStatusAndLastClassifiedAt(
            org.mockito.ArgumentMatchers.eq(feedbackId),
            any(SubmissionStatus.class),
            any(java.time.Instant.class)))
        .thenAnswer(
            inv -> {
              entry.setSubmissionStatus(inv.getArgument(1, SubmissionStatus.class));
              entry.setLastClassifiedAt(inv.getArgument(2, java.time.Instant.class));
              return 1;
            });
  }

  @Test
  void happyPath_allHighConfidence_marksClassified_handsOffToRouter_noEventFromListener() {
    when(classifier.classify(any(FeedbackClassificationContext.class)))
        .thenReturn(
            result(output(Destination.RECIPE, "0.95"), output(Destination.PREFERENCE, "0.85")));

    listener.classifyEntry(feedbackId, userId, traceId);

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.CLASSIFIED);
    assertThat(entry.getLastClassifiedAt()).isEqualTo(clock.instant());
    assertThat(entry.getClassificationAttempts()).isEqualTo(1);
    verify(router, times(1)).routeAll(any(UUID.class), any());
    verify(events, never()).publishEvent(any(FeedbackProcessedEvent.class));
  }

  @Test
  void mixedAutoAndFlag_stillRoutes() {
    when(classifier.classify(any(FeedbackClassificationContext.class)))
        .thenReturn(
            result(output(Destination.RECIPE, "0.92"), output(Destination.PREFERENCE, "0.65")));

    listener.classifyEntry(feedbackId, userId, traceId);

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.CLASSIFIED);
    verify(router).routeAll(any(UUID.class), any());
  }

  @Test
  void anyBelow050_writesOneClarificationQuery_publishesEvent_entryPaused() {
    when(classifier.classify(any(FeedbackClassificationContext.class)))
        .thenReturn(
            result(
                output(Destination.RECIPE, "0.95"),
                output(Destination.PREFERENCE, "0.45"),
                output(Destination.NUTRITION, "0.30")));

    listener.classifyEntry(feedbackId, userId, traceId);

    ArgumentCaptor<ClarificationQuery> queryCaptor =
        ArgumentCaptor.forClass(ClarificationQuery.class);
    verify(clarificationRepository, times(1)).save(queryCaptor.capture());
    ClarificationQuery saved = queryCaptor.getValue();
    assertThat(saved.getStatus()).isEqualTo(ClarificationStatus.PENDING);
    assertThat(saved.getQuestionText())
        .isEqualTo(FeedbackClassificationListener.CLARIFICATION_QUESTION_TEXT);
    assertThat(saved.getExpiresAt())
        .isEqualTo(clock.instant().plus(FeedbackClassificationListener.CLARIFICATION_TTL));
    // Two options written (the two CLARIFICATION_QUEUED entries; the 0.95 entry is omitted).
    assertThat(saved.getClassifierOptionsJson().size()).isEqualTo(2);

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.CLARIFICATION_PENDING);
    assertThat(entry.getLastClassifiedAt()).isEqualTo(clock.instant());

    ArgumentCaptor<FeedbackProcessedEvent> eventCaptor =
        ArgumentCaptor.forClass(FeedbackProcessedEvent.class);
    verify(events).publishEvent(eventCaptor.capture());
    FeedbackProcessedEvent fpe = eventCaptor.getValue();
    assertThat(fpe.clarificationPending()).isTrue();
    assertThat(fpe.destinationsTouched()).isEmpty();
    assertThat(fpe.partialFailure()).isFalse();

    verify(router, never()).routeAll(any(UUID.class), any());
  }

  @Test
  void emptyClassifications_marksRouted_publishesEvent() {
    when(classifier.classify(any(FeedbackClassificationContext.class)))
        .thenReturn(
            new ClassificationResult(List.of(), new BigDecimal("0.10"), "nothing actionable"));

    listener.classifyEntry(feedbackId, userId, traceId);

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.ROUTED);
    assertThat(entry.getLastClassifiedAt()).isEqualTo(clock.instant());

    ArgumentCaptor<FeedbackProcessedEvent> eventCaptor =
        ArgumentCaptor.forClass(FeedbackProcessedEvent.class);
    verify(events).publishEvent(eventCaptor.capture());
    FeedbackProcessedEvent fpe = eventCaptor.getValue();
    assertThat(fpe.destinationsTouched()).isEmpty();
    assertThat(fpe.partialFailure()).isFalse();
    assertThat(fpe.clarificationPending()).isFalse();

    verify(router, never()).routeAll(any(UUID.class), any());
    verify(clarificationRepository, never()).save(any(ClarificationQuery.class));
  }

  @Test
  void aiUnavailable_revertsToReceived_decrementsAttempts_noEvent() {
    when(classifier.classify(any(FeedbackClassificationContext.class)))
        .thenThrow(new AiUnavailableException("upstream 503"));

    listener.classifyEntry(feedbackId, userId, traceId);

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.RECEIVED);
    // step 1 increments to 1, defer decrements back to 0.
    assertThat(entry.getClassificationAttempts()).isEqualTo(0);
    verify(events, never()).publishEvent(any());
    verify(router, never()).routeAll(any(UUID.class), any());
  }

  @Test
  void aiInvalidResponse_marksFailed_publishesDegenerateEvent() {
    when(classifier.classify(any(FeedbackClassificationContext.class)))
        .thenThrow(new AiInvalidResponseException("malformed JSON"));

    listener.classifyEntry(feedbackId, userId, traceId);

    assertThat(entry.getSubmissionStatus()).isEqualTo(SubmissionStatus.FAILED);
    assertThat(entry.getLastClassifiedAt()).isEqualTo(clock.instant());

    ArgumentCaptor<FeedbackProcessedEvent> eventCaptor =
        ArgumentCaptor.forClass(FeedbackProcessedEvent.class);
    verify(events).publishEvent(eventCaptor.capture());
    FeedbackProcessedEvent fpe = eventCaptor.getValue();
    assertThat(fpe.partialFailure()).isTrue();
    assertThat(fpe.destinationsTouched()).isEmpty();
    assertThat(fpe.clarificationPending()).isFalse();

    verify(router, never()).routeAll(any(UUID.class), any());
  }

  @Test
  void incrementsAttemptsOnFirstAttempt() {
    when(classifier.classify(any(FeedbackClassificationContext.class)))
        .thenReturn(result(output(Destination.RECIPE, "0.95")));

    listener.classifyEntry(feedbackId, userId, traceId);

    assertThat(entry.getClassificationAttempts()).isEqualTo(1);
  }

  // ---------------- helpers ----------------

  /**
   * Inline TransactionTemplate — runs callback synchronously without a real
   * PlatformTransactionManager.
   */
  private TransactionTemplate inlineRequiresNewTemplate() {
    PlatformTransactionManager noop =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        };
    TransactionTemplate t =
        new TransactionTemplate(noop) {
          @Override
          public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
          }
        };
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return t;
  }

  private static ClassificationResult result(ClassificationOutput... outputs) {
    return new ClassificationResult(List.of(outputs), new BigDecimal("0.80"), null);
  }

  private static ClassificationOutput output(Destination dest, String confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("note", dest.name());
    return new ClassificationOutput(dest, new BigDecimal(confidence), "snippet " + dest, payload);
  }
}
