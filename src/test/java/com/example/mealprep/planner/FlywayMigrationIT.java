package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the four planner migrations land cleanly on a fresh Postgres 16 instance:
 *
 * <ul>
 *   <li>Tables and indexes from {@code V…120000-120300} exist;
 *   <li>{@code planner_meal_slots.eaters} and {@code planner_reopt_suggestions.affected_slot_ids}
 *       are {@code uuid[]};
 *   <li>{@code score_breakdown} and {@code rollup_summary} are {@code jsonb NOT NULL};
 *   <li>The partial unique index {@code uq_planner_plans_active_per_household_week} fires on a
 *       second {@code 'ACTIVE'} insert for the same (household, week) tuple — the locked-in casing
 *       fix.
 * </ul>
 *
 * <p>Spring Boot's {@code spring.jpa.hibernate.ddl-auto = validate} (the project default) would
 * already refuse to start if entity ↔ schema drift existed; this IT additionally pins the DB-level
 * shape so future migrations don't regress.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class FlywayMigrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void allFourPlannerTables_exist() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "SELECT tablename FROM pg_tables WHERE tablename LIKE 'planner_%' ORDER BY tablename",
            String.class);
    assertThat(tables)
        .containsExactly(
            "planner_days",
            "planner_meal_slots",
            "planner_plans",
            "planner_reopt_suggestions",
            "planner_scheduled_recipes");
  }

  @Test
  void plannerPlans_scoreBreakdownAndRollupSummary_areJsonbNotNull() {
    Map<String, Object> scoreCol = column("planner_plans", "score_breakdown");
    assertThat(scoreCol.get("data_type")).isEqualTo("jsonb");
    assertThat(scoreCol.get("is_nullable")).isEqualTo("NO");

    Map<String, Object> rollupCol = column("planner_plans", "rollup_summary");
    assertThat(rollupCol.get("data_type")).isEqualTo("jsonb");
    assertThat(rollupCol.get("is_nullable")).isEqualTo("NO");
  }

  @Test
  void plannerMealSlots_eaters_isUuidArrayNotNull() {
    Map<String, Object> col = column("planner_meal_slots", "eaters");
    assertThat(col.get("data_type")).isEqualTo("ARRAY");
    assertThat(col.get("udt_name")).isEqualTo("_uuid");
    assertThat(col.get("is_nullable")).isEqualTo("NO");
  }

  @Test
  void plannerReoptSuggestions_affectedSlotIds_isUuidArrayNotNull() {
    Map<String, Object> col = column("planner_reopt_suggestions", "affected_slot_ids");
    assertThat(col.get("data_type")).isEqualTo("ARRAY");
    assertThat(col.get("udt_name")).isEqualTo("_uuid");
    assertThat(col.get("is_nullable")).isEqualTo("NO");
  }

  @Test
  void expectedIndexes_present() {
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE indexname IN ("
                + "  'idx_planner_plans_household_week_status',"
                + "  'idx_planner_plans_household_week_gen',"
                + "  'idx_planner_plans_household_range',"
                + "  'idx_planner_plans_trace',"
                + "  'idx_planner_days_plan_date',"
                + "  'idx_planner_meal_slots_plan_state',"
                + "  'idx_planner_meal_slots_day',"
                + "  'idx_planner_scheduled_recipes_batch',"
                + "  'idx_planner_scheduled_recipes_recipe',"
                + "  'idx_planner_reopt_pending',"
                + "  'uq_planner_plans_active_per_household_week'"
                + ") ORDER BY indexname",
            String.class);
    assertThat(indexes)
        .containsExactly(
            "idx_planner_days_plan_date",
            "idx_planner_meal_slots_day",
            "idx_planner_meal_slots_plan_state",
            "idx_planner_plans_household_range",
            "idx_planner_plans_household_week_gen",
            "idx_planner_plans_household_week_status",
            "idx_planner_plans_trace",
            "idx_planner_reopt_pending",
            "idx_planner_scheduled_recipes_batch",
            "idx_planner_scheduled_recipes_recipe",
            "uq_planner_plans_active_per_household_week");
  }

  @Test
  void partialUniqueIndex_fires_onSecondActiveInsert() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2099, 1, 4);
    insertPlan(householdId, week, "ACTIVE");
    assertThatThrownBy(() -> insertPlan(householdId, week, "ACTIVE"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void partialUniqueIndex_allowsMultipleNonActivePlans_forSameHouseholdWeek() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2099, 2, 8);
    insertPlan(householdId, week, "GENERATED");
    insertPlan(householdId, week, "GENERATED");
    insertPlan(householdId, week, "SUPERSEDED");

    Integer rowCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM planner_plans WHERE household_id = ? AND week_start_date = ?",
            Integer.class,
            householdId,
            java.sql.Date.valueOf(week));
    assertThat(rowCount).isEqualTo(3);
  }

  private void insertPlan(UUID householdId, LocalDate week, String status) {
    Instant now = Instant.now();
    jdbcTemplate.update(
        "INSERT INTO planner_plans ("
            + "  id, household_id, week_start_date, generation, status, trigger_kind,"
            + "  quality_warning, cold_start, ai_augmented, trace_id, decision_id,"
            + "  score_breakdown, rollup_summary, version, created_at, updated_at"
            + ") VALUES ("
            + "  ?, ?, ?, 1, ?, 'USER_INITIATED',"
            + "  false, false, false, ?, ?,"
            + "  '{}'::jsonb, '{}'::jsonb, 0, ?, ?)",
        UUID.randomUUID(),
        householdId,
        java.sql.Date.valueOf(week),
        status,
        UUID.randomUUID(),
        UUID.randomUUID(),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
  }

  private Map<String, Object> column(String table, String columnName) {
    return jdbcTemplate.queryForMap(
        "SELECT * FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
        table,
        columnName);
  }
}
