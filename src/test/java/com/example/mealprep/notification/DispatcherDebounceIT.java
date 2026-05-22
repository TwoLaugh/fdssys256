package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.provisions.event.ItemNearingExpiryEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.time.LocalDate;
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

/** Real-DB debounce/bundle behaviour for {@code PROVISION_ITEM_NEAR_EXPIRY}. */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class DispatcherDebounceIT {

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

  private void publishExpiry(UUID user) {
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
  }

  @Test
  void threeEventsWithinWindow_collapseToOneRowWithBundleCount3() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publishExpiry(user);
    publishExpiry(user);
    publishExpiry(user);

    Long rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?::uuid AND kind = 'PROVISION_ITEM_NEAR_EXPIRY' AND status = 'UNREAD'",
            Long.class,
            user.toString());
    assertThat(rows).isEqualTo(1L);

    Integer bundleCount =
        jdbcTemplate.queryForObject(
            "SELECT bundle_count FROM notifications WHERE user_id = ?::uuid AND kind = 'PROVISION_ITEM_NEAR_EXPIRY' AND status = 'UNREAD'",
            Integer.class,
            user.toString());
    assertThat(bundleCount).isEqualTo(3);
  }

  @Test
  void crossKindIsolation_doesNotBundle() {
    UUID user = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publishExpiry(user);
    // A spoiled event must not bundle into the near-expiry row.
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            t ->
                publisher.publishEvent(
                    new com.example.mealprep.provisions.event.ItemSpoiledEvent(
                        user, List.of(UUID.randomUUID()), "x", UUID.randomUUID(), Instant.now())));

    Long total =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?::uuid AND status = 'UNREAD'",
            Long.class,
            user.toString());
    assertThat(total).isEqualTo(2L);
  }
}
