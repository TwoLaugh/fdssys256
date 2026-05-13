package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.testsupport.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the adaptation pipeline's six versioned migrations and two repeatable stubs run cleanly
 * against a fresh Postgres (Testcontainers). Implicitly covers {@code ddl-auto=validate} because
 * the Spring context only starts if Hibernate's schema-validate pass succeeds against the migrated
 * schema for every entity declared in the module.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationFlywayMigrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void all_six_adaptation_tables_exist() {
    assertTable("adaptation_jobs");
    assertTable("adaptation_pending_changes");
    assertTable("adaptation_traces");
    assertTable("adaptation_fingerprints");
    assertTable("adaptation_planner_hints");
    assertTable("adaptation_nutritional_knowledge");
  }

  @Test
  void partial_unique_index_on_pending_recipe_dim_active_exists() {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from pg_indexes where indexname ="
                + " 'idx_adaptation_pending_recipe_dim_active'",
            Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void gin_index_on_nutritional_knowledge_subject_keys_exists() {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from pg_indexes where indexname ="
                + " 'idx_adaptation_nut_knowledge_subjects_gin'",
            Integer.class);
    assertThat(count).isEqualTo(1);
  }

  private void assertTable(String name) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = ?",
            Integer.class,
            name);
    assertThat(count).as("table %s exists", name).isEqualTo(1);
  }
}
