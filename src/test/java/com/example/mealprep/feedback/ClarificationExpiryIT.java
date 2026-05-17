package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Postgres-backed IT for the daily expiry sweep. Queries are seeded directly via the repository (no
 * POST → no async runner racing the seed — wave-3 retro). The {@code @Scheduled} method is invoked
 * manually. TTL anchoring is relative to {@code Instant.now()} via the test-data builder (never a
 * hardcoded date — time-bomb gotcha).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class ClarificationExpiryIT {

  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private ClarificationQueryRepository clarificationRepository;
  @Autowired private FeedbackUpdateService updateService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
  }

  @Test
  void sweep_expiresPastTtlQuery_andFailsParentEntry() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "ambiguous note");
    entry.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entryRepository.saveAndFlush(entry);
    ClarificationQuery expired = FeedbackTestData.expiredClarificationQuery(entry);
    clarificationRepository.saveAndFlush(expired);

    updateService.expireOldClarificationQueries();

    ClarificationQuery reloaded = clarificationRepository.findById(expired.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(ClarificationStatus.EXPIRED);
    FeedbackEntry reloadedEntry = entryRepository.findById(entry.getId()).orElseThrow();
    assertThat(reloadedEntry.getSubmissionStatus()).isEqualTo(SubmissionStatus.FAILED);
  }

  @Test
  void sweep_leavesFutureTtlQueryUntouched() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "still fresh");
    entry.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entryRepository.saveAndFlush(entry);
    ClarificationQuery fresh = FeedbackTestData.clarificationQuery(entry); // expires +7d
    clarificationRepository.saveAndFlush(fresh);

    updateService.expireOldClarificationQueries();

    ClarificationQuery reloaded = clarificationRepository.findById(fresh.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(ClarificationStatus.PENDING);
    FeedbackEntry reloadedEntry = entryRepository.findById(entry.getId()).orElseThrow();
    assertThat(reloadedEntry.getSubmissionStatus())
        .isEqualTo(SubmissionStatus.CLARIFICATION_PENDING);
  }

  @Test
  void sweep_isIdempotent_alreadyExpiredQueryNotReprocessed() {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "old");
    entry.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entryRepository.saveAndFlush(entry);
    ClarificationQuery q = FeedbackTestData.expiredClarificationQuery(entry);
    q.setStatus(ClarificationStatus.EXPIRED);
    clarificationRepository.saveAndFlush(q);

    updateService.expireOldClarificationQueries();

    // The PENDING-only filter excludes it; the parent entry is NOT flipped to FAILED here.
    FeedbackEntry reloadedEntry = entryRepository.findById(entry.getId()).orElseThrow();
    assertThat(reloadedEntry.getSubmissionStatus())
        .isEqualTo(SubmissionStatus.CLARIFICATION_PENDING);
  }
}
