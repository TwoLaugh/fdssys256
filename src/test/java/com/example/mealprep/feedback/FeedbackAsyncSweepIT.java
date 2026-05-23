package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testing.TestAiService;
import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Postgres-backed IT for the {@code retryStuckClassifications} sweep (feedback-01i), the LLD-named
 * {@code FeedbackAsyncSweepIT} (lld/feedback.md line 907).
 *
 * <p>Two contracts:
 *
 * <ul>
 *   <li><b>Stuck {@code RECEIVED} re-classified</b>: an entry that the listener reverted to {@code
 *       RECEIVED} on an {@code AiUnavailable} (modelled here by seeding the post-revert state with
 *       a backdated retry clock) re-enters the classifier on the next sweep — the sweep
 *       re-publishes {@code FeedbackSubmittedEvent} (same traceId), the now-healthy {@link
 *       TestAiService} returns a high-confidence classification, and the entry progresses.
 *   <li><b>Stuck &gt; 24h escalates to {@code FAILED}</b>: an entry whose {@code createdAt}
 *       predates the 24h escalation threshold is flipped to {@code FAILED}, {@code
 *       lastClassifiedAt} stamped, and a terminal {@code FeedbackProcessedEvent(failed=true)}
 *       published exactly once; the row stays in the table for postmortem.
 * </ul>
 *
 * <p>The {@code @Scheduled} auto-fire is pushed far out in the test profile, so the sweep is
 * invoked deterministically here. {@code createdAt} is {@code @CreatedDate}-managed, so seeds are
 * backdated via direct SQL after persistence (the time-bomb-safe equivalent of {@code Clock.fixed}
 * for an audit column the app, not the test, stamps).
 */
@SpringBootTest
@Import({TestContainersConfig.class, FeedbackAsyncSweepIT.SweepCaptureConfig.class})
@ActiveProfiles("test")
class FeedbackAsyncSweepIT {

  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private FeedbackUpdateService updateService;
  @Autowired private TestAiService testAiService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ProcessedCapture processedCapture;

  @BeforeEach
  void setUp() {
    testAiService.clear();
    processedCapture.clear();
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
  void sweep_stuckReceivedOlderThan5Min_reEntersClassifier_andProgresses() {
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION, result(output(Destination.RECIPE, "0.92")));

    UUID id = persistStuckReceived(Duration.ofMinutes(6));

    updateService.retryStuckClassifications();

    // Re-published FeedbackSubmittedEvent -> async listener flips CLASSIFYING + increments attempts
    // + runs the (healthy) classifier. Await the classification evidence rather than a transient
    // status (the real router may carry CLASSIFIED onward), mirroring FeedbackClassificationFlowIT.
    awaitCondition(
        () ->
            entryRepository
                .findById(id)
                .filter(e -> e.getLastClassifiedAt() != null)
                .filter(e -> e.getClassificationAttempts() == 1)
                .isPresent(),
        Duration.ofSeconds(30));

    FeedbackEntry reloaded = entryRepository.findById(id).orElseThrow();
    assertThat(reloaded.getClassificationAttempts()).isEqualTo(1);
    assertThat(reloaded.getLastClassifiedAt()).isNotNull();
    assertThat(reloaded.getSubmissionStatus()).isNotEqualTo(SubmissionStatus.RECEIVED);
    // The classifier actually ran (a TestAiService call was recorded for this entry).
    assertThat(testAiService.recordedCalls()).isNotEmpty();
  }

  @Test
  void sweep_freshReceivedUnder5Min_isNotSwept() {
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION, result(output(Destination.RECIPE, "0.92")));

    UUID id = persistStuckReceived(Duration.ofMinutes(2)); // under the 5-min threshold

    updateService.retryStuckClassifications();

    // No dispatch — give any erroneous async work a brief window to (not) happen.
    sleepBriefly();
    FeedbackEntry reloaded = entryRepository.findById(id).orElseThrow();
    assertThat(reloaded.getSubmissionStatus()).isEqualTo(SubmissionStatus.RECEIVED);
    assertThat(reloaded.getClassificationAttempts()).isZero();
    assertThat(reloaded.getLastClassifiedAt()).isNull();
    assertThat(testAiService.recordedCalls()).isEmpty();
  }

  @Test
  void sweep_stuckReceivedOlderThan24h_escalatesToFailed_publishesTerminalEvent() {
    UUID id = persistStuckReceived(Duration.ofHours(25)); // past the 24h escalation threshold

    updateService.retryStuckClassifications();

    awaitCondition(
        () ->
            entryRepository
                .findById(id)
                .filter(e -> e.getSubmissionStatus() == SubmissionStatus.FAILED)
                .isPresent(),
        Duration.ofSeconds(30));

    FeedbackEntry reloaded = entryRepository.findById(id).orElseThrow();
    assertThat(reloaded.getSubmissionStatus()).isEqualTo(SubmissionStatus.FAILED);
    assertThat(reloaded.getLastClassifiedAt()).isNotNull(); // stamped on escalation
    // Row stays in the table for postmortem.
    assertThat(entryRepository.findById(id)).isPresent();
    // Terminal FeedbackProcessedEvent published exactly once for this entry.
    awaitCondition(
        () -> processedCapture.events.stream().anyMatch(e -> e.feedbackId().equals(id)),
        Duration.ofSeconds(30));
    List<FeedbackProcessedEvent> forEntry =
        processedCapture.events.stream().filter(e -> e.feedbackId().equals(id)).toList();
    assertThat(forEntry).hasSize(1);
    assertThat(forEntry.get(0).partialFailure()).isTrue();
    assertThat(forEntry.get(0).clarificationPending()).isFalse();
    assertThat(forEntry.get(0).destinationsTouched()).isEmpty();
    // No classifier call — escalation never re-classifies.
    assertThat(testAiService.recordedCalls()).isEmpty();
  }

  // ---------------- helpers ----------------

  /**
   * Seeds an entry in {@code RECEIVED} and backdates its retry clock by {@code age} so it falls
   * into (or out of) the sweep window. {@code createdAt} is {@code @CreatedDate}-managed, so the
   * backdate is a direct SQL UPDATE after persistence.
   */
  private UUID persistStuckReceived(Duration age) {
    FeedbackEntry entry =
        FeedbackTestData.feedbackEntry(UUID.randomUUID(), "the salt was too much");
    entry.setSubmissionStatus(SubmissionStatus.RECEIVED);
    entry.setClassificationAttempts(0);
    entryRepository.saveAndFlush(entry);
    Instant backdated = Instant.now().minus(age).truncatedTo(ChronoUnit.MILLIS);
    // Backdate created_at AND null-out last_classified_at so COALESCE(lastClassifiedAt, createdAt)
    // resolves to the backdated created_at — a never-progressed RECEIVED entry's honest clock.
    jdbcTemplate.update(
        "UPDATE feedback_entries SET created_at = ?, last_classified_at = NULL WHERE id = ?",
        java.sql.Timestamp.from(backdated),
        entry.getId());
    return entry.getId();
  }

  private static ClassificationResult result(ClassificationOutput... outputs) {
    return new ClassificationResult(List.of(outputs), new BigDecimal("0.80"), null);
  }

  private static ClassificationOutput output(Destination dest, String confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("note", dest.name());
    return new ClassificationOutput(dest, new BigDecimal(confidence), "snippet " + dest, payload);
  }

  private static void sleepBriefly() {
    try {
      TimeUnit.MILLISECONDS.sleep(300);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
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

  // ---------------- FeedbackProcessedEvent capture ----------------

  @TestConfiguration
  static class SweepCaptureConfig {
    @Bean
    ProcessedCapture sweepProcessedCapture() {
      return new ProcessedCapture();
    }
  }

  static class ProcessedCapture {
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
