package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.nutrition.event.HealthDirectiveReceivedEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
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
 * Verifies that consumed events drive notification rows through the AFTER_COMMIT listener chain.
 * The publisher must run inside a committed transaction for {@code @TransactionalEventListener} to
 * fire; Spring runs AFTER_COMMIT listeners synchronously on commit, so assertions can follow
 * immediately.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class EventListenerIT {

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

  @Test
  void itemSpoiled_producesNotificationAndDeliveryLog() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    tx().executeWithoutResult(
            t ->
                publisher.publishEvent(
                    new ItemSpoiledEvent(
                        user,
                        List.of(UUID.randomUUID()),
                        "expired",
                        UUID.randomUUID(),
                        Instant.now())));

    assertThat(countFor(user, "PROVISION_ITEM_SPOILED")).isEqualTo(1);
    Long delivered =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_delivery_log WHERE channel = 'IN_APP' AND outcome = 'DELIVERED'",
            Long.class);
    assertThat(delivered).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void healthDirective_producesUrgentNotification() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    tx().executeWithoutResult(
            t ->
                publisher.publishEvent(
                    new HealthDirectiveReceivedEvent(
                        user,
                        UUID.randomUUID(),
                        com.example.mealprep.nutrition.api.dto.DirectiveType.values()[0],
                        "platform",
                        Instant.now(),
                        UUID.randomUUID(),
                        Instant.now())));

    assertThat(countFor(user, "HEALTH_DIRECTIVE_RECEIVED")).isEqualTo(1);
  }

  @Test
  void rolledBackPublisherTransaction_producesNoNotification() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    tx().executeWithoutResult(
            t -> {
              publisher.publishEvent(
                  new ItemSpoiledEvent(
                      user, List.of(UUID.randomUUID()), "x", UUID.randomUUID(), Instant.now()));
              t.setRollbackOnly();
            });

    assertThat(countFor(user, "PROVISION_ITEM_SPOILED")).isZero();
  }
}
