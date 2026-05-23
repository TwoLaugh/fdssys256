package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.scanner.ExpiryWarningScanner;
import com.example.mealprep.notification.scanner.PrepReminderScanner;
import com.example.mealprep.notification.scanner.StapleReplenishmentScanner;
import com.example.mealprep.notification.scanner.internal.DispatchLogCleanupScheduler;
import com.example.mealprep.testsupport.TestContainersConfig;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
  @Autowired private PrepReminderScanner prepReminderScanner;
  @Autowired private DispatchLogCleanupScheduler cleanupScheduler;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;

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
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM planner_plans");
    jdbcTemplate.update("DELETE FROM household_member");
    jdbcTemplate.update("DELETE FROM household");
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

  @Test
  void prepReminder_runTwiceSameWindow_secondIsNoOp_andPrepStepAtTimeIsStable() {
    UUID user = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    UUID slot = UUID.randomUUID();
    // The shared fixed clock is 2026-06-15T06:00Z. Seed the slot's meal_time override to 06:15 with
    // a 15-min budget so the resolved prep moment (06:15 − 15min) == 06:00 == NOW, inside the
    // 15-min lead window. The user must hold active inventory to be enumerated by the scanner.
    insertFridgeItem(user, TODAY.plusDays(5));
    seedHouseholdWithPrimary(household, user);
    seedActivePlanWithDinnerSlot(household, slot, TODAY, LocalTime.of(6, 15), 15);

    int first = prepReminderScanner.scan();
    int second = prepReminderScanner.scan();

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
    assertThat(countDispatchRows("prep_reminder_dispatch_log", user)).isEqualTo(1);
    // The recomputed prepStepAtTime is stable across runs: exactly one logged moment, == 06:00Z.
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT prep_step_at_time) FROM prep_reminder_dispatch_log"
                    + " WHERE slot_id = ?::uuid",
                Long.class,
                slot.toString()))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT prep_step_at_time FROM prep_reminder_dispatch_log WHERE slot_id = ?::uuid",
                Timestamp.class,
                slot.toString()))
        .isEqualTo(Timestamp.from(NOW));
    assertThat(countNotifications(user, "PLANNER_PREP_REMINDER")).isEqualTo(1);
  }

  @Test
  void prepReminder_scanDoesNotWriteToPlannerOrHouseholdTables() {
    UUID user = UUID.randomUUID();
    UUID household = UUID.randomUUID();
    UUID slot = UUID.randomUUID();
    insertFridgeItem(user, TODAY.plusDays(5));
    seedHouseholdWithPrimary(household, user);
    seedActivePlanWithDinnerSlot(household, slot, TODAY, LocalTime.of(6, 15), 15);

    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    stats.clear();

    int fired = prepReminderScanner.scan();

    assertThat(fired).isEqualTo(1);
    // The cross-module meal-time read is read-only: the scan inserts only its own dispatch-log row
    // (+ the AFTER_COMMIT notification), and never updates/deletes any source-module entity.
    assertThat(stats.getEntityUpdateCount()).isZero();
    assertThat(stats.getEntityDeleteCount()).isZero();
    // Source planner/household rows are untouched.
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM planner_meal_slots", Long.class))
        .isEqualTo(1L);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM household_member", Long.class))
        .isEqualTo(1L);
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

  private void seedHouseholdWithPrimary(UUID household, UUID user) {
    jdbcTemplate.update(
        "INSERT INTO household (id, name, created_by_user_id, version, created_at, updated_at)"
            + " VALUES (?::uuid, 'h', ?::uuid, 0, now(), now())",
        household.toString(),
        user.toString());
    jdbcTemplate.update(
        "INSERT INTO household_member (id, household_id, user_id, role, priority, joined_at,"
            + " version, created_at, updated_at) VALUES"
            + " (?::uuid, ?::uuid, ?::uuid, 'primary', 100, now(), 0, now(), now())",
        UUID.randomUUID().toString(),
        household.toString(),
        user.toString());
  }

  private void seedActivePlanWithDinnerSlot(
      UUID household, UUID slot, LocalDate onDate, LocalTime mealTime, int timeBudgetMin) {
    UUID plan = UUID.randomUUID();
    UUID day = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO planner_plans (id, household_id, week_start_date, generation, status,"
            + " trigger_kind, trace_id, decision_id, created_at, updated_at) VALUES"
            + " (?::uuid, ?::uuid, ?, 1, 'ACTIVE', 'INITIAL', ?::uuid, ?::uuid, now(), now())",
        plan.toString(),
        household.toString(),
        java.sql.Date.valueOf(onDate),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString());
    jdbcTemplate.update(
        "INSERT INTO planner_days (id, plan_id, on_date) VALUES (?::uuid, ?::uuid, ?)",
        day.toString(),
        plan.toString(),
        java.sql.Date.valueOf(onDate));
    jdbcTemplate.update(
        "INSERT INTO planner_meal_slots (id, day_id, plan_id, slot_index, kind, label,"
            + " time_budget_min, shared, state, meal_time) VALUES"
            + " (?::uuid, ?::uuid, ?::uuid, 0, 'DINNER', 'Dinner', ?, false, 'PLANNED', ?)",
        slot.toString(),
        day.toString(),
        plan.toString(),
        timeBudgetMin,
        java.sql.Time.valueOf(mealTime));
    jdbcTemplate.update(
        "INSERT INTO planner_scheduled_recipes (id, slot_id, recipe_id, recipe_version_id,"
            + " recipe_branch_id, servings) VALUES"
            + " (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 2)",
        UUID.randomUUID().toString(),
        slot.toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString());
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
