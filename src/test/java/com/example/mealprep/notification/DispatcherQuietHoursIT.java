package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.api.dto.UpdateNotificationPreferenceRequest;
import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.notification.testdata.NotificationTestData;
import com.example.mealprep.provisions.event.ItemNearingExpiryEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
 * Real-DB quiet-hours behaviour. A quiet-hours window of 00:00–23:59 keeps every wall-clock time
 * inside the window, so an ATTENTION event is always suppressed (and persisted DISMISSED) while an
 * URGENT event is always delivered — independent of the host clock.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class DispatcherQuietHoursIT {

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

  private void enableAlwaysQuiet(UUID user) {
    updateService.ensurePreferencesForUser(user);
    updateService.updatePreferences(
        user,
        new UpdateNotificationPreferenceRequest(
            NotificationTestData.allEnabled(),
            true,
            LocalTime.of(0, 0),
            LocalTime.of(23, 59),
            "Europe/London",
            30,
            0L));
  }

  @Test
  void attentionDuringQuietHours_suppressed_persistedDismissed() {
    UUID user = UUID.randomUUID();
    enableAlwaysQuiet(user);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            t ->
                publisher.publishEvent(
                    new ItemNearingExpiryEvent(
                        user,
                        null,
                        List.of(UUID.randomUUID()),
                        LocalDate.now(),
                        UUID.randomUUID(),
                        Instant.now())));

    Long dismissed =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?::uuid AND status = 'DISMISSED'",
            Long.class,
            user.toString());
    assertThat(dismissed).isEqualTo(1L);

    Long unread =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?::uuid AND status = 'UNREAD'",
            Long.class,
            user.toString());
    assertThat(unread).isZero();

    Long skipLog =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_delivery_log WHERE outcome = 'SKIPPED' AND skip_reason = 'QUIET_HOURS'",
            Long.class);
    assertThat(skipLog).isEqualTo(1L);
  }

  @Test
  void urgentDuringQuietHours_delivered() {
    UUID user = UUID.randomUUID();
    enableAlwaysQuiet(user);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            t ->
                publisher.publishEvent(
                    new com.example.mealprep.nutrition.event.HealthDirectiveReceivedEvent(
                        user,
                        UUID.randomUUID(),
                        com.example.mealprep.nutrition.api.dto.DirectiveType.values()[0],
                        "platform",
                        Instant.now(),
                        UUID.randomUUID(),
                        Instant.now())));

    Long unread =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?::uuid AND status = 'UNREAD' AND severity = 'URGENT'",
            Long.class,
            user.toString());
    assertThat(unread).isEqualTo(1L);
  }
}
