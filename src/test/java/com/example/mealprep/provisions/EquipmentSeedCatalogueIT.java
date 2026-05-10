package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.testsupport.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the repeatable migration {@code R__provision_seed_equipment_catalogue.sql} populates the
 * {@code provision_equipment_catalogue} reference table to exactly 15 rows and is idempotent —
 * Flyway re-applies repeatables when their checksum changes; the {@code ON CONFLICT DO UPDATE}
 * upsert leaves row count stable across re-runs.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class EquipmentSeedCatalogueIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void catalogueHasExactly15Rows() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_equipment_catalogue", Long.class);
    assertThat(count).isEqualTo(15L);
  }

  @Test
  void catalogueContainsExpectedCanonicalNames() {
    Long ovenCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_equipment_catalogue WHERE name = 'oven'", Long.class);
    assertThat(ovenCount).isEqualTo(1L);
    String ovenDisplay =
        jdbcTemplate.queryForObject(
            "SELECT display_name FROM provision_equipment_catalogue WHERE name = 'oven'",
            String.class);
    assertThat(ovenDisplay).isEqualTo("Oven");
  }

  @Test
  void seedIsIdempotent_reRunningOnConflictUpsertLeavesRowCountStable() {
    // Simulate a second seed run by re-executing the upsert directly.
    jdbcTemplate.update(
        "INSERT INTO provision_equipment_catalogue (name, display_name, sort_order)"
            + " VALUES ('oven', 'Oven', 10)"
            + " ON CONFLICT (name) DO UPDATE SET"
            + "   display_name = EXCLUDED.display_name,"
            + "   sort_order = EXCLUDED.sort_order");
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_equipment_catalogue", Long.class);
    assertThat(count).isEqualTo(15L);
  }
}
