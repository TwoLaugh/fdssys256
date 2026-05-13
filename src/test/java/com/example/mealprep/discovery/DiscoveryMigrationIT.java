package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeSkipReason;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schema-level integration test for the discovery 01a migrations. Verifies that the three new
 * tables, all named indexes (per LLD invariants 1, 5, 10), the {@code job_id} FK with {@code ON
 * DELETE CASCADE}, and the absence of a {@code recipe_id} FK to {@code recipe_recipes} all line up
 * with what the LLD specifies. Also exercises JSONB round-trip discipline for {@code DiscoveryJob}
 * (constraints + sources lists) and append-only insert for {@code DiscoveryScrapeLog}.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class DiscoveryMigrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EntityManager entityManager;
  @Autowired private ObjectMapper objectMapper;

  // ------------------------------------------------------------------
  // Schema assertions
  // ------------------------------------------------------------------

  @Test
  void allThreeTables_existInPublicSchema() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "select tablename from pg_tables where schemaname = 'public'"
                + " and tablename in ('discovery_sources', 'discovery_jobs', 'discovery_scrape_log')",
            String.class);
    assertThat(tables)
        .containsExactlyInAnyOrder("discovery_sources", "discovery_jobs", "discovery_scrape_log");
  }

  @Test
  void allDiscoveryIndexes_existPerLld() {
    Set<String> expectedIndexes =
        Set.of(
            "idx_discovery_sources_enabled",
            "idx_discovery_sources_kind",
            "idx_discovery_jobs_user_status",
            "idx_discovery_jobs_status_started",
            "idx_discovery_jobs_trace",
            "idx_discovery_scrape_log_job",
            "idx_discovery_scrape_log_source_time",
            "idx_discovery_scrape_log_fingerprint",
            "idx_discovery_scrape_log_robots",
            "idx_discovery_scrape_log_recipe");

    List<String> actual =
        jdbcTemplate.queryForList(
            "select indexname from pg_indexes where schemaname = 'public'"
                + " and indexname like 'idx_discovery_%'",
            String.class);

    assertThat(actual).containsExactlyInAnyOrderElementsOf(expectedIndexes);
  }

  @Test
  void partialIndexes_haveWhereClause() {
    // idx_discovery_sources_enabled, idx_discovery_jobs_status_started,
    // idx_discovery_scrape_log_fingerprint, idx_discovery_scrape_log_robots,
    // idx_discovery_scrape_log_recipe are partial.
    List<String> partialIndexDefs =
        jdbcTemplate.queryForList(
            "select indexdef from pg_indexes where schemaname = 'public'"
                + " and indexname in ("
                + " 'idx_discovery_sources_enabled',"
                + " 'idx_discovery_jobs_status_started',"
                + " 'idx_discovery_scrape_log_fingerprint',"
                + " 'idx_discovery_scrape_log_robots',"
                + " 'idx_discovery_scrape_log_recipe')",
            String.class);

    assertThat(partialIndexDefs).hasSize(5);
    for (String def : partialIndexDefs) {
      assertThat(def).contains("WHERE");
    }
  }

  @Test
  void discoveryScrapeLog_jobId_isFkWithCascadeDelete() {
    // confdeltype 'c' = CASCADE per pg_constraint docs.
    String fkDeleteRule =
        jdbcTemplate.queryForObject(
            "select confdeltype from pg_constraint"
                + " where conrelid = 'discovery_scrape_log'::regclass"
                + " and confrelid = 'discovery_jobs'::regclass"
                + " and contype = 'f'",
            String.class);
    assertThat(fkDeleteRule).isEqualTo("c");
  }

  @Test
  void discoveryScrapeLog_recipeId_isNotForeignKeyToRecipeRecipes() {
    Integer fkCount =
        jdbcTemplate.queryForObject(
            "select count(*) from pg_constraint"
                + " where conrelid = 'discovery_scrape_log'::regclass"
                + " and confrelid = 'recipe_recipes'::regclass"
                + " and contype = 'f'",
            Integer.class);
    assertThat(fkCount).isZero();
  }

  // ------------------------------------------------------------------
  // Entity round-trip assertions
  // ------------------------------------------------------------------

  @Test
  @Transactional
  void discoverySource_persistAndReload_preservesJsonbCrawlConfig() {
    DiscoverySource source = DiscoveryTestData.sampleSource("src_round_trip");
    entityManager.persist(source);
    entityManager.flush();
    entityManager.clear();

    DiscoverySource reloaded = entityManager.find(DiscoverySource.class, source.getId());
    assertThat(reloaded).isNotNull();
    assertThat(reloaded.getSourceKey()).isEqualTo("src_round_trip");
    assertThat(reloaded.getCrawlConfig()).isNotNull();
    assertThat(reloaded.getCrawlConfig().path("sitemap_url").asText())
        .isEqualTo("https://example.test/sitemap.xml");
    assertThat(reloaded.isEnabled()).isTrue();
  }

  @Test
  @Transactional
  void discoveryJob_persistAndReload_preservesJsonbConstraintsAndSourcesLists() throws Exception {
    UUID userId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
    job.setSourcesRequested(new ArrayList<>(List.of("src_a", "src_b")));
    job.setSourcesSucceeded(new ArrayList<>(List.of("src_a")));
    job.setSourcesFailed(new ArrayList<>(List.of("src_b")));

    entityManager.persist(job);
    entityManager.flush();
    entityManager.clear();

    DiscoveryJob reloaded = entityManager.find(DiscoveryJob.class, job.getId());
    assertThat(reloaded).isNotNull();
    assertThat(reloaded.getStatus()).isEqualTo(DiscoveryJobStatus.QUEUED);
    assertThat(reloaded.getSourcesRequested()).containsExactly("src_a", "src_b");
    assertThat(reloaded.getSourcesSucceeded()).containsExactly("src_a");
    assertThat(reloaded.getSourcesFailed()).containsExactly("src_b");

    // Round-trip the JSONB constraints document.
    DiscoveryConstraints constraints =
        objectMapper.treeToValue(reloaded.getConstraintsJson(), DiscoveryConstraints.class);
    assertThat(constraints.schemaVersion()).isEqualTo(1);
    assertThat(constraints.requiredCuisines()).containsExactly("East Asian");
    assertThat(constraints.mustExcludeIngredientMappingKeys()).containsExactly("peanuts");
  }

  @Test
  @Transactional
  void discoveryScrapeLog_insertWithSparseFields_roundTripsCleanly() {
    // First save a job because of the FK from scrape_log.job_id.
    DiscoveryJob job = DiscoveryTestData.sampleJob(UUID.randomUUID());
    entityManager.persist(job);

    DiscoveryScrapeLog skipped =
        DiscoveryScrapeLog.builder()
            .id(UUID.randomUUID())
            .jobId(job.getId())
            .sourceKey("src_a")
            .candidateUrl("https://example.test/skipped")
            .status(ScrapeOutcome.SKIPPED)
            .robotsTxtOutcome(RobotsTxtOutcome.SKIPPED)
            .skipReason(ScrapeSkipReason.RATE_LIMITED)
            .occurredAt(Instant.now())
            .build();

    entityManager.persist(skipped);
    entityManager.flush();
    entityManager.clear();

    DiscoveryScrapeLog reloaded = entityManager.find(DiscoveryScrapeLog.class, skipped.getId());
    assertThat(reloaded).isNotNull();
    assertThat(reloaded.getStatus()).isEqualTo(ScrapeOutcome.SKIPPED);
    assertThat(reloaded.getSkipReason()).isEqualTo(ScrapeSkipReason.RATE_LIMITED);
    assertThat(reloaded.getHttpStatusCode()).isNull();
    assertThat(reloaded.getRecipeId()).isNull();
    assertThat(reloaded.getContentFingerprint()).isNull();
  }

  @Test
  @Transactional
  void discoveryScrapeLog_longestEnumValue_HARD_CONSTRAINT_VIOLATION_fitsInStatusColumn() {
    DiscoveryJob job = DiscoveryTestData.sampleJob(UUID.randomUUID());
    entityManager.persist(job);

    DiscoveryScrapeLog row =
        DiscoveryScrapeLog.builder()
            .id(UUID.randomUUID())
            .jobId(job.getId())
            .sourceKey("src_a")
            .candidateUrl("https://example.test/blocked")
            .status(ScrapeOutcome.HARD_CONSTRAINT_VIOLATION)
            .robotsTxtOutcome(RobotsTxtOutcome.ALLOWED)
            .skipReason(ScrapeSkipReason.HARD_CONSTRAINT)
            .occurredAt(Instant.now())
            .build();

    entityManager.persist(row);
    entityManager.flush();
    entityManager.clear();

    DiscoveryScrapeLog reloaded = entityManager.find(DiscoveryScrapeLog.class, row.getId());
    assertThat(reloaded.getStatus()).isEqualTo(ScrapeOutcome.HARD_CONSTRAINT_VIOLATION);
  }
}
