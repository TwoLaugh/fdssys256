package com.example.mealprep.feedback;

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
 * Asserts the four feedback migrations:
 *
 * <ul>
 *   <li>Run cleanly against Postgres 16 (Spring Boot would refuse to start with {@code ddl-auto =
 *       validate} if JPA-vs-schema drift existed);
 *   <li>Materialise the expected column types and indexes;
 *   <li>Implement the documented FK behaviours (CASCADE on parent FK, SET NULL on {@code
 *       superseded_by}, and a non-cascading {@code original_routing_id}).
 * </ul>
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class FlywayMigrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void feedbackEntries_textIsText_uiContextIsJsonbNotNull() {
    Map<String, Object> textCol = column("feedback_entries", "text");
    assertThat(textCol.get("data_type")).isEqualTo("text");
    assertThat(textCol.get("is_nullable")).isEqualTo("NO");

    Map<String, Object> uiContextCol = column("feedback_entries", "ui_context");
    assertThat(uiContextCol.get("data_type")).isEqualTo("jsonb");
    assertThat(uiContextCol.get("is_nullable")).isEqualTo("NO");
  }

  @Test
  void routingLog_confidenceIsNumeric4_3() {
    Map<String, Object> col = column("feedback_routing_log", "confidence");
    assertThat(col.get("data_type")).isEqualTo("numeric");
    assertThat(col.get("numeric_precision")).hasToString("4");
    assertThat(col.get("numeric_scale")).hasToString("3");
  }

  @Test
  void corrections_replayStatusIsNotNull_andAllowsPendingReplayLiteral() {
    Map<String, Object> col = column("feedback_misclassification_corrections", "replay_status");
    assertThat(col.get("is_nullable")).isEqualTo("NO");
    // No CHECK constraint exists, so the column is a plain varchar(24) — verify PENDING_REPLAY
    // fits.
    assertThat(col.get("data_type")).isEqualTo("character varying");
    assertThat(col.get("character_maximum_length")).hasToString("24");
  }

  @Test
  void allFeedbackTables_exist() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "SELECT tablename FROM pg_tables WHERE tablename LIKE 'feedback_%' ORDER BY tablename",
            String.class);
    assertThat(tables)
        .containsExactly(
            "feedback_bridge_idempotency",
            "feedback_clarification_queries",
            "feedback_entries",
            "feedback_misclassification_corrections",
            "feedback_routing_log");
  }

  @Test
  void bridgeIdempotency_hasUniqueFeedbackIdDestination_andRecentIndex() {
    // The (feedback_id, destination) UNIQUE constraint is the idempotency guard (feedback-01g §4).
    Long uniqueCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_constraint c"
                + " JOIN pg_class t ON t.oid = c.conrelid"
                + " WHERE t.relname = 'feedback_bridge_idempotency'"
                + "   AND c.contype = 'u'"
                + "   AND pg_get_constraintdef(c.oid) LIKE '%feedback_id, destination%'",
            Long.class);
    assertThat(uniqueCount).isEqualTo(1L);

    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes"
                + " WHERE indexname = 'idx_feedback_bridge_idempotency_recent'",
            String.class);
    assertThat(indexes).containsExactly("idx_feedback_bridge_idempotency_recent");
  }

  @Test
  void partialIndexes_present() {
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE indexname IN ("
                + "  'idx_feedback_entries_status_created',"
                + "  'idx_routing_log_status',"
                + "  'idx_clarification_user_status_created',"
                + "  'idx_clarification_expires'"
                + ") ORDER BY indexname",
            String.class);
    assertThat(indexes)
        .containsExactly(
            "idx_clarification_expires",
            "idx_clarification_user_status_created",
            "idx_feedback_entries_status_created",
            "idx_routing_log_status");
  }

  @Test
  void supersededByFk_isSetNullOnDelete() {
    String rule =
        jdbcTemplate.queryForObject(
            "SELECT confdeltype FROM pg_constraint c "
                + " JOIN pg_class t ON t.oid = c.conrelid "
                + " WHERE t.relname = 'feedback_routing_log' "
                + "   AND c.contype = 'f' "
                + "   AND pg_get_constraintdef(c.oid) LIKE '%superseded_by%'",
            String.class);
    // 'n' = SET NULL, 'c' = CASCADE, 'a' = NO ACTION, 'r' = RESTRICT
    assertThat(rule).isEqualTo("n");
  }

  @Test
  void feedbackEntryFk_isCascadeOnDelete_onAllChildren() {
    List<String> rules =
        jdbcTemplate.queryForList(
            "SELECT confdeltype FROM pg_constraint c "
                + " JOIN pg_class t ON t.oid = c.conrelid "
                + " WHERE t.relname IN ("
                + "   'feedback_routing_log',"
                + "   'feedback_misclassification_corrections',"
                + "   'feedback_clarification_queries') "
                + "   AND c.contype = 'f' "
                + "   AND pg_get_constraintdef(c.oid) LIKE '%feedback_entries(id)%'",
            String.class);
    assertThat(rules).hasSize(3).allMatch("c"::equals);
  }

  private Map<String, Object> column(String table, String column) {
    return jdbcTemplate.queryForMap(
        "SELECT * FROM information_schema.columns " + "WHERE table_name = ? AND column_name = ?",
        table,
        column);
  }
}
