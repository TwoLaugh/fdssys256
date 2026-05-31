package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.domain.service.NotificationUpdateService;
import com.example.mealprep.provisions.event.DefrostReminderEvent;
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

  /** Publish a defrost reminder for a specific meal slot (per-key bundling key = mealSlotId). */
  private void publishDefrost(UUID user, UUID mealSlotId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            t ->
                publisher.publishEvent(
                    new DefrostReminderEvent(
                        user,
                        null, // no household → dispatch straight to the event user, deterministic
                        UUID.randomUUID(),
                        mealSlotId,
                        Instant.now().plusSeconds(3600),
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

  /**
   * notification-3, real DB: a per-key kind (defrost reminder, keyed on {@code mealSlotId}) must
   * bundle onto the older same-key open row even when a newer different-key row of the same kind
   * was created in between. With a LIMIT-1 lookup the third event (slot A again) would only see the
   * newest row (slot B), miss slot A's open row, and wrongly open a third notification. The
   * page-scan finds slot A's row, so we end with two rows and slot A at {@code bundle_count = 2}.
   */
  @Test
  void perKey_olderSameKeyHiddenBehindNewerKey_bundlesOntoOlderRow() {
    UUID user = UUID.randomUUID();
    UUID slotA = UUID.randomUUID();
    UUID slotB = UUID.randomUUID();
    updateService.ensurePreferencesForUser(user);

    publishDefrost(user, slotA); // opens row A
    publishDefrost(user, slotB); // opens row B (now the newest open row for this kind)
    publishDefrost(user, slotA); // must bundle onto row A, NOT open a third row

    Long rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?::uuid AND kind = 'PROVISION_DEFROST_REMINDER' AND status = 'UNREAD'",
            Long.class,
            user.toString());
    assertThat(rows).isEqualTo(2L);

    Integer slotABundleCount =
        jdbcTemplate.queryForObject(
            "SELECT bundle_count FROM notifications WHERE user_id = ?::uuid AND kind = 'PROVISION_DEFROST_REMINDER' AND bundle_keys @> ?::jsonb",
            Integer.class,
            user.toString(),
            "[\"" + slotA + "\"]");
    assertThat(slotABundleCount).isEqualTo(2);
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
