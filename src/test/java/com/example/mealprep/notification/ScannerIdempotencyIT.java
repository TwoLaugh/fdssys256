package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.scanner.ExpiryWarningScanner;
import com.example.mealprep.notification.scanner.StapleReplenishmentScanner;
import com.example.mealprep.notification.scanner.internal.DispatchLogCleanupScheduler;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.sql.Timestamp;
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
 * Testcontainers IT proving the scanner idempotency tables fence a re-run within the same window
 * against real Postgres, that the {@code AFTER_COMMIT} dispatch path produces notifications, and
 * that the retention sweep prunes aged dispatch-log rows. A {@code @Primary} fixed {@link Clock}
 * pins time so the scan windows are deterministic.
 */
@SpringBootTest
@Import({TestContainersConfig.class, ScannerIdempotencyIT.FixedClockConfig.class})
@ActiveProfiles("test")
class ScannerIdempotencyIT {

  /** 2026-06-15 is a Monday; 06:00 UTC is inside the expiry/staple scan logic (date-only). */
  static final Instant NOW = Instant.parse("2026-06-15T06:00:00Z");

  static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

  @Autowired private ExpiryWarningScanner expiryWarningScanner;
  @Autowired private StapleReplenishmentScanner stapleReplenishmentScanner;
  @Autowired private DispatchLogCleanupScheduler cleanupScheduler;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM notification_delivery_log");
    jdbcTemplate.update("DELETE FROM notifications");
    jdbcTemplate.update("DELETE FROM notification_preferences");
    jdbcTemplate.update("DELETE FROM expiry_warning_dispatch_log");
    jdbcTemplate.update("DELETE FROM staple_replenishment_dispatch_log");
    jdbcTemplate.update("DELETE FROM defrost_reminder_dispatch_log");
    jdbcTemplate.update("DELETE FROM prep_reminder_dispatch_log");
    jdbcTemplate.update("DELETE FROM nutrition_alert_dispatch_log");
    jdbcTemplate.update("DELETE FROM provision_inventory");
  }

  @Test
  void expiryWarning_runTwiceSameDay_secondIsNoOp() {
    UUID user = UUID.randomUUID();
    insertFridgeItem(user, TODAY.plusDays(1)); // within the 2-day fridge threshold

    int first = expiryWarningScanner.scan();
    int second = expiryWarningScanner.scan();

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
    assertThat(countDispatchRows("expiry_warning_dispatch_log", user)).isEqualTo(1);
    // The AFTER_COMMIT listener produced exactly one notification for the user.
    assertThat(countNotifications(user, "PROVISION_ITEM_NEAR_EXPIRY")).isEqualTo(1);
  }

  @Test
  void stapleReplenishment_runTwiceSameDay_secondIsNoOp() {
    UUID user = UUID.randomUUID();
    insertStaple(user); // LOW staple

    int first = stapleReplenishmentScanner.scan();
    int second = stapleReplenishmentScanner.scan();

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
    assertThat(countDispatchRows("staple_replenishment_dispatch_log", user)).isEqualTo(1);
    assertThat(countNotifications(user, "STAPLE_REPLENISHMENT_NEEDED")).isEqualTo(1);
  }

  @Test
  void cleanupSweep_deletesRowsOlderThanRetention_keepsRecent() {
    UUID user = UUID.randomUUID();
    // One aged row (40 days old) + one recent row (1 day old). Retention is 30 days.
    insertExpiryLog(user, NOW.minusSeconds(40L * 86_400));
    insertExpiryLog(user, NOW.minusSeconds(86_400));

    int deleted = cleanupScheduler.sweep();

    assertThat(deleted).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM expiry_warning_dispatch_log", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void emptyInventory_scanIsCleanNoOp() {
    assertThat(expiryWarningScanner.scan()).isZero();
    assertThat(stapleReplenishmentScanner.scan()).isZero();
  }

  // ---------------- seeding helpers ----------------

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

  private void insertStaple(UUID user) {
    jdbcTemplate.update(
        "INSERT INTO provision_inventory (id, user_id, name, category, storage_location,"
            + " tracking_mode, status, is_staple, ingredient_mapping_key, source, item_status,"
            + " version, created_at, updated_at) VALUES"
            + " (?::uuid, ?::uuid, 'paprika', 'spice', 'SPICE_RACK', 'STATUS', 'LOW', true,"
            + " 'paprika', 'MANUAL_ADD', 'ACTIVE', 0, now(), now())",
        UUID.randomUUID().toString(),
        user.toString());
  }

  private void insertExpiryLog(UUID user, Instant firedAt) {
    jdbcTemplate.update(
        "INSERT INTO expiry_warning_dispatch_log (id, user_id, scan_date, fired_at, item_count)"
            + " VALUES (?::uuid, ?::uuid, ?, ?, 1)",
        UUID.randomUUID().toString(),
        user.toString(),
        java.sql.Date.valueOf(LocalDate.ofInstant(firedAt, ZoneOffset.UTC)),
        Timestamp.from(firedAt));
  }

  private long countDispatchRows(String table, UUID user) {
    Long c =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE user_id = ?::uuid",
            Long.class,
            user.toString());
    return c == null ? 0 : c;
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
