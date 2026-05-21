package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testing.TestAiService;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeSkipReason;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoveryScrapeLogRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.domain.service.internal.CandidateFilterResult;
import com.example.mealprep.discovery.exception.ExtractionFailedException;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Testcontainers IT driving the LIVE {@code DiscoveryJobRunner} through the synchronous admin
 * endpoint with purpose-built test {@code DiscoverySource} beans, so the runner's per-candidate
 * fetch-phase and search-phase branches execute against the real Postgres-backed {@code
 * DiscoveryJobTransitions} ({@code @Transactional} scrape-row writes, counter bumps, terminal
 * transition) rather than the Mockito stubs used by {@code DiscoveryJobRunnerTest}. JaCoCo line
 * coverage only credits these real persistence paths when they run inside a Testcontainers IT.
 *
 * <p>Each test posts a COLD_START job to {@code /admin/jobs/sync} (the established LIVE-runner
 * entry point per {@code DiscoveryRunJobSyncIT}; the runner runs to a terminal state synchronously
 * inside the request), then asserts the persisted {@code discovery_scrape_log} rows and the
 * terminal job counters/status. Distinct source keys per scenario keep the live runner
 * deterministic.
 *
 * <p>Reuses the established {@code @SpringBootTest + @AutoConfigureMockMvc + TestContainersConfig}
 * context convention (no OpenApi validator here — these assert persisted state, not contract shape)
 * with a single nested {@code @TestConfiguration} of stub source beans, mirroring {@code
 * DiscoveryRunJobSyncIT.SyncSourcesConfig} to avoid context-cache thrash.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, DiscoveryRunnerPhasesIT.PhaseSourcesConfig.class})
@ActiveProfiles("test")
class DiscoveryRunnerPhasesIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private DiscoverySourceRepository sourceRepository;
  @Autowired private DiscoveryJobRepository jobRepository;
  @Autowired private DiscoveryScrapeLogRepository scrapeLogRepository;
  @Autowired private TestAiService testAiService;

  @BeforeEach
  void seedAiCannedResponse() {
    // discovery-01g: the AI candidate filter dispatches one cheap-tier task per candidate.
    // Register a default "accept high-confidence" response so the phase tests' deterministic
    // candidates pass the filter and exercise downstream fetch / persist branches.
    testAiService.register(
        TaskType.DISCOVERY_FILTERING,
        new CandidateFilterResult(true, new BigDecimal("0.90"), "looks like a recipe"));
  }

  @AfterEach
  void cleanup() {
    // Children (scrape-log → discovery_jobs FK) before parents. The sync endpoint blocks until the
    // runner reaches a terminal state, so no live runner thread outlives the test to write a
    // scrape row after this delete (the wave-3 FK-violation trap applies to async POST /jobs, not
    // the synchronous /admin/jobs/sync path).
    jdbcTemplate.update("DELETE FROM discovery_scrape_log");
    jdbcTemplate.update("DELETE FROM discovery_jobs");
    jdbcTemplate.update("DELETE FROM discovery_sources");
    // discovery-01g writes WEB_DISCOVERED rows into the recipe module's tables. Sweep them too so
    // a subsequent test's dedup probe (on content_fingerprint) doesn't collapse to an orphan row.
    //
    // Cyclic-FK note: recipe_recipes.current_branch_id → recipe_branches.id, AND
    // recipe_branches.recipe_id → recipe_recipes.id. Naïve "delete branches then recipes"
    // fails the recipe → branch FK; null out the current_* pointers first to break the
    // cycle, then proceed in standard dependency order.
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET current_branch_id = NULL WHERE catalogue = 'SYSTEM'");
    jdbcTemplate.update("DELETE FROM recipe_imports");
    jdbcTemplate.update("DELETE FROM recipe_method_steps");
    jdbcTemplate.update("DELETE FROM recipe_ingredients");
    jdbcTemplate.update("DELETE FROM recipe_metadata");
    jdbcTemplate.update("DELETE FROM recipe_tags");
    jdbcTemplate.update("DELETE FROM recipe_versions");
    jdbcTemplate.update("DELETE FROM recipe_branches");
    jdbcTemplate.update("DELETE FROM recipe_recipes WHERE catalogue = 'SYSTEM'");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    testAiService.clear();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "phase-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userIdJson =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userIdJson), cookie);
  }

  private void seedSource(String key) {
    DiscoverySource src = DiscoveryTestData.sampleSource(key);
    src.setEnabled(true);
    sourceRepository.saveAndFlush(src);
  }

  private StartDiscoveryJobRequest coldStart(int requestedCount, List<String> sourceKeys) {
    return new StartDiscoveryJobRequest(
        DiscoveryJobTrigger.COLD_START,
        requestedCount,
        DiscoveryTestData.sampleConstraints(),
        sourceKeys,
        null);
  }

  /**
   * Runs a COLD_START job synchronously and returns the persisted terminal job. {@code
   * requestedCount} is floored at 3 to satisfy the {@code maxRecipesPerSource (=3) <=
   * requestedCount} cross-field {@code @AssertTrue} on {@link StartDiscoveryJobRequest} — skeleton
   * mode never increments {@code recipesIngested}, so the quota never gates and the per-candidate
   * branch under test is identical regardless of the exact (>=3) count.
   */
  private DiscoveryJob runSyncToTerminal(AuthedUser user, int requestedCount, String sourceKey)
      throws Exception {
    int count = Math.max(3, requestedCount);
    MvcResult res =
        mvc.perform(
                post("/api/v1/discovery/admin/jobs/sync?timeoutSeconds=20")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(coldStart(count, List.of(sourceKey)))))
            .andReturn();
    // 200 (terminal/partial DTO, carries "id") or 502 (all sources down → ProblemDetail body with
    // no "id"). Both mean the runner finalised. For 502 resolve the job via the repository — the
    // test user has exactly one job per test (clean @AfterEach).
    int sc = res.getResponse().getStatus();
    assertThat(sc).isIn(200, 502);
    JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
    JsonNode idNode = body.get("id");
    if (idNode != null && !idNode.isNull()) {
      return jobRepository.findById(UUID.fromString(idNode.asText())).orElseThrow();
    }
    return jobRepository
        .findByUserIdOrderByQueuedAtDesc(
            user.userId(), org.springframework.data.domain.PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .orElseThrow();
  }

  private List<DiscoveryScrapeLog> rowsFor(UUID jobId) {
    return scrapeLogRepository.findByJobId(jobId);
  }

  // ---- discovery-01g: end-to-end success — saveImportedRecipe persists, SUCCESS row, counters
  // ----

  @Test
  void happyCandidate_realSpi_writesSuccessRow_incrementsIngested_recordsCounters()
      throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_ok");

    DiscoveryJob job = runSyncToTerminal(user, 2, "phase_ok");

    List<DiscoveryScrapeLog> rows = rowsFor(job.getId());
    assertThat(rows).isNotEmpty();
    DiscoveryScrapeLog success =
        rows.stream().filter(r -> r.getStatus() == ScrapeOutcome.SUCCESS).findFirst().orElseThrow();
    assertThat(success.getRecipeId()).isNotNull();
    assertThat(success.getContentFingerprint()).isNotBlank();
    assertThat(job.getCandidatesSeen()).isGreaterThanOrEqualTo(1);
    assertThat(job.getCandidatesAfterFilter()).isGreaterThanOrEqualTo(1);
    assertThat(job.getRecipesIngested()).isGreaterThanOrEqualTo(1);
    // catalogue=SYSTEM with WEB_DISCOVERED quality landed.
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM recipe_recipes WHERE id = ? AND catalogue = 'SYSTEM' AND"
                + " data_quality = 'WEB_DISCOVERED'",
            Integer.class,
            success.getRecipeId());
    assertThat(count).isEqualTo(1);
  }

  // ---- fetch phase: source.fetchRecipe throws ExtractionFailedException ----

  @Test
  void fetchExtractionFailedException_writesExtractionFailedRow() throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_extract_fail");

    DiscoveryJob job = runSyncToTerminal(user, 1, "phase_extract_fail");

    List<DiscoveryScrapeLog> rows = rowsFor(job.getId());
    assertThat(rows)
        .anySatisfy(
            r -> {
              assertThat(r.getStatus()).isEqualTo(ScrapeOutcome.EXTRACTION_FAILED);
              assertThat(r.getErrorClass()).isEqualTo("ExtractionFailedException");
            });
    assertThat(job.getStatus()).isEqualTo(DiscoveryJobStatus.FAILED);
  }

  // ---- fetch phase: generic RuntimeException from fetchRecipe ----

  @Test
  void fetchRuntimeException_writesExtractionFailedRow() throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_fetch_boom");

    DiscoveryJob job = runSyncToTerminal(user, 1, "phase_fetch_boom");

    assertThat(rowsFor(job.getId()))
        .anySatisfy(
            r -> {
              assertThat(r.getStatus()).isEqualTo(ScrapeOutcome.EXTRACTION_FAILED);
              assertThat(r.getErrorClass()).isEqualTo("IllegalStateException");
            });
  }

  // ---- fetch phase: low-confidence guard ----

  @Test
  void lowConfidenceParse_writesExtractionFailedRow_lowConfidenceSkipReason() throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_low_conf");

    DiscoveryJob job = runSyncToTerminal(user, 1, "phase_low_conf");

    assertThat(rowsFor(job.getId()))
        .anySatisfy(
            r -> {
              assertThat(r.getStatus()).isEqualTo(ScrapeOutcome.EXTRACTION_FAILED);
              assertThat(r.getSkipReason()).isEqualTo(ScrapeSkipReason.LOW_CONFIDENCE);
              assertThat(r.getExtractionConfidence()).isLessThan(new BigDecimal("0.5"));
            });
  }

  // ---- fetch phase: content-fingerprint dedup → DUPLICATE + incrementSkippedDuplicate ----

  @Test
  void duplicateFingerprint_writesDuplicateRow_incrementsSkippedDuplicateCounter()
      throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_dupe");

    // First run persists a fingerprint via the skeleton-mode row (carries contentFingerprint).
    DiscoveryJob first = runSyncToTerminal(user, 1, "phase_dupe");
    assertThat(rowsFor(first.getId())).isNotEmpty();

    // Second run with the SAME deterministic recipe → scrapeLogExistsSince finds the prior
    // fingerprint within the lookback window → DUPLICATE skip + recipesSkippedDuplicate bumped.
    DiscoveryJob second = runSyncToTerminal(user, 1, "phase_dupe");

    assertThat(rowsFor(second.getId()))
        .anySatisfy(
            r -> {
              assertThat(r.getStatus()).isEqualTo(ScrapeOutcome.DUPLICATE);
              assertThat(r.getSkipReason()).isEqualTo(ScrapeSkipReason.DUPLICATE);
            });
    assertThat(second.getRecipesSkippedDuplicate()).isGreaterThanOrEqualTo(1);
  }

  // ---- fetch phase: robots DISALLOWED (stub gate) ----

  @Test
  void robotsDisallowed_writesRobotsDisallowedRow() throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_robots_block");

    DiscoveryJob job = runSyncToTerminal(user, 1, "phase_robots_block");

    assertThat(rowsFor(job.getId()))
        .anySatisfy(
            r -> {
              assertThat(r.getStatus()).isEqualTo(ScrapeOutcome.ROBOTS_DISALLOWED);
              assertThat(r.getRobotsTxtOutcome()).isEqualTo(RobotsTxtOutcome.DISALLOWED);
              assertThat(r.getSkipReason()).isEqualTo(ScrapeSkipReason.ROBOTS_DISALLOWED);
            });
  }

  // ---- fetch phase: robots UNAVAILABLE + source respects robots → SKIPPED ----

  @Test
  void robotsUnavailable_respectingSource_writesSkippedRow() throws Exception {
    AuthedUser user = registerUser();
    // sampleSource sets respectRobotsTxt=true, so an UNAVAILABLE robots verdict skips the fetch.
    seedSource("phase_robots_unavail");

    DiscoveryJob job = runSyncToTerminal(user, 1, "phase_robots_unavail");

    assertThat(rowsFor(job.getId()))
        .anySatisfy(
            r -> {
              assertThat(r.getStatus()).isEqualTo(ScrapeOutcome.SKIPPED);
              assertThat(r.getRobotsTxtOutcome()).isEqualTo(RobotsTxtOutcome.UNAVAILABLE);
            });
  }

  // ---- fetch phase: multi-candidate loop persists every candidate ----

  @Test
  void multipleCandidates_eachProcessed_skeletonRowsPersisted() throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_multi");

    // The multi source emits 3 distinct candidates. Skeleton mode never increments `ingested`, so
    // each candidate flows through fetch → hard-constraint → dedup → skeleton persist, exercising
    // the multi-iteration fetch loop and three real @Transactional scrape-row writes.
    DiscoveryJob job = runSyncToTerminal(user, 5, "phase_multi");

    assertThat(rowsFor(job.getId())).hasSize(3);
    assertThat(job.getCandidatesSeen()).isEqualTo(3);
  }

  // ---- search phase: source unavailable → HTTP_ERROR row, source failed, job FAILED ----

  @Test
  void searchSourceUnavailable_writesHttpErrorRow_jobFailed() throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_down");

    DiscoveryJob job = runSyncToTerminal(user, 1, "phase_down");

    assertThat(rowsFor(job.getId()))
        .anySatisfy(
            r -> {
              assertThat(r.getStatus()).isEqualTo(ScrapeOutcome.HTTP_ERROR);
              assertThat(r.getErrorClass()).isEqualTo("DiscoverySourceUnavailableException");
            });
    assertThat(job.getStatus()).isEqualTo(DiscoveryJobStatus.FAILED);
    assertThat(job.getSourcesFailed()).contains("phase_down");
  }

  // ---- search phase: generic RuntimeException → HTTP_ERROR row ----

  @Test
  void searchRuntimeException_writesHttpErrorRow_jobFailed() throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_search_boom");

    DiscoveryJob job = runSyncToTerminal(user, 1, "phase_search_boom");

    assertThat(rowsFor(job.getId()))
        .anySatisfy(
            r -> {
              assertThat(r.getStatus()).isEqualTo(ScrapeOutcome.HTTP_ERROR);
              assertThat(r.getErrorClass()).isEqualTo("IllegalArgumentException");
            });
    assertThat(job.getStatus()).isEqualTo(DiscoveryJobStatus.FAILED);
  }

  // ---- fetch phase: candidate with unknown source key is skipped (no row) ----

  @Test
  void candidateWithUnknownSourceKey_isSkipped_noScrapeRow() throws Exception {
    AuthedUser user = registerUser();
    seedSource("phase_wrongkey");

    DiscoveryJob job = runSyncToTerminal(user, 1, "phase_wrongkey");

    // The single candidate carries a sourceKey ("ghost") not in the active bean map → the runner
    // logs and continues without writing a scrape row. No skeleton row, terminal FAILED.
    assertThat(rowsFor(job.getId())).isEmpty();
    assertThat(job.getStatus()).isEqualTo(DiscoveryJobStatus.FAILED);
  }

  // =================== test source beans ===================

  private static ParsedRecipe parsed(String url, BigDecimal confidence) {
    return new ParsedRecipe(
        url,
        "Recipe " + url,
        "desc",
        List.of(),
        List.of(),
        new ParsedRecipe.ParsedRecipeMetadata(
            2, 5, 10, 15, List.of(), "East Asian", List.of("dinner")),
        "jsonld",
        confidence);
  }

  private abstract static class BaseSource
      implements com.example.mealprep.discovery.domain.service.DiscoverySource {
    private final String key;

    BaseSource(String key) {
      this.key = key;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public DiscoverySourceKind kind() {
      return DiscoverySourceKind.SITEMAP;
    }

    @Override
    public List<DiscoveryCandidate> search(DiscoveryQuery query) {
      return List.of(
          new DiscoveryCandidate(key, "https://example.test/" + key + "/1", "T", "d", Map.of()));
    }

    @Override
    public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
      return parsed(candidate.candidateUrl(), new BigDecimal("0.90"));
    }
  }

  @TestConfiguration
  static class PhaseSourcesConfig {

    /** Happy candidate → skeleton-mode EXTRACTION_FAILED row + counter bumps. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseOkSource() {
      return new BaseSource("phase_ok") {};
    }

    /** Stable fingerprint across runs → dedup DUPLICATE on the second run. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseDupeSource() {
      return new BaseSource("phase_dupe") {
        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          return List.of(
              new DiscoveryCandidate(
                  "phase_dupe", "https://example.test/phase_dupe/stable", "Stable", "d", Map.of()));
        }

        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          // Identical content every call → identical content fingerprint.
          return parsed("https://example.test/phase_dupe/stable", new BigDecimal("0.90"));
        }
      };
    }

    /** fetchRecipe throws ExtractionFailedException. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseExtractFailSource() {
      return new BaseSource("phase_extract_fail") {
        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          throw new ExtractionFailedException(candidate.candidateUrl(), "no recipe markup");
        }
      };
    }

    /** fetchRecipe throws a generic RuntimeException. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseFetchBoomSource() {
      return new BaseSource("phase_fetch_boom") {
        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          throw new IllegalStateException("fetch exploded");
        }
      };
    }

    /** Parsed recipe below the 0.5 confidence floor. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseLowConfSource() {
      return new BaseSource("phase_low_conf") {
        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          return parsed(candidate.candidateUrl(), new BigDecimal("0.30"));
        }
      };
    }

    /** Declares a robots URI so the runner consults the (stubbed DISALLOWED) gate. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseRobotsBlockSource() {
      return new BaseSource("phase_robots_block") {
        @Override
        public Optional<URI> robotsTxtUri() {
          return Optional.of(URI.create("https://example.test/robots.txt"));
        }

        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          return List.of(
              new DiscoveryCandidate(
                  "phase_robots_block", "https://example.test/blocked/1", "T", "d", Map.of()));
        }
      };
    }

    /** Declares a robots URI; the stub gate returns UNAVAILABLE for this host. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseRobotsUnavailSource() {
      return new BaseSource("phase_robots_unavail") {
        @Override
        public Optional<URI> robotsTxtUri() {
          return Optional.of(URI.create("https://example.test/robots.txt"));
        }

        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          return List.of(
              new DiscoveryCandidate(
                  "phase_robots_unavail", "https://unavail.test/u/1", "T", "d", Map.of()));
        }
      };
    }

    /** Emits 3 candidates → multi-candidate fetch loop. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseMultiSource() {
      return new BaseSource("phase_multi") {
        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          return List.of(
              new DiscoveryCandidate("phase_multi", "https://example.test/m/1", "1", "d", Map.of()),
              new DiscoveryCandidate("phase_multi", "https://example.test/m/2", "2", "d", Map.of()),
              new DiscoveryCandidate(
                  "phase_multi", "https://example.test/m/3", "3", "d", Map.of()));
        }

        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          // Distinct content per candidate so the dedup probe never collapses them.
          return parsed(candidate.candidateUrl(), new BigDecimal("0.90"));
        }
      };
    }

    /** search throws DiscoverySourceUnavailableException. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseDownSource() {
      return new BaseSource("phase_down") {
        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          throw new com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException(
              "phase_down", "simulated outage", null);
        }
      };
    }

    /** search throws a generic RuntimeException. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseSearchBoomSource() {
      return new BaseSource("phase_search_boom") {
        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          throw new IllegalArgumentException("search blew up");
        }
      };
    }

    /** Candidate references a source key not in the active bean set. */
    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource phaseWrongKeySource() {
      return new BaseSource("phase_wrongkey") {
        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          return List.of(
              new DiscoveryCandidate("ghost", "https://example.test/ghost/1", "T", "d", Map.of()));
        }
      };
    }

    /**
     * Primary {@link com.example.mealprep.discovery.domain.service.RobotsTxtGate} stub so the
     * runner's robots branch is deterministic without WireMock: DISALLOWED for the blocked host,
     * UNAVAILABLE for the unavail host, ALLOWED otherwise.
     */
    @Bean
    @Primary
    com.example.mealprep.discovery.domain.service.RobotsTxtGate stubRobotsTxtGate() {
      return (candidateUrl, userAgent) -> {
        String host = candidateUrl.getHost();
        if (candidateUrl.getPath().startsWith("/blocked")) {
          return RobotsTxtOutcome.DISALLOWED;
        }
        if ("unavail.test".equals(host)) {
          return RobotsTxtOutcome.UNAVAILABLE;
        }
        return RobotsTxtOutcome.ALLOWED;
      };
    }
  }
}
