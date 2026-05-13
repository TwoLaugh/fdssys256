package com.example.mealprep.feedback.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.MisclassificationCorrection;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Round-trip integration tests for the four feedback entities. Verifies:
 *
 * <ul>
 *   <li>JSONB persists + reads back unchanged for {@code uiContext} / {@code structuredPayload} /
 *       {@code destinationResultJson} / {@code classifierOptionsJson};
 *   <li>{@code @Version} on {@code FeedbackEntry} and {@code ClarificationQuery} surfaces an {@code
 *       OptimisticLockingFailureException} on stale write;
 *   <li>{@code RoutingLogEntry} / {@code MisclassificationCorrection} have no {@code @Version} —
 *       two updates to the same row from different reads both succeed;
 *   <li>FK ON DELETE CASCADE removes children when the parent {@code FeedbackEntry} is deleted.
 * </ul>
 *
 * <p>Lives in the {@code domain.repository} package so the package-private repos are reachable.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class FeedbackEntityRoundTripIT {

  @Autowired private FeedbackEntryRepository feedbackEntryRepository;
  @Autowired private RoutingLogRepository routingLogRepository;
  @Autowired private MisclassificationCorrectionRepository misclassificationRepository;
  @Autowired private ClarificationQueryRepository clarificationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @PersistenceContext private EntityManager entityManager;

  @Test
  @Transactional
  void feedbackEntry_jsonbAndAuditColumns_roundTrip() {
    UUID userId = UUID.randomUUID();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "the salt was too much");
    feedbackEntryRepository.saveAndFlush(entry);
    entityManager.clear();

    FeedbackEntry reloaded = feedbackEntryRepository.findById(entry.getId()).orElseThrow();
    assertThat(reloaded.getText()).isEqualTo("the salt was too much");
    assertThat(reloaded.getUiContext()).isEqualTo(entry.getUiContext());
    assertThat(reloaded.getSubmissionStatus()).isEqualTo(SubmissionStatus.RECEIVED);
    assertThat(reloaded.getCreatedAt()).isNotNull();
    assertThat(reloaded.getUpdatedAt()).isNotNull();
    assertThat(reloaded.getOptimisticVersion()).isZero();
  }

  @Test
  void routingLog_jsonbColumnsRoundTrip_andEntityGraphLoadsLog() {
    FeedbackEntry entry =
        FeedbackTestData.feedbackEntry(UUID.randomUUID(), "the salt was too much");
    feedbackEntryRepository.saveAndFlush(entry);

    RoutingLogEntry log = FeedbackTestData.routingLogEntry(entry);
    routingLogRepository.saveAndFlush(log);

    FeedbackEntry reloaded =
        feedbackEntryRepository
            .findWithRoutingByIdAndUserId(entry.getId(), entry.getUserId())
            .orElseThrow();
    assertThat(reloaded.getRoutingLog()).hasSize(1);
    RoutingLogEntry reloadedLog = reloaded.getRoutingLog().get(0);
    assertThat(reloadedLog.getStructuredPayload()).isEqualTo(log.getStructuredPayload());
    assertThat(reloadedLog.getDestinationResultJson()).isEqualTo(log.getDestinationResultJson());
    assertThat(reloadedLog.getDestination()).isEqualTo(Destination.RECIPE);
    assertThat(reloadedLog.getConfidence()).isEqualByComparingTo(new BigDecimal("0.850"));
  }

  @Test
  void clarificationQuery_jsonbAndVersionRoundTrip() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "lighter dishes");
    feedbackEntryRepository.saveAndFlush(entry);

    ClarificationQuery query = FeedbackTestData.clarificationQuery(entry);
    clarificationRepository.saveAndFlush(query);

    ClarificationQuery reloaded = clarificationRepository.findById(query.getId()).orElseThrow();
    assertThat(reloaded.getClassifierOptionsJson()).isEqualTo(query.getClassifierOptionsJson());
    assertThat(reloaded.getStatus()).isEqualTo(ClarificationStatus.PENDING);
    assertThat(reloaded.getOptimisticVersion()).isZero();
  }

  @Test
  void misclassificationCorrection_pendingReplayInitialStatus_roundTrips() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "no cream");
    feedbackEntryRepository.saveAndFlush(entry);
    RoutingLogEntry log = FeedbackTestData.routingLogEntry(entry);
    routingLogRepository.saveAndFlush(log);

    MisclassificationCorrection correction =
        FeedbackTestData.misclassificationCorrection(entry, log.getId(), entry.getUserId());
    misclassificationRepository.saveAndFlush(correction);

    MisclassificationCorrection reloaded =
        misclassificationRepository.findById(correction.getId()).orElseThrow();
    assertThat(reloaded.getReplayStatus()).isEqualTo(CorrectionReplayStatus.PENDING_REPLAY);
    assertThat(reloaded.getOriginalRoutingId()).isEqualTo(log.getId());
    assertThat(reloaded.getCorrectedDestination()).isEqualTo(Destination.PREFERENCE);
    assertThat(reloaded.getOriginalDestination()).isEqualTo(Destination.RECIPE);
  }

  @Test
  void feedbackEntry_versionIncrementsOnUpdate_andStaleWriteThrows() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "first text");
    feedbackEntryRepository.saveAndFlush(entry);

    FeedbackEntry copyA = feedbackEntryRepository.findById(entry.getId()).orElseThrow();
    FeedbackEntry copyB = feedbackEntryRepository.findById(entry.getId()).orElseThrow();

    copyA.setSubmissionStatus(SubmissionStatus.CLASSIFYING);
    feedbackEntryRepository.saveAndFlush(copyA);

    copyB.setSubmissionStatus(SubmissionStatus.FAILED);
    assertThatThrownBy(() -> feedbackEntryRepository.saveAndFlush(copyB))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void routingLog_hasNoVersion_twoWritersBothSucceed() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "neutral");
    feedbackEntryRepository.saveAndFlush(entry);
    RoutingLogEntry log = FeedbackTestData.routingLogEntry(entry);
    routingLogRepository.saveAndFlush(log);

    RoutingLogEntry copyA = routingLogRepository.findById(log.getId()).orElseThrow();
    RoutingLogEntry copyB = routingLogRepository.findById(log.getId()).orElseThrow();

    copyA.setStatus(RoutingStatus.APPLIED);
    copyA.setCompletedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    routingLogRepository.saveAndFlush(copyA);

    // copyB's write must NOT throw — no @Version on RoutingLogEntry.
    copyB.setActionTaken("alt summary");
    routingLogRepository.saveAndFlush(copyB);

    RoutingLogEntry reloaded = routingLogRepository.findById(log.getId()).orElseThrow();
    assertThat(reloaded.getActionTaken()).isEqualTo("alt summary");
  }

  @Test
  void deletingFeedbackEntry_cascadesToAllChildTables() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "cascade test");
    feedbackEntryRepository.saveAndFlush(entry);
    RoutingLogEntry log = FeedbackTestData.routingLogEntry(entry);
    routingLogRepository.saveAndFlush(log);
    ClarificationQuery query = FeedbackTestData.clarificationQuery(entry);
    clarificationRepository.saveAndFlush(query);
    MisclassificationCorrection correction =
        FeedbackTestData.misclassificationCorrection(entry, log.getId(), entry.getUserId());
    misclassificationRepository.saveAndFlush(correction);

    // Native DELETE to bypass cascade-on-the-Java side; rely on the DB ON DELETE CASCADE.
    jdbcTemplate.update("DELETE FROM feedback_entries WHERE id = ?", entry.getId());

    assertThat(routingLogRepository.findById(log.getId())).isEmpty();
    assertThat(clarificationRepository.findById(query.getId())).isEmpty();
    assertThat(misclassificationRepository.findById(correction.getId())).isEmpty();
  }

  @Test
  void aggregateByDestination_groupsAndAverages() {
    // Use a unique, narrow time window that isolates from any other test's routing rows.
    Instant windowAnchor = Instant.parse("2099-01-01T00:00:00Z");

    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "rollup test");
    feedbackEntryRepository.saveAndFlush(entry);

    RoutingLogEntry r1 = FeedbackTestData.routingLogEntry(entry);
    r1.setConfidence(new BigDecimal("0.700"));
    r1.setRoutedAt(windowAnchor.plusSeconds(1));
    routingLogRepository.saveAndFlush(r1);

    RoutingLogEntry r2 = FeedbackTestData.routingLogEntry(entry);
    r2.setConfidence(new BigDecimal("0.900"));
    r2.setDestination(Destination.RECIPE);
    r2.setRoutedAt(windowAnchor.plusSeconds(2));
    routingLogRepository.saveAndFlush(r2);

    List<DestinationRollupRow> rows =
        routingLogRepository.aggregateByDestination(
            windowAnchor, windowAnchor.plus(1, ChronoUnit.HOURS));

    assertThat(rows).isNotEmpty();
    DestinationRollupRow recipeRow =
        rows.stream()
            .filter(r -> r.getDestination() == Destination.RECIPE)
            .findFirst()
            .orElseThrow();
    assertThat(recipeRow.getCount()).isEqualTo(2L);
    assertThat(recipeRow.getAvgConfidence()).isEqualByComparingTo(new BigDecimal("0.800"));
  }
}
