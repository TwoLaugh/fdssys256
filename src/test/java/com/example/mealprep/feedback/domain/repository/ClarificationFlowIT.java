package com.example.mealprep.feedback.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testing.TestAiService;
import com.example.mealprep.feedback.api.dto.AnswerClarificationRequest;
import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
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
import java.util.UUID;
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

/**
 * End-to-end clarification round-trip: a &lt;0.5 first classification queues a {@link
 * ClarificationQuery}; the user answers it; the re-fired {@code FeedbackSubmittedEvent} drives 01c
 * to re-classify (now high-confidence) and the entry advances past CLASSIFIED. TestAiService is
 * re-registered between rounds (it returns one canned response per task type) to simulate the
 * attempt-1-low / attempt-2-high progression.
 *
 * <p>Async-race pattern: bounded poll on persisted evidence, never {@code Thread.sleep}.
 */
@SpringBootTest
@Import({TestContainersConfig.class, ClarificationFlowIT.PublisherConfig.class})
@ActiveProfiles("test")
class ClarificationFlowIT {

  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private ClarificationQueryRepository clarificationRepository;
  @Autowired private FeedbackUpdateService updateService;
  @Autowired private TestAiService testAiService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EventPublisherHelper publisherHelper;

  @BeforeEach
  void setUp() {
    testAiService.clear();
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    jdbcTemplate.update("DELETE FROM ai_call_log");
    testAiService.clear();
  }

  @Test
  void submit_lowConfidence_queuesClarification_thenAnswer_reClassifiesHighConfidence() {
    // Round 1: low confidence → clarification queued.
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION,
        result(output(Destination.RECIPE, "0.95"), output(Destination.PREFERENCE, "0.30")));

    UUID userId = UUID.randomUUID();
    FeedbackEntry entry = persistEntry(userId);
    publisherHelper.publishInTransaction(
        new FeedbackSubmittedEvent(
            entry.getId(),
            userId,
            entry.getUiContext().screen(),
            entry.getTraceId(),
            Instant.now()));

    awaitCondition(
        () ->
            entryRepository
                .findById(entry.getId())
                .filter(e -> e.getSubmissionStatus() == SubmissionStatus.CLARIFICATION_PENDING)
                .isPresent(),
        Duration.ofSeconds(30));

    List<ClarificationQuery> pending =
        clarificationRepository.findAll().stream()
            .filter(q -> q.getStatus() == ClarificationStatus.PENDING)
            .toList();
    assertThat(pending).hasSize(1);
    ClarificationQuery query = pending.get(0);

    // Round 2: re-register a high-confidence response, then answer the clarification.
    testAiService.clear();
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION, result(output(Destination.PREFERENCE, "0.92")));

    updateService.answerClarificationQuery(
        userId,
        query.getId(),
        new AnswerClarificationRequest(Destination.PREFERENCE, "a standing preference"));

    // The answer fired a fresh FeedbackSubmittedEvent → 01c re-classifies. Await the second
    // classification's evidence: attempts incremented to 2 + a fresh lastClassifiedAt.
    awaitCondition(
        () ->
            entryRepository
                .findById(entry.getId())
                .filter(e -> e.getClassificationAttempts() >= 2)
                .filter(e -> e.getLastClassifiedAt() != null)
                .isPresent(),
        Duration.ofSeconds(30));

    ClarificationQuery answered = clarificationRepository.findById(query.getId()).orElseThrow();
    assertThat(answered.getStatus()).isEqualTo(ClarificationStatus.ANSWERED);
    assertThat(answered.getSelectedDestination()).isEqualTo(Destination.PREFERENCE);

    FeedbackEntry reclassified = entryRepository.findById(entry.getId()).orElseThrow();
    assertThat(reclassified.getClassificationAttempts()).isGreaterThanOrEqualTo(2);
    // Second round was all-high-confidence → no longer CLARIFICATION_PENDING.
    assertThat(reclassified.getSubmissionStatus())
        .isNotEqualTo(SubmissionStatus.CLARIFICATION_PENDING);
  }

  // ---------------- helpers ----------------

  private FeedbackEntry persistEntry(UUID userId) {
    FeedbackEntry entry =
        FeedbackEntry.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .traceId(UUID.randomUUID())
            .text("make it lighter, and stop suggesting cream")
            .uiContext(
                new UiContextDocument(Screen.RECIPE_DETAIL, UUID.randomUUID(), 1, null, null, null))
            .submissionStatus(SubmissionStatus.RECEIVED)
            .classificationAttempts(0)
            .routingLog(new ArrayList<>())
            .build();
    return entryRepository.save(entry);
  }

  private static ClassificationResult result(ClassificationOutput... outputs) {
    return new ClassificationResult(List.of(outputs), new BigDecimal("0.80"), null);
  }

  private static ClassificationOutput output(Destination dest, String confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("note", dest.name());
    return new ClassificationOutput(dest, new BigDecimal(confidence), "snippet " + dest, payload);
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

  /**
   * Publishes {@link FeedbackSubmittedEvent} from inside a transaction so 01c's listener's {@code
   * AFTER_COMMIT} phase fires (Spring silently drops AFTER_COMMIT events published with no active
   * transaction).
   */
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

  @TestConfiguration
  static class PublisherConfig {
    @Bean
    EventPublisherHelper eventPublisherHelper(ApplicationEventPublisher publisher) {
      return new EventPublisherHelper(publisher);
    }
  }
}
