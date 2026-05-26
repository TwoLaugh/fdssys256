package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NOTIF-16 in-process listener IT: a {@link FeedbackProcessedEvent} published inside a committed
 * transaction drives the {@code AFTER_COMMIT} {@code FeedbackEventListener} → exactly one {@code
 * FEEDBACK_CONFIRMATION} row when at least one destination applied, and NO row for the three
 * negative outcomes (non-actionable / clarification-pending / total-failure). Mirrors {@link
 * EventListenerIT}; AFTER_COMMIT listeners run synchronously on commit, so assertions follow
 * immediately.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class FeedbackEventListenerIT {

  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private NotificationUpdateService updateService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM notification_delivery_log");
    jdbcTemplate.update("DELETE FROM notifications");
    jdbcTemplate.update("DELETE FROM notification_preferences");
  }

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  private long countFor(UUID userId, String kind) {
    Long c =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?::uuid AND kind = ?",
            Long.class,
            userId.toString(),
            kind);
    return c == null ? 0 : c;
  }

  private void publish(FeedbackProcessedEvent event) {
    tx().executeWithoutResult(t -> publisher.publishEvent(event));
  }

  /** A routed apply: ≥1 destination touched, no failure, no clarification → fire. */
  @Test
  void appliedChange_producesExactlyOneFeedbackConfirmation() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publish(
        new FeedbackProcessedEvent(
            UUID.randomUUID(),
            user,
            Set.of(Destination.PROVISIONS),
            false,
            false,
            UUID.randomUUID(),
            Instant.now()));

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isEqualTo(1);
  }

  /** Partial success (some applied, some failed): something applied → still fire (one row). */
  @Test
  void partialSuccess_producesExactlyOneFeedbackConfirmation() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publish(
        new FeedbackProcessedEvent(
            UUID.randomUUID(),
            user,
            Set.of(Destination.PROVISIONS, Destination.NUTRITION),
            true, // partialFailure — but at least one applied
            false,
            UUID.randomUUID(),
            Instant.now()));

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isEqualTo(1);
  }

  /** Non-actionable / empty (markRoutedEmpty): no destination touched → no row. */
  @Test
  void nonActionable_producesNoNotification() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publish(
        new FeedbackProcessedEvent(
            UUID.randomUUID(),
            user,
            Set.of(), // nothing touched
            false,
            false,
            UUID.randomUUID(),
            Instant.now()));

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isZero();
  }

  /** Clarification-pending (queueClarification): clarificationPending=true → no row. */
  @Test
  void clarificationPending_producesNoNotification() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publish(
        new FeedbackProcessedEvent(
            UUID.randomUUID(),
            user,
            Set.of(),
            false,
            true, // clarificationPending
            UUID.randomUUID(),
            Instant.now()));

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isZero();
  }

  /**
   * Total failure (markFailed / StuckClassificationRetrier — the terminal pre-route paths): no
   * destination applied (empty touched set), partialFailure=true → no row.
   */
  @Test
  void totalFailure_producesNoNotification() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publish(
        new FeedbackProcessedEvent(
            UUID.randomUUID(),
            user,
            Set.of(), // terminal failure publishes an empty touched set
            true, // partialFailure
            false,
            UUID.randomUUID(),
            Instant.now()));

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isZero();
  }

  /** A rolled-back publisher transaction never reaches the AFTER_COMMIT listener → no row. */
  @Test
  void rolledBackPublisherTransaction_producesNoNotification() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    tx().executeWithoutResult(
            t -> {
              publisher.publishEvent(
                  new FeedbackProcessedEvent(
                      UUID.randomUUID(),
                      user,
                      Set.of(Destination.PROVISIONS),
                      false,
                      false,
                      UUID.randomUUID(),
                      Instant.now()));
              t.setRollbackOnly();
            });

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isZero();
  }
}
