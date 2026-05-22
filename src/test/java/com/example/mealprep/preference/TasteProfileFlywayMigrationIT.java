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
 * Asserts the three 01c taste-profile migrations apply cleanly against Testcontainers Postgres and
 * produce the column types and indices the JPA entities expect. Booting the context at all proves
 * Flyway ran the migrations; the per-column assertions guard against silent type drift between the
 * SQL and the entity {@code @Column} definitions.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class TasteProfileFlywayMigrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void tasteProfile_documentIsJsonbNotNull_versionsAreCorrectIntegerTypes() {
    Map<String, Object> doc = column("preference_taste_profile", "document");
    assertThat(doc.get("data_type")).isEqualTo("jsonb");
    assertThat(doc.get("is_nullable")).isEqualTo("NO");

    Map<String, Object> docVersion = column("preference_taste_profile", "document_version");
    assertThat(docVersion.get("data_type")).isEqualTo("integer");
    assertThat(docVersion.get("is_nullable")).isEqualTo("NO");

    Map<String, Object> optVersion = column("preference_taste_profile", "optimistic_version");
    assertThat(optVersion.get("data_type")).isEqualTo("bigint");
    assertThat(optVersion.get("is_nullable")).isEqualTo("NO");

    Map<String, Object> vectorStatus = column("preference_taste_profile", "taste_vector_status");
    assertThat(vectorStatus.get("data_type")).isEqualTo("character varying");
    assertThat(vectorStatus.get("character_maximum_length")).hasToString("16");
    assertThat(vectorStatus.get("is_nullable")).isEqualTo("NO");
  }

  @Test
  void tasteProfile_userIdIsUnique() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes " + "WHERE indexname = 'idx_pref_taste_profile_user'",
            Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void allThreeTasteProfileTables_exist() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "SELECT tablename FROM pg_tables WHERE tablename LIKE 'preference_taste_profile%' "
                + "ORDER BY tablename",
            String.class);
    // contains (not containsExactly): the preference_taste_profile_archive table (added by
    // preference-01e) also matches the LIKE prefix. This test's intent is "the taste-profile
    // migration created its 3 tables", not "no future table may share the prefix".
    assertThat(tables)
        .contains(
            "preference_taste_profile",
            "preference_taste_profile_audit",
            "preference_taste_profile_versions");
  }

  @Test
  void versionsTable_documentSnapshotIsJsonb_deltasAppliedIsJsonb_uniqueOnProfileAndVersion() {
    assertThat(column("preference_taste_profile_versions", "document_snapshot").get("data_type"))
        .isEqualTo("jsonb");
    assertThat(column("preference_taste_profile_versions", "deltas_applied").get("data_type"))
        .isEqualTo("jsonb");

    // Unique (taste_profile_id, document_version).
    Integer uniqueCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid = c.conrelid "
                + "WHERE t.relname = 'preference_taste_profile_versions' AND c.contype = 'u'",
            Integer.class);
    assertThat(uniqueCount).isEqualTo(1);
  }

  @Test
  void versionsFk_isCascadeOnDelete() {
    String rule =
        jdbcTemplate.queryForObject(
            "SELECT confdeltype FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid = c.conrelid "
                + "WHERE t.relname = 'preference_taste_profile_versions' AND c.contype = 'f'",
            String.class);
    assertThat(rule).isEqualTo("c");
  }

  @Test
  void auditTable_changeTypeIsVarchar32_actorTypeIsVarchar16_cascadeFk() {
    assertThat(
            column("preference_taste_profile_audit", "change_type").get("character_maximum_length"))
        .hasToString("32");
    assertThat(
            column("preference_taste_profile_audit", "actor_type").get("character_maximum_length"))
        .hasToString("16");

    String rule =
        jdbcTemplate.queryForObject(
            "SELECT confdeltype FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid = c.conrelid "
                + "WHERE t.relname = 'preference_taste_profile_audit' AND c.contype = 'f'",
            String.class);
    assertThat(rule).isEqualTo("c");
  }

  @Test
  void auditTable_indexesPresent() {
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE indexname IN ("
                + "  'idx_pref_tp_audit_tp_time',"
                + "  'idx_pref_tp_audit_actor'"
                + ") ORDER BY indexname",
            String.class);
    assertThat(indexes).containsExactly("idx_pref_tp_audit_actor", "idx_pref_tp_audit_tp_time");
  }

  private Map<String, Object> column(String table, String column) {
    return jdbcTemplate.queryForMap(
        "SELECT * FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
        table,
        column);
  }
}
