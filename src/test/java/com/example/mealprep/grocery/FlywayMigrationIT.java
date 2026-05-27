package com.example.mealprep.grocery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the six grocery migrations + the repeatable pack-size seed land cleanly on a fresh
 * Postgres 16 AND that the JPA mappings validate against the resulting schema with no drift.
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto=validate} is forced here (the {@code test} profile
 * defaults to {@code none}) so the context boot itself is the {@code ddl-auto=validate} proof
 * required by ticket-01a — Hibernate refuses to start if any grocery entity field has no matching
 * column. The DB-level assertions below additionally pin the shape so future migrations don't
 * regress.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class FlywayMigrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void allGroceryTables_exist() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "SELECT tablename FROM pg_tables WHERE tablename IN ("
                + "  'shopping_lists', 'shopping_list_lines', 'grocery_orders',"
                + "  'grocery_order_lines', 'grocery_provider_state',"
                + "  'grocery_substitution_proposals', 'grocery_price_history',"
                + "  'grocery_pack_size_heuristics'"
                + ") ORDER BY tablename",
            String.class);
    assertThat(tables)
        .containsExactly(
            "grocery_order_lines",
            "grocery_orders",
            "grocery_pack_size_heuristics",
            "grocery_price_history",
            "grocery_provider_state",
            "grocery_substitution_proposals",
            "shopping_list_lines",
            "shopping_lists");
  }

  @Test
  void shoppingLists_havesPlanGenerationColumn_notPlanRevision() {
    Map<String, Object> col = column("shopping_lists", "plan_generation");
    assertThat(col.get("data_type")).isEqualTo("integer");
    assertThat(col.get("is_nullable")).isEqualTo("NO");
    // The renamed-away column must NOT exist.
    Integer revisionCols =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_name = 'shopping_lists' AND column_name = 'plan_revision'",
            Integer.class);
    assertThat(revisionCols).isZero();
  }

  @Test
  void shoppingLists_uniqueOnPlanIdAndPlanGeneration_fires() {
    UUID planId = UUID.randomUUID();
    insertShoppingList(planId, 1);
    assertThatThrownBy(() -> insertShoppingList(planId, 1))
        .isInstanceOf(DuplicateKeyException.class);
    // A different generation for the same plan is allowed (supersession path).
    insertShoppingList(planId, 2);
    Integer rows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shopping_lists WHERE plan_id = ?", Integer.class, planId);
    assertThat(rows).isEqualTo(2);
  }

  @Test
  void priceHistory_isAppendOnly_noVersionNoUpdatedAt() {
    Integer drift =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_name = 'grocery_price_history'"
                + " AND column_name IN ('version', 'updated_at')",
            Integer.class);
    assertThat(drift).isZero();
    // created_at is the only audit column.
    assertThat(column("grocery_price_history", "created_at").get("data_type"))
        .isEqualTo("timestamp with time zone");
  }

  @Test
  void groceryOrders_automationFailureLog_isJsonbNotNullWithArrayDefault() {
    Map<String, Object> col = column("grocery_orders", "automation_failure_log");
    assertThat(col.get("data_type")).isEqualTo("jsonb");
    assertThat(col.get("is_nullable")).isEqualTo("NO");
    assertThat(String.valueOf(col.get("column_default"))).contains("[]");
  }

  @Test
  void providerState_sessionState_isNullableJsonb() {
    Map<String, Object> col = column("grocery_provider_state", "session_state");
    assertThat(col.get("data_type")).isEqualTo("jsonb");
    assertThat(col.get("is_nullable")).isEqualTo("YES");
  }

  @Test
  void packSizeHeuristics_checkConstraints_present() {
    List<String> checks =
        jdbcTemplate.queryForList(
            "SELECT conname FROM pg_constraint"
                + " WHERE conname IN ('chk_packsize_or_count', 'chk_match_target')"
                + " ORDER BY conname",
            String.class);
    assertThat(checks).containsExactly("chk_match_target", "chk_packsize_or_count");
  }

  @Test
  void packSizeHeuristics_check_packsizeOrCount_rejectsRowWithNeither() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO grocery_pack_size_heuristics"
                        + " (id, category, pack_unit, rank) VALUES (?, 'x', 'g', 1)",
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void packSizeSeed_hasStarterRows_forFlourEggsMilk() {
    Integer flour =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM grocery_pack_size_heuristics WHERE ingredient_mapping_key = 'flour'",
            Integer.class);
    Integer eggs =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM grocery_pack_size_heuristics WHERE ingredient_mapping_key = 'eggs'",
            Integer.class);
    Integer milk =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM grocery_pack_size_heuristics WHERE ingredient_mapping_key = 'milk'",
            Integer.class);
    assertThat(flour).isEqualTo(3); // 500g / 1kg / 1.5kg
    assertThat(eggs).isEqualTo(2); // 6 / 12
    assertThat(milk).isEqualTo(3); // 1pt / 2pt / 4pt
    Integer total =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM grocery_pack_size_heuristics", Integer.class);
    assertThat(total).isGreaterThanOrEqualTo(15);
  }

  @Test
  void expectedIndexes_present() {
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE indexname IN ("
                + "  'idx_shop_lists_user_plan_active',"
                + "  'idx_shop_lists_user_generated',"
                + "  'idx_shop_lines_list',"
                + "  'idx_shop_lines_list_mapping_key',"
                + "  'idx_grocery_orders_provider_order',"
                + "  'idx_grocery_orders_user_status_created',"
                + "  'idx_grocery_orders_shopping_list',"
                + "  'idx_grocery_order_lines_order',"
                + "  'idx_grocery_provider_state_user_provider',"
                + "  'idx_grocery_subs_order_status',"
                + "  'idx_grocery_price_hh_key_observed',"
                + "  'idx_grocery_price_hh_key_store_observed',"
                + "  'idx_grocery_price_user_observed',"
                + "  'idx_grocery_price_observed',"
                + "  'idx_grocery_pack_heur_key',"
                + "  'idx_grocery_pack_heur_category'"
                + ") ORDER BY indexname",
            String.class);
    assertThat(indexes).hasSize(16);
  }

  private void insertShoppingList(UUID planId, int planGeneration) {
    Instant now = Instant.now();
    jdbcTemplate.update(
        "INSERT INTO shopping_lists ("
            + "  id, user_id, plan_id, plan_generation, generated_at,"
            + "  estimated_total_currency, stale_ingredient_count, pantry_tracking_enabled,"
            + "  version, created_at, updated_at"
            + ") VALUES (?, ?, ?, ?, ?, 'GBP', 0, true, 0, ?, ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        planId,
        planGeneration,
        java.sql.Timestamp.from(now),
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
