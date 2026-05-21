package com.example.mealprep.core.origin;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.testsupport.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the two core-02b migrations run cleanly against a fresh Postgres (Testcontainers): the
 * {@code auth_service_tokens} table + its partial index, and the additive {@code (actor_type,
 * origin_trace)} columns on every existing audit-log table. Context startup implicitly covers
 * {@code ddl-auto=validate} for the new {@link
 * com.example.mealprep.auth.domain.entity.ServiceToken} entity against the migrated schema.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class OriginAuditMigrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void auth_service_tokens_table_and_index_exist() {
    assertTable("auth_service_tokens");
    Integer idx =
        jdbcTemplate.queryForObject(
            "select count(*) from pg_indexes where indexname = 'idx_auth_service_tokens_enabled'",
            Integer.class);
    assertThat(idx).isEqualTo(1);
  }

  @Test
  void audit_origin_columns_added_to_every_existing_audit_table() {
    assertColumns("preference_hard_constraints_audit");
    assertColumns("household_settings_audit");
    assertColumns("nutrition_targets_audit");
    assertColumns("nutrition_intake_audit");
    assertColumns("provision_inventory_audit");
  }

  @Test
  void audit_origin_columns_are_nullable() {
    // Backward-compat: existing inserts that omit these columns must remain valid.
    String nullable =
        jdbcTemplate.queryForObject(
            "select is_nullable from information_schema.columns"
                + " where table_name = 'preference_hard_constraints_audit'"
                + " and column_name = 'actor_type'",
            String.class);
    assertThat(nullable).isEqualTo("YES");
  }

  private void assertTable(String name) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = ?",
            Integer.class,
            name);
    assertThat(count).as("table %s exists", name).isEqualTo(1);
  }

  private void assertColumns(String table) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns"
                + " where table_name = ? and column_name in ('actor_type', 'origin_trace')",
            Integer.class,
            table);
    assertThat(count).as("table %s has actor_type + origin_trace columns", table).isEqualTo(2);
  }
}
