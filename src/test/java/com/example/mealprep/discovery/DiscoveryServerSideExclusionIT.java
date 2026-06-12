package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.entity.ScrapeOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeSkipReason;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoveryScrapeLogRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.domain.service.internal.CandidateFilterResult;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.preference.domain.repository.HardConstraintsAuditLogRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Regression lock for ticket {@code discovery-server-side-exclusions} (P1 SAFETY): the server
 * derives the caller's hard-constraint exclusion snapshot at enqueue and unions it with the
 * client-supplied {@code mustExcludeIngredientMappingKeys} — the client list is additive only.
 *
 * <p>Drives the REAL user path ({@code POST /api/v1/discovery/jobs}, {@code USER_INITIATED}, async
 * runner) against Testcontainers Postgres with a stubbed in-process source and the TestAiService
 * double (zero live AI / network), then polls the job to terminal.
 *
 * <ul>
 *   <li><b>Attack case (ticket DoD):</b> user with a peanut allergy, client sends {@code []}, a
 *       seeded peanut-derivative recipe page → scrape log shows {@code HARD_CONSTRAINT_VIOLATION},
 *       nothing ingested, and the persisted constraints recap carries the server-derived keys.
 *   <li><b>Union case:</b> client adds an extra key ("mushroom") → both the client key and the
 *       user's allergen derivatives are excluded; a safe recipe still ingests.
 *   <li><b>No-constraints user:</b> the client set passes through unchanged and is enforced.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, DiscoveryServerSideExclusionIT.ExclusionSourceConfig.class})
@ActiveProfiles("test")
class DiscoveryServerSideExclusionIT {

  private static final String SOURCE_KEY = "server_side_exclusion_src";
  private static final EnumSet<DiscoveryJobStatus> TERMINAL =
      EnumSet.of(
          DiscoveryJobStatus.SUCCEEDED, DiscoveryJobStatus.FAILED, DiscoveryJobStatus.PARTIAL);

  /** Recipes the stub source serves for the currently-running test. */
  private static volatile List<SeedRecipe> served = List.of();

  record SeedRecipe(String url, String title, List<String> ingredientKeys) {}

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private DiscoverySourceRepository sourceRepository;
  @Autowired private DiscoveryJobRepository jobRepository;
  @Autowired private DiscoveryScrapeLogRepository scrapeLogRepository;
  @Autowired private HardConstraintsRepository hardConstraintsRepository;
  @Autowired private HardConstraintsAuditLogRepository hardConstraintsAuditLogRepository;
  @Autowired private TestAiService testAiService;

  @BeforeEach
  void setUp() {
    // The AI candidate filter dispatches one cheap-tier task per candidate; accept everything so
    // candidates reach the deterministic hard-constraint passes under test.
    testAiService.register(
        TaskType.DISCOVERY_FILTERING,
        new CandidateFilterResult(true, new BigDecimal("0.90"), "looks like a recipe"));
    com.example.mealprep.discovery.domain.entity.DiscoverySource src =
        DiscoveryTestData.sampleSource(SOURCE_KEY);
    src.setEnabled(true);
    // SourceRateLimiterRegistry lazily builds ONE cached limiter per source_key from this row's
    // requestsPerMinute. The sample default (6/min) starves the later tests in this class (search
    // + per-candidate fetch tokens accumulate across tests within the refresh minute) —
    // RATE_LIMITED
    // rows instead of the hard-constraint outcomes under test. Budget generously.
    src.setRequestsPerMinute(600);
    sourceRepository.saveAndFlush(src);
  }

  @AfterEach
  void cleanup() {
    served = List.of();
    jdbcTemplate.update("DELETE FROM discovery_scrape_log");
    jdbcTemplate.update("DELETE FROM discovery_jobs");
    jdbcTemplate.update("DELETE FROM discovery_sources");
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
    hardConstraintsAuditLogRepository.deleteAllInBatch();
    hardConstraintsRepository.deleteAllInBatch();
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    testAiService.clear();
  }

  // ---------- the ticket's attack case ----------

  @Test
  void attackCase_clientOmitsAllergen_serverSnapshotStillExcludes_nothingIngested()
      throws Exception {
    AuthedUser user = registerUser();
    seedPeanutAllergy(user.userId());
    served =
        List.of(
            new SeedRecipe(
                "https://example.test/attack/peanut-satay",
                "Peanut Satay",
                List.of("peanut_butter", "rice")));

    // THE ATTACK: the client sends an EMPTY exclusion list, omitting the user's allergen.
    UUID jobId = startUserJob(user, List.of(), 1);
    DiscoveryJob job = awaitTerminal(jobId);

    // The deterministic pass still rejected the peanut-derivative recipe.
    List<DiscoveryScrapeLog> rows = scrapeLogRepository.findByJobId(jobId);
    assertThat(rows)
        .as("scrape log must show the hard-constraint rejection, rows=" + rows)
        .anyMatch(
            r ->
                r.getStatus() == ScrapeOutcome.HARD_CONSTRAINT_VIOLATION
                    && r.getSkipReason() == ScrapeSkipReason.HARD_CONSTRAINT);
    assertThat(rows).noneMatch(r -> r.getStatus() == ScrapeOutcome.SUCCESS);
    assertThat(job.getRecipesIngested()).isZero();
    Integer ingestedRecipes =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM recipe_recipes WHERE catalogue = 'SYSTEM'", Integer.class);
    assertThat(ingestedRecipes).as("nothing may reach the SYSTEM catalogue").isZero();

    // The persisted recap (GET DTO) carries the server-derived union: the allergen AND its seeded
    // derivatives — proof the server, not the client, computed the snapshot.
    List<String> recap = constraintsRecap(user, jobId);
    assertThat(recap).contains("peanut", "peanut_butter");
  }

  // ---------- union: client keys are additive ----------

  @Test
  void unionCase_clientExtraKey_bothClientAndServerKeysExcluded_safeRecipeStillIngests()
      throws Exception {
    AuthedUser user = registerUser();
    seedPeanutAllergy(user.userId());
    served =
        List.of(
            new SeedRecipe(
                "https://example.test/union/mushroom-risotto",
                "Mushroom Risotto",
                List.of("mushroom", "rice")),
            new SeedRecipe(
                "https://example.test/union/peanut-noodles",
                "Peanut Noodles",
                List.of("peanut_oil", "noodles")),
            new SeedRecipe(
                "https://example.test/union/bean-stew", "Bean Stew", List.of("beans", "tomato")));

    UUID jobId = startUserJob(user, List.of("mushroom"), 3);
    DiscoveryJob job = awaitTerminal(jobId);

    List<DiscoveryScrapeLog> rows = scrapeLogRepository.findByJobId(jobId);
    // Client-added key enforced deterministically (only the snapshot pass can reject mushroom —
    // it is not one of the user's stored constraints) AND the server allergen derivative.
    assertThat(rows)
        .anyMatch(
            r ->
                r.getStatus() == ScrapeOutcome.HARD_CONSTRAINT_VIOLATION
                    && r.getCandidateUrl().contains("mushroom-risotto"))
        .anyMatch(
            r ->
                r.getStatus() == ScrapeOutcome.HARD_CONSTRAINT_VIOLATION
                    && r.getCandidateUrl().contains("peanut-noodles"))
        .anyMatch(
            r ->
                r.getStatus() == ScrapeOutcome.SUCCESS
                    && r.getCandidateUrl().contains("bean-stew"));
    assertThat(job.getRecipesIngested()).isEqualTo(1);

    List<String> recap = constraintsRecap(user, jobId);
    assertThat(recap).contains("mushroom", "peanut", "peanut_oil");
  }

  // ---------- no-constraints user: client set passes through ----------

  @Test
  void noConstraintsUser_clientKeysPassThroughUnchanged_andAreEnforced() throws Exception {
    AuthedUser user = registerUser(); // no hard-constraints aggregate seeded
    served =
        List.of(
            new SeedRecipe(
                "https://example.test/passthrough/mushroom-soup",
                "Mushroom Soup",
                List.of("mushroom", "cream")),
            new SeedRecipe(
                "https://example.test/passthrough/lentil-curry",
                "Lentil Curry",
                List.of("lentils", "quinoa")));

    UUID jobId = startUserJob(user, List.of("mushroom"), 2);
    DiscoveryJob job = awaitTerminal(jobId);

    List<DiscoveryScrapeLog> rows = scrapeLogRepository.findByJobId(jobId);
    assertThat(rows)
        .anyMatch(
            r ->
                r.getStatus() == ScrapeOutcome.HARD_CONSTRAINT_VIOLATION
                    && r.getCandidateUrl().contains("mushroom-soup"))
        .anyMatch(
            r ->
                r.getStatus() == ScrapeOutcome.SUCCESS
                    && r.getCandidateUrl().contains("lentil-curry"));
    assertThat(job.getRecipesIngested()).isEqualTo(1);

    // Empty server snapshot → the client list passes through exactly.
    assertThat(constraintsRecap(user, jobId)).containsExactly("mushroom");
  }

  // -------- helpers --------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("excl-" + AuthTestData.shortId());
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

  private void seedPeanutAllergy(UUID userId) {
    hardConstraintsRepository.saveAndFlush(
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build());
  }

  /** POST /api/v1/discovery/jobs as the user (USER_INITIATED, async) and return the job id. */
  private UUID startUserJob(AuthedUser user, List<String> clientMustExclude, int requestedCount)
      throws Exception {
    DiscoveryConstraints constraints =
        new DiscoveryConstraints(1, null, null, null, clientMustExclude, null, null, null);
    StartDiscoveryJobRequest request =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            requestedCount,
            constraints,
            List.of(SOURCE_KEY),
            null);
    MvcResult res =
        mvc.perform(
                post("/api/v1/discovery/jobs")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andReturn();
    JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
    return UUID.fromString(body.get("id").asText());
  }

  /** Polls the async runner to a terminal status (bounded; the stub source is instant). */
  private DiscoveryJob awaitTerminal(UUID jobId) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(deadline)) {
      DiscoveryJob job = jobRepository.findById(jobId).orElse(null);
      if (job != null && TERMINAL.contains(job.getStatus())) {
        return job;
      }
      Thread.sleep(200);
    }
    DiscoveryJob last = jobRepository.findById(jobId).orElse(null);
    throw new AssertionError(
        "discovery job " + jobId + " did not reach a terminal status in 30s; last=" + last);
  }

  /** The mustExclude recap the user sees on the job card (GET DTO), per the ticket. */
  private List<String> constraintsRecap(AuthedUser user, UUID jobId) throws Exception {
    MvcResult res =
        mvc.perform(get("/api/v1/discovery/jobs/" + jobId).cookie(user.cookie()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode keys =
        objectMapper
            .readTree(res.getResponse().getContentAsString())
            .path("constraints")
            .path("mustExcludeIngredientMappingKeys");
    List<String> out = new ArrayList<>();
    keys.forEach(n -> out.add(n.asText()));
    return out;
  }

  // -------- stub source serving the per-test seed recipes --------

  @TestConfiguration
  static class ExclusionSourceConfig {

    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource serverSideExclusionSource() {
      return new com.example.mealprep.discovery.domain.service.DiscoverySource() {
        @Override
        public String key() {
          return SOURCE_KEY;
        }

        @Override
        public DiscoverySourceKind kind() {
          return DiscoverySourceKind.SITEMAP;
        }

        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          List<DiscoveryCandidate> candidates = new ArrayList<>();
          for (SeedRecipe seed : served) {
            candidates.add(
                new DiscoveryCandidate(SOURCE_KEY, seed.url(), seed.title(), "d", Map.of()));
          }
          return candidates;
        }

        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          SeedRecipe seed =
              served.stream()
                  .filter(s -> s.url().equals(candidate.candidateUrl()))
                  .findFirst()
                  .orElseThrow();
          List<ParsedRecipe.ParsedIngredient> ingredients = new ArrayList<>();
          for (String key : seed.ingredientKeys()) {
            ingredients.add(
                new ParsedRecipe.ParsedIngredient(key, key, BigDecimal.ONE, "g", null, false));
          }
          return new ParsedRecipe(
              seed.url(),
              seed.title(),
              "desc for " + seed.title(),
              ingredients,
              List.of(new ParsedRecipe.ParsedMethodStep(1, "Cook the " + seed.title() + ".", 10)),
              new ParsedRecipe.ParsedRecipeMetadata(
                  2, 10, 10, 20, List.of(), "Fusion", List.of("dinner")),
              "jsonld",
              new BigDecimal("0.90"));
        }
      };
    }
  }
}
