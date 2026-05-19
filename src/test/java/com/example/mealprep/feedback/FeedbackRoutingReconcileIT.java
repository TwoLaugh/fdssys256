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
 * Async submit→classify→fan-out IT targeting the {@link
 * com.example.mealprep.feedback.domain.service.internal.FeedbackRouterImpl} reconcile arms the
 * existing flow ITs leave uncovered:
 *
 * <ul>
 *   <li><b>All destinations FAILED</b> → {@code reconcileAndPublish}'s {@code anyFailed &&
 *       !anyNonFailed} branch → entry {@code FAILED}, {@code FeedbackProcessedEvent.partialFailure
 *       = true} (the routing flow IT only covered the mixed PARTIALLY_FAILED branch).
 *   <li><b>Recipe requiresApproval=true</b> → {@code DispatchResult.awaitingApproval} → routing row
 *       {@code AWAITING_USER_APPROVAL}, treated as non-failed so the entry reconciles to {@code
 *       ROUTED}.
 *   <li><b>Recipe with no recipe id anywhere</b> → {@code RecipeDestinationDispatcher} self-fails
 *       with {@code DESTINATION_VALIDATION} (the resolve-recipe-id null arm) without invoking the
 *       SPI; entry reconciles to {@code FAILED}.
 * </ul>
 *
 * <p>Async-race pattern: bounded polling, no {@code Thread.sleep} outside the helper. Bridges carry
 * {@code @Primary} to override the {@code @ConditionalOnMissingBean} Noop SPIs (wave-3 SPI-stand-in
 * trap).
 */
@SpringBootTest
@Import({TestContainersConfig.class, FeedbackRoutingReconcileIT.ReconcileItConfig.class})
@ActiveProfiles("test")
class FeedbackRoutingReconcileIT {

  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private RoutingLogRepository routingLogRepository;
  @Autowired private TestAiService testAiService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FeedbackProcessedCapture processedCapture;
  @Autowired private EventPublisherHelper publisherHelper;

  @BeforeEach
  void setUp() {
    processedCapture.clear();
    testAiService.clear();
  }

  @AfterEach
  void cleanup() {
    // Children before parents: feedback_misclassification_corrections FK-references
    // feedback_routing_log (original_routing_id) — delete it first.
    jdbcTemplate.update("DELETE FROM feedback_misclassification_corrections");
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    jdbcTemplate.update("DELETE FROM ai_call_log");
    testAiService.clear();
    processedCapture.clear();
  }

  @Test
  void allDestinationsFail_reconcilesToFailed_partialFailureTrue() {
    // Two destinations, both bridges throw a destination-business exception → both FAILED.
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION,
        result(output(Destination.PREFERENCE, "0.91"), output(Destination.PROVISIONS, "0.88")));

    UUID feedbackId = persistEntry(recipeContext());
    publishAfterCommit(feedbackId);

    awaitState(feedbackId, SubmissionStatus.FAILED);

    List<RoutingLogEntry> rows =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    assertThat(rows).hasSize(2);
    assertThat(rows).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(RoutingStatus.FAILED));
    assertThat(rows)
        .allSatisfy(
            r -> assertThat(r.getFailureKind()).isEqualTo(RoutingFailureKind.DESTINATION_BUSINESS));

    awaitCondition(() -> !processedCapture.events.isEmpty(), Duration.ofSeconds(30));
    FeedbackProcessedEvent fpe = processedCapture.events.get(0);
    assertThat(fpe.partialFailure()).isTrue();
    assertThat(fpe.destinationsTouched())
        .containsExactlyInAnyOrder(Destination.PREFERENCE, Destination.PROVISIONS);
  }

  @Test
  void recipeRequiresApproval_persistsAwaitingApprovalRow_andRouted() {
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION, result(output(Destination.RECIPE, "0.93")));

    UUID feedbackId = persistEntry(recipeContext());
    publishAfterCommit(feedbackId);

    awaitState(feedbackId, SubmissionStatus.ROUTED);

    List<RoutingLogEntry> rows =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    assertThat(rows).hasSize(1);
    RoutingLogEntry row = rows.get(0);
    assertThat(row.getDestination()).isEqualTo(Destination.RECIPE);
    assertThat(row.getStatus()).isEqualTo(RoutingStatus.AWAITING_USER_APPROVAL);
    assertThat(row.getActionTaken()).isEqualTo("recipe-approval-needed");
    assertThat(row.getCompletedAt()).isNotNull();

    awaitCondition(() -> !processedCapture.events.isEmpty(), Duration.ofSeconds(30));
    assertThat(processedCapture.events.get(0).partialFailure()).isFalse();
  }

  @Test
  void recipeRoute_noRecipeIdAnywhere_failsWithDestinationValidation_andEntryFailed() {
    // GENERAL screen (no recipeId in context) and classifier payload has no recipeId →
    // RecipeDestinationDispatcher.resolveRecipeId returns null → DESTINATION_VALIDATION.
    testAiService.register(
        TaskType.FEEDBACK_CLASSIFICATION, result(output(Destination.RECIPE, "0.90")));

    UUID feedbackId = persistEntry(generalContext());
    publishAfterCommit(feedbackId);

    awaitState(feedbackId, SubmissionStatus.FAILED);

    List<RoutingLogEntry> rows =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    assertThat(rows).hasSize(1);
    RoutingLogEntry row = rows.get(0);
    assertThat(row.getStatus()).isEqualTo(RoutingStatus.FAILED);
    assertThat(row.getFailureKind()).isEqualTo(RoutingFailureKind.DESTINATION_VALIDATION);
  }

  // ---------------- helpers ----------------

  private UiContextDocument recipeContext() {
    return new UiContextDocument(Screen.RECIPE_DETAIL, UUID.randomUUID(), 1, null, null, null);
  }

  private UiContextDocument generalContext() {
    return new UiContextDocument(Screen.GENERAL, null, null, null, null, null);
  }

  private UUID persistEntry(UiContextDocument ctx) {
    UUID feedbackId = UUID.randomUUID();
    FeedbackEntry entry =
        FeedbackEntry.builder()
            .id(feedbackId)
            .userId(UUID.randomUUID())
            .traceId(UUID.randomUUID())
            .text("the salt was too much")
            .uiContext(ctx)
            .submissionStatus(SubmissionStatus.RECEIVED)
            .classificationAttempts(0)
            .routingLog(new ArrayList<>())
            .build();
    return entryRepository.save(entry).getId();
  }

  private void publishAfterCommit(UUID feedbackId) {
    FeedbackEntry entry = entryRepository.findById(feedbackId).orElseThrow();
    publisherHelper.publishInTransaction(
        new FeedbackSubmittedEvent(
            feedbackId,
            entry.getUserId(),
            entry.getUiContext().screen(),
            entry.getTraceId(),
            Instant.now()));
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

  /** Always rejects with a destination-business exception (preference module package). */
  static class FailingPreferenceBridge implements PreferenceFeedbackBridge {
    @Override
    public Result applyFeedback(Input input) {
      throw new com.example.mealprep.preference.exception.HardConstraintsNotFoundException(
          input.userId());
    }
  }

  /** Always rejects with a destination-business exception (provisions module package). */
  static class FailingProvisionsBridge implements ProvisionsFeedbackBridge {
    @Override
    public Result applyFeedback(Input input) {
      throw new com.example.mealprep.provisions.exception.InventoryItemNotFoundException(
          UUID.randomUUID());
    }
  }

  /** Recipe handler that always requests approval → AWAITING_USER_APPROVAL routing status. */
  static class ApprovalRecipeHandler implements RecipeFeedbackHandler {
    @Override
    public Result handleRecipeFeedback(Input input) {
      return new Result(true, "recipe-approval-needed", Map.of("recipeId", input.recipeId()));
    }
  }

  @TestConfiguration
  static class ReconcileItConfig {

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
    PreferenceFeedbackBridge failingPreferenceBridge() {
      return new FailingPreferenceBridge();
    }

    @Bean
    @Primary
    ProvisionsFeedbackBridge failingProvisionsBridge() {
      return new FailingProvisionsBridge();
    }

    @Bean
    @Primary
    NutritionFeedbackBridge nutritionFeedbackBridge() {
      return input -> new NutritionFeedbackBridge.Result("nutrition-ok", Map.of());
    }

    @Bean
    @Primary
    RecipeFeedbackHandler approvalRecipeHandler() {
      return new ApprovalRecipeHandler();
    }
  }
}
