package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.scanner.ExpiryWarningScanner;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Testcontainers IT backing the {@code @Profile("e2e")} {@code E2eNotificationScanController} →
 * {@code ExpiryWarningScanner.scan()} path that the un-pended NOTIF-09 E2E scenario drives. It
 * proves the exact precondition the scenario sets up over HTTP — a FRIDGE inventory item expiring
 * <em>within</em> the 2-day fridge threshold — results in a {@code PROVISION_ITEM_NEAR_EXPIRY}
 * notification for that user via the {@code AFTER_COMMIT} listener chain, against real Postgres.
 *
 * <p>The controller itself is a one-line pass-through to {@code scan()} (covered by {@code
 * E2eNotificationScanControllerTest}); it is {@code @Profile("e2e")} so it never loads under the
 * {@code test} profile. This IT therefore exercises the load-bearing half — the scan service the
 * trigger invokes — exactly as {@code ScannerIdempotencyIT} does, but pinned on the expiry boundary
 * the scenario relies on. A {@code @Primary} fixed {@link Clock} makes "tomorrow" stable.
 */
@SpringBootTest
@Import({TestContainersConfig.class, ExpiryWarningScanTriggerIT.FixedClockConfig.class})
@ActiveProfiles("test")
class ExpiryWarningScanTriggerIT {

  /** 06:00 UTC, date-only logic — matches the scanner's daily-window semantics. */
  static final Instant NOW = Instant.parse("2026-06-15T06:00:00Z");

  static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

  @Autowired private ExpiryWarningScanner expiryWarningScanner;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM notification_delivery_log");
    jdbcTemplate.update("DELETE FROM notifications");
    jdbcTemplate.update("DELETE FROM notification_preferences");
    jdbcTemplate.update("DELETE FROM expiry_warning_dispatch_log");
    jdbcTemplate.update("DELETE FROM provision_inventory");
  }

  @Test
  void fridgeItemExpiringTomorrow_scanProducesExpiryWarningNotification() {
    UUID user = UUID.randomUUID();
    // The scenario's precondition: a FRIDGE item expiring tomorrow (today + 1), inside the 2-day
    // fridge threshold — so the scan finds exactly this item for this user.
    insertFridgeItem(user, TODAY.plusDays(1));

    int fired = expiryWarningScanner.scan();

    assertThat(fired).isEqualTo(1);
    // The AFTER_COMMIT listener created exactly one expiry-warning notification for the user — the
    // row the un-pended scenario polls the inbox for.
    assertThat(countNotifications(user, "PROVISION_ITEM_NEAR_EXPIRY")).isEqualTo(1);
  }

  @Test
  void itemBeyondFridgeThreshold_scanIsCleanNoOp() {
    UUID user = UUID.randomUUID();
    // 5 days out is beyond the 2-day fridge threshold — the scan must not fire / create a row.
    insertFridgeItem(user, TODAY.plusDays(5));

    int fired = expiryWarningScanner.scan();

    assertThat(fired).isZero();
    assertThat(countNotifications(user, "PROVISION_ITEM_NEAR_EXPIRY")).isZero();
  }

  // ---------------- seeding helpers (mirror ScannerIdempotencyIT) ----------------

  private void insertFridgeItem(UUID user, LocalDate expiry) {
    jdbcTemplate.update(
        "INSERT INTO provision_inventory (id, user_id, name, category, storage_location,"
            + " tracking_mode, quantity, unit, is_staple, expiry_date, source, item_status,"
            + " version, created_at, updated_at) VALUES"
            + " (?::uuid, ?::uuid, 'milk', 'dairy', 'FRIDGE', 'QUANTITY', 1, 'l', false, ?, "
            + " 'MANUAL_ADD', 'ACTIVE', 0, now(), now())",
        UUID.randomUUID().toString(),
        user.toString(),
        java.sql.Date.valueOf(expiry));
  }

  private long countNotifications(UUID user, String kind) {
    Long c =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?::uuid AND kind = ?",
            Long.class,
            user.toString(),
            kind);
    return c == null ? 0 : c;
  }

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
