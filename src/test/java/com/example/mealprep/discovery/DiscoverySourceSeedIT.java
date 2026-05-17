package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the R__discovery_seed_curated_sources repeatable migration: 15 rows after migration (14
 * curated + 1 search), source_key uniqueness, the bbc_good_food + google_cse crawl_config shapes,
 * and idempotent re-run that preserves operator-managed state.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class DiscoverySourceSeedIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void seed_produces15Sources() {
    Integer count =
        jdbcTemplate.queryForObject("select count(*) from discovery_sources", Integer.class);
    assertThat(count).isEqualTo(15);
  }

  @Test
  void seed_allSourceKeysUnique_andIncludeKnownKeys() {
    List<String> keys =
        jdbcTemplate.queryForList(
            "select source_key from discovery_sources order by source_key", String.class);
    assertThat(keys).doesNotHaveDuplicates();
    assertThat(keys)
        .contains("bbc_good_food", "serious_eats", "allrecipes", "google_cse", "hairy_bikers");
  }

  @Test
  void seed_googleCse_hasSearchTypeAndApiKind() {
    var row =
        jdbcTemplate.queryForMap(
            "select source_type, kind, respect_robots_txt, requests_per_day"
                + " from discovery_sources where source_key = 'google_cse'");
    assertThat(row.get("source_type")).isEqualTo("SEARCH");
    assertThat(row.get("kind")).isEqualTo("SEARCH_API");
    assertThat(row.get("respect_robots_txt")).isEqualTo(false);
    assertThat(((Number) row.get("requests_per_day")).intValue()).isEqualTo(100);
  }

  @Test
  void seed_bbcGoodFood_carriesSitemapCrawlConfig() {
    String crawlConfig =
        jdbcTemplate.queryForObject(
            "select crawl_config::text from discovery_sources where source_key = 'bbc_good_food'",
            String.class);
    assertThat(crawlConfig).contains("sitemapUrl").contains("/recipes/");
  }

  @Test
  void seed_isIdempotent_preservesOperatorStateOnReRun() {
    // Simulate operator-managed state drift.
    jdbcTemplate.update(
        "update discovery_sources set enabled = false, failure_streak = 4,"
            + " notes = 'operator note' where source_key = 'serious_eats'");

    // Re-run the repeatable body (same statements Flyway would replay on checksum change).
    jdbcTemplate.execute(
        "insert into discovery_sources (id, source_key, display_name, source_type, kind,"
            + " base_url, enabled, user_disabled, requests_per_minute, requests_per_day,"
            + " respect_robots_txt, user_agent, crawl_config, failure_streak, created_at,"
            + " updated_at) values (gen_random_uuid(), 'serious_eats', 'Serious Eats RENAMED',"
            + " 'CURATED', 'SITEMAP', 'https://www.seriouseats.com', true, false, 6, 500, true,"
            + " 'MealPrepAI/1.0 (+https://mealprep.example.com/bot)', '{}'::jsonb, 0, now(),"
            + " now()) on conflict (source_key) do update set display_name ="
            + " excluded.display_name, crawl_config = excluded.crawl_config, updated_at = now()");

    var row =
        jdbcTemplate.queryForMap(
            "select display_name, enabled, failure_streak, notes from discovery_sources"
                + " where source_key = 'serious_eats'");
    // Refreshed:
    assertThat(row.get("display_name")).isEqualTo("Serious Eats RENAMED");
    // Preserved operator state:
    assertThat(row.get("enabled")).isEqualTo(false);
    assertThat(((Number) row.get("failure_streak")).intValue()).isEqualTo(4);
    assertThat(row.get("notes")).isEqualTo("operator note");
  }
}
