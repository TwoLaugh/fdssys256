package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Asserts the 01e archive migration applies cleanly against Testcontainers Postgres and produces
 * the column types + indices the {@code PreferenceArchiveEntry} entity expects. Booting the context
 * at all proves Flyway ran and {@code ddl-auto=validate} accepts the entity→schema mapping.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PreferenceArchiveFlywayMigrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void archiveTable_exists_withExpectedColumnTypes() {
    assertThat(column("item_payload").get("data_type")).isEqualTo("jsonb");
    assertThat(column("item_payload").get("is_nullable")).isEqualTo("NO");

    assertThat(column("field_path").get("data_type")).isEqualTo("character varying");
    assertThat(column("field_path").get("character_maximum_length")).hasToString("128");
    assertThat(column("field_path").get("is_nullable")).isEqualTo("NO");

    assertThat(column("item_key").get("character_maximum_length")).hasToString("128");

    assertThat(column("evidence_count").get("data_type")).isEqualTo("integer");
    assertThat(column("evidence_count").get("is_nullable")).isEqualTo("NO");

    assertThat(column("last_signal_at").get("data_type")).isEqualTo("date");
    assertThat(column("last_signal_at").get("is_nullable")).isEqualTo("YES");

    assertThat(column("archived_at").get("data_type")).isEqualTo("timestamp with time zone");
    assertThat(column("archived_at").get("is_nullable")).isEqualTo("NO");

    assertThat(column("archived_reason").get("character_maximum_length")).hasToString("32");
    assertThat(column("archived_reason").get("is_nullable")).isEqualTo("NO");

    assertThat(column("re_promoted_at").get("data_type")).isEqualTo("timestamp with time zone");
    assertThat(column("re_promoted_at").get("is_nullable")).isEqualTo("YES");
  }

  @Test
  void archiveTable_indexesPresent() {
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE indexname IN ("
                + "  'idx_pref_archive_user_field_key',"
                + "  'idx_pref_archive_user_archived_at'"
                + ") ORDER BY indexname",
            String.class);
    assertThat(indexes)
        .containsExactly("idx_pref_archive_user_archived_at", "idx_pref_archive_user_field_key");
  }

  private Map<String, Object> column(String column) {
    return jdbcTemplate.queryForMap(
        "SELECT * FROM information_schema.columns "
            + "WHERE table_name = 'preference_taste_profile_archive' AND column_name = ?",
        column);
  }
}
