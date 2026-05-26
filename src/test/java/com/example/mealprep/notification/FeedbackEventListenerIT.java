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

  /** All-success routed: touched non-empty, applied == touched → fire exactly once. */
  @Test
  void appliedChange_producesExactlyOneFeedbackConfirmation() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publish(
        new FeedbackProcessedEvent(
            UUID.randomUUID(),
            user,
            Set.of(Destination.PROVISIONS),
            Set.of(Destination.PROVISIONS), // applied == touched
            false,
            false,
            UUID.randomUUID(),
            Instant.now()));

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isEqualTo(1);
  }

  /**
   * Partial success: touched={PROVISIONS, NUTRITION} but only PROVISIONS applied → still fire (one
   * row), and the payload lists ONLY the succeeded destination.
   */
  @Test
  void partialSuccess_producesExactlyOneFeedbackConfirmation() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    UUID feedbackId = UUID.randomUUID();
    publish(
        new FeedbackProcessedEvent(
            feedbackId,
            user,
            Set.of(Destination.PROVISIONS, Destination.NUTRITION), // both attempted
            Set.of(Destination.PROVISIONS), // only PROVISIONS succeeded
            true, // partialFailure — but at least one applied
            false,
            UUID.randomUUID(),
            Instant.now()));

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isEqualTo(1);
    // Payload lists ONLY the applied destination, not the failed NUTRITION attempt.
    String appliedJson =
        jdbcTemplate.queryForObject(
            "SELECT payload ->> 'appliedDestinations' FROM notifications "
                + "WHERE user_id = ?::uuid AND kind = 'FEEDBACK_CONFIRMATION'",
            String.class,
            user.toString());
    assertThat(appliedJson).contains("PROVISIONS").doesNotContain("NUTRITION");
  }

  /** Non-actionable / empty (markRoutedEmpty): nothing touched, nothing applied → no row. */
  @Test
  void nonActionable_producesNoNotification() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publish(
        new FeedbackProcessedEvent(
            UUID.randomUUID(),
            user,
            Set.of(), // nothing touched
            Set.of(), // nothing applied
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
            Set.of(),
            false,
            true, // clarificationPending
            UUID.randomUUID(),
            Instant.now()));

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isZero();
  }

  /**
   * Pre-route terminal failure (markFailed / StuckClassificationRetrier): nothing touched, nothing
   * applied, partialFailure=true → no row.
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
            Set.of(), // nothing applied
            true, // partialFailure
            false,
            UUID.randomUUID(),
            Instant.now()));

    assertThat(countFor(user, "FEEDBACK_CONFIRMATION")).isZero();
  }

  /**
   * THE BUG THIS FIX CLOSES. A routed feedback whose every bridge FAILED: destinationsTouched is
   * non-empty (the router attempts each destination) but appliedDestinations is EMPTY (no route
   * succeeded). NOTIF-16 must NOT fire — nothing was applied. Before the fix the gate read the
   * non-empty touched set and wrongly fired a "Feedback applied" notification.
   */
  @Test
  void allRoutesFailed_touchedNonEmptyButNothingApplied_producesNoNotification() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publish(
        new FeedbackProcessedEvent(
            UUID.randomUUID(),
            user,
            Set.of(Destination.PROVISIONS, Destination.NUTRITION), // both ATTEMPTED
            Set.of(), // ...but NONE applied (all routes FAILED)
            true, // partialFailure / total failure
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
