package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testing.TestAiService;
import com.example.mealprep.feedback.ai.dto.AiTasteProfileDelta;
import com.example.mealprep.feedback.ai.dto.TasteProfileDeltaResponse;
import com.example.mealprep.feedback.ai.internal.PreferenceDeltaBatchTrigger;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.TriggerTasteProfileRefreshRequest;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Testcontainers IT for the preference AI delta-generation pipeline (preference-01g):
 *
 * <ul>
 *   <li>MANUAL trigger — {@code triggerRefresh} publishes {@code TasteProfileRefreshRequestedEvent}
 *       AFTER_COMMIT → {@code PreferenceRefreshRequestedListener} → {@code applyDeltas} under
 *       {@code REQUIRES_NEW}; asserts the profile version bumps and the audit row carries {@code
 *       actor_type=AI} despite firing in the AFTER_COMMIT phase (decision-log 0010 commit-proof).
 *   <li>BATCH trigger — five PREFERENCE-routed feedbacks drive {@code PreferenceDeltaBatchTrigger}
 *       to fire on the 5th and apply deltas.
 * </ul>
 *
 * <p>AI is stubbed via {@link TestAiService} (canned {@link TasteProfileDeltaResponse}); no HTTP.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PreferenceDeltaPipelineIT {

  @Autowired private TasteProfileUpdateService tasteProfileUpdate;
  @Autowired private TasteProfileQueryService tasteProfileQuery;
  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private RoutingLogRepository routingLogRepository;
  @Autowired private PreferenceDeltaBatchTrigger batchTrigger;
  @Autowired private TestAiService testAiService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    testAiService.clear();
  }

  @AfterEach
  void cleanup() {
    // FK-children of feedback_routing_log first: feedback_misclassification_corrections holds a
    // NOT-NULL, non-cascading original_routing_id (and a nullable replay_routing_id) → deleting the
    // routing log before its corrections children raises a DataIntegrityViolationException.
    jdbcTemplate.update("DELETE FROM feedback_misclassification_corrections");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_preference_delta_cursor");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    jdbcTemplate.update("DELETE FROM preference_taste_profile_audit");
    jdbcTemplate.update("DELETE FROM preference_taste_profile_versions");
    jdbcTemplate.update("DELETE FROM preference_taste_profile");
    jdbcTemplate.update("DELETE FROM ai_call_log");
    testAiService.clear();
  }

  @Test
  void manualTrigger_afterCommit_appliesDeltasUnderRequiresNew_withAiActor() {
    UUID userId = UUID.randomUUID();
    TasteProfileDto seeded = tasteProfileUpdate.initialise(userId);
    int versionBefore = seeded.documentVersion();

    UUID feedbackId = persistPreferenceFeedback(userId, "I really love prawns in a stir fry");
    testAiService.register(TaskType.PREFERENCE_DELTA_UPDATE, addPrawnsResponse(feedbackId));

    // triggerRefresh publishes TasteProfileRefreshRequestedEvent AFTER_COMMIT (no explicit range).
    tasteProfileUpdate.triggerRefresh(
        userId, new TriggerTasteProfileRefreshRequest(null, null), userId, UUID.randomUUID());

    awaitCondition(
        () ->
            tasteProfileQuery
                .getTasteProfile(userId)
                .filter(p -> p.documentVersion() > versionBefore)
                .isPresent(),
        Duration.ofSeconds(30));

    TasteProfileDto after = tasteProfileQuery.getTasteProfile(userId).orElseThrow();
    assertThat(after.documentVersion()).isEqualTo(versionBefore + 1);

    // The delta-apply audit row carries actor_type=AI (the user only requested the refresh).
    Integer aiRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM preference_taste_profile_audit WHERE actor_type = 'AI'"
                + " AND change_type = 'AI_DELTA_APPLIED'",
            Integer.class);
    assertThat(aiRows).isGreaterThanOrEqualTo(1);
    // An AiCallLog row was written for the mid-tier task (proves the AI call ran through the stub).
    Integer aiCalls =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_call_log WHERE task_type = 'PREFERENCE_DELTA_UPDATE'",
            Integer.class);
    assertThat(aiCalls).isGreaterThanOrEqualTo(1);
  }

  @Test
  void batchTrigger_firesOnFifthPreferenceFeedback_andApplies() {
    UUID userId = UUID.randomUUID();
    TasteProfileDto seeded = tasteProfileUpdate.initialise(userId);
    int versionBefore = seeded.documentVersion();
    testAiService.register(TaskType.PREFERENCE_DELTA_UPDATE, addPrawnsResponse(null));

    // Four below-threshold feedbacks: accumulate, no run.
    for (int i = 0; i < 4; i++) {
      UUID fid = persistPreferenceFeedback(userId, "I keep enjoying prawns " + i);
      assertThat(batchTrigger.onPreferenceFeedback(userId, fid)).isNull();
    }
    assertThat(tasteProfileQuery.getTasteProfile(userId).orElseThrow().documentVersion())
        .isEqualTo(versionBefore);

    // Fifth fires the BATCH run.
    UUID fifth = persistPreferenceFeedback(userId, "definitely a prawns person now");
    batchTrigger.onPreferenceFeedback(userId, fifth);

    TasteProfileDto after = tasteProfileQuery.getTasteProfile(userId).orElseThrow();
    assertThat(after.documentVersion()).isGreaterThan(versionBefore);
  }

  // ---------------- helpers ----------------

  private UUID persistPreferenceFeedback(UUID userId, String text) {
    UUID feedbackId = UUID.randomUUID();
    FeedbackEntry entry =
        FeedbackEntry.builder()
            .id(feedbackId)
            .userId(userId)
            .traceId(UUID.randomUUID())
            .text(text)
            .uiContext(new UiContextDocument(Screen.GENERAL, null, null, null, null, null))
            .submissionStatus(SubmissionStatus.ROUTED)
            .classificationAttempts(1)
            .lastClassifiedAt(Instant.now())
            .routingLog(new ArrayList<>())
            .build();
    entry = entryRepository.save(entry);

    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("note", "preference");
    RoutingLogEntry log =
        RoutingLogEntry.builder()
            .id(UUID.randomUUID())
            .feedbackEntry(entry)
            .destination(Destination.PREFERENCE)
            .confidence(new BigDecimal("0.920"))
            .extractedFeedback(text)
            .structuredPayload(payload)
            .routingDecision(RoutingDecision.AUTO_ROUTED)
            .status(RoutingStatus.APPLIED)
            .classificationAttempt(1)
            .routedAt(Instant.now())
            .build();
    routingLogRepository.save(log);
    return feedbackId;
  }

  private TasteProfileDeltaResponse addPrawnsResponse(UUID feedbackId) {
    String evidence = feedbackId == null ? UUID.randomUUID().toString() : feedbackId.toString();
    return new TasteProfileDeltaResponse(
        List.of(
            new AiTasteProfileDelta.Add(
                "likes.ingredients",
                "prawns",
                "especially in quick high-heat preparations",
                evidence,
                "single explicit positive statement about prawns",
                AiTasteProfileDelta.Confidence.MEDIUM)),
        "added prawns to likes from explicit feedback",
        List.of());
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
}
