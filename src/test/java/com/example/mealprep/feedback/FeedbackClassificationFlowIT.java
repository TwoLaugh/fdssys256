package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testing.TestAiService;
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
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full async-flow IT for the classification listener. Publishes {@link FeedbackSubmittedEvent}
 * against a pre-persisted entry, registers a canned response via {@link TestAiService}, and asserts
 * the entry transitions to the expected terminal state.
 *
 * <p>Async-race pattern: tests poll for the entity state with a bounded retry rather than {@code
 * Thread.sleep}.
 */
@SpringBootTest
@Import({TestContainersConfig.class, FeedbackClassificationFlowIT.EventCaptureConfig.class})
@ActiveProfiles("test")
class FeedbackClassificationFlowIT {

  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private ClarificationQueryRepository clarificationRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private TestAiService testAiService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FeedbackProcessedCapture processedCapture;

  @BeforeEach
  void setUp() {
    processedCapture.clear();
    testAiService.clear();
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    jdbcTemplate.update("DELETE FROM ai_call_log");
    testAiService.clear();
    processedCapture.clear();
  }

  @Test
  void happyPath_allHighConfidence_transitionsToClassified() {
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION, result(output(Destination.RECIPE, "0.92")));

    UUID feedbackId = persistEntry();

    publishAfterCommit(feedbackId);

    awaitState(feedbackId, SubmissionStatus.CLASSIFIED);

    FeedbackEntry final_ = entryRepository.findById(feedbackId).orElseThrow();
    assertThat(final_.getLastClassifiedAt()).isNotNull();
    assertThat(final_.getClassificationAttempts()).isEqualTo(1);
    // No FeedbackProcessedEvent — router (Noop) does not publish.
  }

  @Test
  void anyBelow050_transitionsToClarificationPending_andWritesQuery() {
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION,
        result(output(Destination.RECIPE, "0.95"), output(Destination.PREFERENCE, "0.30")));

    UUID feedbackId = persistEntry();
    publishAfterCommit(feedbackId);

    awaitState(feedbackId, SubmissionStatus.CLARIFICATION_PENDING);

    List<ClarificationQuery> rows = clarificationRepository.findAll();
    assertThat(rows).hasSize(1);
    ClarificationQuery row = rows.get(0);
    assertThat(row.getStatus()).isEqualTo(ClarificationStatus.PENDING);
    assertThat(row.getExpiresAt()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));

    awaitCondition(() -> !processedCapture.events.isEmpty(), Duration.ofSeconds(30));
    FeedbackProcessedEvent fpe = processedCapture.events.get(0);
    assertThat(fpe.clarificationPending()).isTrue();
    assertThat(fpe.destinationsTouched()).isEmpty();
    assertThat(fpe.partialFailure()).isFalse();
  }

  @Test
  void emptyClassifications_transitionsToRouted() {
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION,
        new ClassificationResult(List.of(), new BigDecimal("0.10"), "nothing"));

    UUID feedbackId = persistEntry();
    publishAfterCommit(feedbackId);

    awaitState(feedbackId, SubmissionStatus.ROUTED);

    awaitCondition(() -> !processedCapture.events.isEmpty(), Duration.ofSeconds(30));
    FeedbackProcessedEvent fpe = processedCapture.events.get(0);
    assertThat(fpe.clarificationPending()).isFalse();
    assertThat(fpe.partialFailure()).isFalse();
    assertThat(fpe.destinationsTouched()).isEmpty();
  }

  @Test
  void aiInvalidResponse_transitionsToFailed() {
    // Register a wrong-typed response — TestAiService throws AiInvalidResponseException for type
    // mismatch.
    testAiService.register(TaskType.FEEDBACK_CLASSIFICATION, "not a ClassificationResult");

    UUID feedbackId = persistEntry();
    publishAfterCommit(feedbackId);

    awaitState(feedbackId, SubmissionStatus.FAILED);

    awaitCondition(() -> !processedCapture.events.isEmpty(), Duration.ofSeconds(30));
    FeedbackProcessedEvent fpe = processedCapture.events.get(0);
    assertThat(fpe.partialFailure()).isTrue();
    assertThat(fpe.destinationsTouched()).isEmpty();
  }

  // ---------------- helpers ----------------

  private UUID persistEntry() {
    UUID feedbackId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    FeedbackEntry entry =
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
    return entryRepository.save(entry).getId();
  }

  @Autowired private EventPublisherHelper publisherHelper;

  private void publishAfterCommit(UUID feedbackId) {
    FeedbackEntry entry = entryRepository.findById(feedbackId).orElseThrow();
    FeedbackSubmittedEvent event =
        new FeedbackSubmittedEvent(
            feedbackId,
            entry.getUserId(),
            entry.getUiContext().screen(),
            entry.getTraceId(),
            Instant.now());
    publisherHelper.publishInTransaction(event);
  }

  private void awaitState(UUID feedbackId, SubmissionStatus expected) {
    awaitCondition(
        () -> {
          Optional<FeedbackEntry> e = entryRepository.findById(feedbackId);
          return e.isPresent() && e.get().getSubmissionStatus() == expected;
        },
        Duration.ofSeconds(30));
    FeedbackEntry e = entryRepository.findById(feedbackId).orElseThrow();
    assertThat(e.getSubmissionStatus()).isEqualTo(expected);
  }

  private static void awaitCondition(BooleanSupplier check, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (check.getAsBoolean()) {
        return;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(50);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while awaiting condition", ie);
      }
    }
    if (!check.getAsBoolean()) {
      throw new AssertionError("Timed out waiting for condition after " + timeout);
    }
  }

  private static ClassificationResult result(ClassificationOutput... outputs) {
    return new ClassificationResult(List.of(outputs), new BigDecimal("0.80"), null);
  }

  private static ClassificationOutput output(Destination dest, String confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("note", dest.name());
    return new ClassificationOutput(dest, new BigDecimal(confidence), "snippet " + dest, payload);
  }

  // ---------------- AFTER_COMMIT publisher helper ----------------

  /**
   * Publishes {@link FeedbackSubmittedEvent} from inside a transaction so the listener's {@code
   * AFTER_COMMIT} phase fires. {@code @Transactional} ensures the publication participates in a
   * transaction; the commit emits the event to the async listener.
   */
  @org.springframework.stereotype.Component
  static class EventPublisherHelper {
    private final ApplicationEventPublisher publisher;

    EventPublisherHelper(ApplicationEventPublisher publisher) {
      this.publisher = publisher;
    }

    @Transactional
    public void publishInTransaction(FeedbackSubmittedEvent event) {
      publisher.publishEvent(event);
    }
  }

  // ---------------- FeedbackProcessedEvent capture ----------------

  @TestConfiguration
  static class EventCaptureConfig {
    @Bean
    FeedbackProcessedCapture feedbackProcessedCapture() {
      return new FeedbackProcessedCapture();
    }

    @Bean
    EventPublisherHelper eventPublisherHelper(ApplicationEventPublisher publisher) {
      return new EventPublisherHelper(publisher);
    }
  }

  static class FeedbackProcessedCapture {
    final List<FeedbackProcessedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProcessed(FeedbackProcessedEvent event) {
      events.add(event);
    }

    void clear() {
      events.clear();
    }
  }
}
