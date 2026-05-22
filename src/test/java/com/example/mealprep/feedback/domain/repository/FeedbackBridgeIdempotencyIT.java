package com.example.mealprep.feedback.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackBridgeIdempotency;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link FeedbackBridgeIdempotencyRepository} against real Postgres: the
 * insert-or-skip primitive ({@code ON CONFLICT DO NOTHING}), the {@code (feedback_id, destination)}
 * unique guard, the status update, and the retention delete (feedback-01g §4, §21).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class FeedbackBridgeIdempotencyIT {

  @Autowired private FeedbackBridgeIdempotencyRepository repository;

  @AfterEach
  void cleanup() {
    repository.deleteAll();
  }

  @Test
  @Transactional
  void insertIfAbsent_secondInsertForSamePair_isSkipped() {
    UUID feedbackId = UUID.randomUUID();
    Instant now = Instant.now();

    int first =
        repository.insertIfAbsent(
            UUID.randomUUID(),
            feedbackId,
            Destination.PREFERENCE.name(),
            BridgeDispatchStatus.DISPATCHED.name(),
            now);
    int second =
        repository.insertIfAbsent(
            UUID.randomUUID(),
            feedbackId,
            Destination.PREFERENCE.name(),
            BridgeDispatchStatus.FAILED.name(),
            now.plusSeconds(1));

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero(); // unique (feedback_id, destination) — second is a no-op

    Optional<FeedbackBridgeIdempotency> row =
        repository.findByFeedbackIdAndDestination(feedbackId, Destination.PREFERENCE);
    assertThat(row).isPresent();
    // The first insert's status survives — the second was skipped, not overwritten.
    assertThat(row.get().getStatus()).isEqualTo(BridgeDispatchStatus.DISPATCHED);
  }

  @Test
  @Transactional
  void differentDestinationsForSameFeedback_bothInsert() {
    UUID feedbackId = UUID.randomUUID();
    Instant now = Instant.now();

    int pref =
        repository.insertIfAbsent(
            UUID.randomUUID(),
            feedbackId,
            Destination.PREFERENCE.name(),
            BridgeDispatchStatus.DISPATCHED.name(),
            now);
    int nutrition =
        repository.insertIfAbsent(
            UUID.randomUUID(),
            feedbackId,
            Destination.NUTRITION.name(),
            BridgeDispatchStatus.FAILED.name(),
            now);

    assertThat(pref).isEqualTo(1);
    assertThat(nutrition).isEqualTo(1);
  }

  @Test
  @Transactional
  void updateStatus_overwritesTerminalOutcome() {
    UUID feedbackId = UUID.randomUUID();
    Instant now = Instant.now();
    repository.insertIfAbsent(
        UUID.randomUUID(),
        feedbackId,
        Destination.RECIPE.name(),
        BridgeDispatchStatus.FAILED.name(),
        now);

    int updated =
        repository.updateStatus(
            feedbackId, Destination.RECIPE, BridgeDispatchStatus.DISPATCHED, now.plusSeconds(2));

    assertThat(updated).isEqualTo(1);
    assertThat(
            repository
                .findByFeedbackIdAndDestination(feedbackId, Destination.RECIPE)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.DISPATCHED);
  }

  @Test
  @Transactional
  void deleteOlderThan_removesOnlyStaleRows() {
    Instant now = Instant.now();
    UUID staleFeedback = UUID.randomUUID();
    UUID freshFeedback = UUID.randomUUID();
    repository.insertIfAbsent(
        UUID.randomUUID(),
        staleFeedback,
        Destination.PROVISIONS.name(),
        BridgeDispatchStatus.DISPATCHED.name(),
        now.minus(8, ChronoUnit.DAYS));
    repository.insertIfAbsent(
        UUID.randomUUID(),
        freshFeedback,
        Destination.PROVISIONS.name(),
        BridgeDispatchStatus.DISPATCHED.name(),
        now.minus(1, ChronoUnit.DAYS));

    int deleted = repository.deleteOlderThan(now.minus(7, ChronoUnit.DAYS));

    assertThat(deleted).isEqualTo(1);
    assertThat(repository.findByFeedbackIdAndDestination(staleFeedback, Destination.PROVISIONS))
        .isEmpty();
    assertThat(repository.findByFeedbackIdAndDestination(freshFeedback, Destination.PROVISIONS))
        .isPresent();
  }
}
