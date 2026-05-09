package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.testsupport.TestContainersConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the DB CHECK constraints on {@code provision_inventory} are real and not dropped by the
 * migration. Each test inserts a row that violates exactly one constraint via raw {@link
 * JdbcTemplate} (bypassing the entity's {@code @PrePersist}) and asserts the DB rejects it with a
 * {@link DataIntegrityViolationException}.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class InventoryConstraintIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_inventory");
  }

  @Test
  void chk_tracking_quantity_rejectsQuantityModeWithoutQuantity() {
    Instant now = Instant.now();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    insertSql(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Cheddar",
                    "dairy",
                    "FRIDGE",
                    "QUANTITY",
                    /* quantity */ null,
                    "g",
                    /* cost_paid */ null,
                    /* status */ null,
                    false,
                    /* expiry_date */ null,
                    /* ingredient_mapping_key */ null,
                    /* notes */ null,
                    "MANUAL_ADD",
                    /* source_ref */ null,
                    "ACTIVE",
                    /* frozen_at */ null,
                    /* max_freeze_weeks */ null,
                    /* defrost_method */ null,
                    /* defrost_lead_time_hours */ null,
                    /* source_recipe_id */ null,
                    Timestamp.from(now),
                    Timestamp.from(now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void chk_tracking_quantity_rejectsQuantityModeWithoutUnit() {
    Instant now = Instant.now();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    insertSql(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Cheddar",
                    "dairy",
                    "FRIDGE",
                    "QUANTITY",
                    new java.math.BigDecimal("100.000"),
                    /* unit */ null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    "MANUAL_ADD",
                    null,
                    "ACTIVE",
                    null,
                    null,
                    null,
                    null,
                    null,
                    Timestamp.from(now),
                    Timestamp.from(now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void chk_tracking_status_rejectsStatusModeWithoutStatus() {
    Instant now = Instant.now();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    insertSql(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Salt",
                    "seasoning",
                    "SPICE_RACK",
                    "STATUS",
                    null,
                    null,
                    null,
                    /* status */ null,
                    true,
                    null,
                    null,
                    null,
                    "MANUAL_ADD",
                    null,
                    "ACTIVE",
                    null,
                    null,
                    null,
                    null,
                    null,
                    Timestamp.from(now),
                    Timestamp.from(now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void chk_quantity_nonneg_rejectsNegativeQuantity() {
    Instant now = Instant.now();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    insertSql(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Cheddar",
                    "dairy",
                    "FRIDGE",
                    "QUANTITY",
                    new java.math.BigDecimal("-1.000"),
                    "g",
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    "MANUAL_ADD",
                    null,
                    "ACTIVE",
                    null,
                    null,
                    null,
                    null,
                    null,
                    Timestamp.from(now),
                    Timestamp.from(now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static String insertSql() {
    return "INSERT INTO provision_inventory (id, user_id, name, category, storage_location,"
        + " tracking_mode, quantity, unit, cost_paid, status, is_staple, expiry_date,"
        + " ingredient_mapping_key, notes, source, source_ref, item_status, frozen_at,"
        + " max_freeze_weeks, defrost_method, defrost_lead_time_hours, source_recipe_id,"
        + " version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
        + " ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";
  }
}
