package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testing.TestAiService;
import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.example.mealprep.feedback.spi.RecipeFeedbackHandler;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full async IT: submit → classify → fan out → routing-log rows. Uses {@link TestAiService} to feed
 * the classification result and {@code @Primary} test-config beans to inject controllable bridge
 * stubs.
 *
 * <p>Async race-pattern: polls for terminal-state with a bounded retry, no {@code Thread.sleep}
 * outside the polling helper.
 */
@SpringBootTest
@Import({TestContainersConfig.class, FeedbackRoutingFlowIT.RoutingItConfig.class})
@ActiveProfiles("test")
class FeedbackRoutingFlowIT {

  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private RoutingLogRepository routingLogRepository;
  @Autowired private TestAiService testAiService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FeedbackProcessedCapture processedCapture;
  @Autowired private RecordingRecipeHandler recordingRecipe;
  @Autowired private RecordingPreferenceBridge recordingPreference;

  @BeforeEach
  void setUp() {
    processedCapture.clear();
    testAiService.clear();
    recordingRecipe.reset();
    recordingPreference.reset();
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    jdbcTemplate.update("DELETE FROM ai_call_log");
    testAiService.clear();
    processedCapture.clear();
    recordingRecipe.reset();
    recordingPreference.reset();
  }

  @Test
  void singleRecipeRoute_persistsAppliedRow_andRouted() {
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION, result(output(Destination.RECIPE, "0.92")));

    UUID feedbackId = persistEntry();
    publishAfterCommit(feedbackId);

    awaitState(feedbackId, SubmissionStatus.ROUTED);

    List<RoutingLogEntry> rows =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    assertThat(rows).hasSize(1);
    RoutingLogEntry row = rows.get(0);
    assertThat(row.getDestination()).isEqualTo(Destination.RECIPE);
    assertThat(row.getStatus()).isEqualTo(RoutingStatus.APPLIED);
    assertThat(row.getActionTaken()).isEqualTo("recipe-ok");
    assertThat(row.getCompletedAt()).isNotNull();

    awaitCondition(() -> !processedCapture.events.isEmpty(), Duration.ofSeconds(15));
    FeedbackProcessedEvent fpe = processedCapture.events.get(0);
    assertThat(fpe.destinationsTouched()).containsExactly(Destination.RECIPE);
    assertThat(fpe.partialFailure()).isFalse();
  }

  @Test
  void mixedSuccessAndFailure_partialFailureStatus() {
    recordingPreference.failOnNext = true;

    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION,
        result(output(Destination.RECIPE, "0.92"), output(Destination.PREFERENCE, "0.85")));

    UUID feedbackId = persistEntry();
    publishAfterCommit(feedbackId);

    awaitState(feedbackId, SubmissionStatus.PARTIALLY_FAILED);

    List<RoutingLogEntry> rows =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    assertThat(rows).hasSize(2);
    RoutingLogEntry recipeRow =
        rows.stream()
            .filter(r -> r.getDestination() == Destination.RECIPE)
            .findFirst()
            .orElseThrow();
    RoutingLogEntry prefRow =
        rows.stream()
            .filter(r -> r.getDestination() == Destination.PREFERENCE)
            .findFirst()
            .orElseThrow();
    assertThat(recipeRow.getStatus()).isEqualTo(RoutingStatus.APPLIED);
    assertThat(prefRow.getStatus()).isEqualTo(RoutingStatus.FAILED);
    assertThat(prefRow.getFailureKind()).isEqualTo(RoutingFailureKind.DESTINATION_BUSINESS);

    awaitCondition(() -> !processedCapture.events.isEmpty(), Duration.ofSeconds(15));
    FeedbackProcessedEvent fpe = processedCapture.events.get(0);
    assertThat(fpe.partialFailure()).isTrue();
    assertThat(fpe.destinationsTouched())
        .containsExactlyInAnyOrder(Destination.RECIPE, Destination.PREFERENCE);
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
        Duration.ofSeconds(15));
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

  // ---------------- test wiring ----------------

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

  /** Controllable RecipeFeedbackHandler — applies a deterministic result so the IT is hermetic. */
  static class RecordingRecipeHandler implements RecipeFeedbackHandler {
    @Override
    public Result handleRecipeFeedback(Input input) {
      return new Result(false, "recipe-ok", Map.of("recipeId", input.recipeId().toString()));
    }

    void reset() {}
  }

  /**
   * Controllable PreferenceFeedbackBridge — toggle {@link #failOnNext} to simulate a destination
   * business exception. Throws {@link
   * com.example.mealprep.preference.exception.HardConstraintsNotFoundException} which the router
   * classifies as {@code DESTINATION_BUSINESS}.
   */
  static class RecordingPreferenceBridge implements PreferenceFeedbackBridge {
    volatile boolean failOnNext = false;

    @Override
    public Result applyFeedback(Input input) {
      if (failOnNext) {
        throw new com.example.mealprep.preference.exception.HardConstraintsNotFoundException(
            input.userId());
      }
      return new Result("preference-ok", Map.of());
    }

    void reset() {
      failOnNext = false;
    }
  }

  static class NoopNutritionBridge implements NutritionFeedbackBridge {
    @Override
    public Result applyFeedback(Input input) {
      return new Result("nutrition-ok", Map.of());
    }
  }

  static class NoopProvisionsBridge implements ProvisionsFeedbackBridge {
    @Override
    public Result applyFeedback(Input input) {
      return new Result("provisions-ok", Map.of());
    }
  }

  @TestConfiguration
  static class RoutingItConfig {

    @Bean
    FeedbackProcessedCapture feedbackProcessedCapture() {
      return new FeedbackProcessedCapture();
    }

    @Bean
    EventPublisherHelper eventPublisherHelper(ApplicationEventPublisher publisher) {
      return new EventPublisherHelper(publisher);
    }

    @Bean
    @Primary
    RecipeFeedbackHandler recordingRecipeHandler() {
      return new RecordingRecipeHandler();
    }

    @Bean
    @Primary
    PreferenceFeedbackBridge recordingPreferenceBridge() {
      return new RecordingPreferenceBridge();
    }

    @Bean
    @Primary
    NutritionFeedbackBridge nutritionFeedbackBridge() {
      return new NoopNutritionBridge();
    }

    @Bean
    @Primary
    ProvisionsFeedbackBridge provisionsFeedbackBridge() {
      return new NoopProvisionsBridge();
    }

    /** Expose the recording impls under their concrete types for the test to mutate. */
    @Bean
    RecordingRecipeHandler recordingRecipeHandlerHandle(RecipeFeedbackHandler bean) {
      return (RecordingRecipeHandler) bean;
    }

    @Bean
    RecordingPreferenceBridge recordingPreferenceBridgeHandle(PreferenceFeedbackBridge bean) {
      return (RecordingPreferenceBridge) bean;
    }
  }
}
