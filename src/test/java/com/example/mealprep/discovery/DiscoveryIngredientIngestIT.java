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
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.entity.ScrapeOutcome;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoveryScrapeLogRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.domain.service.internal.CandidateFilterResult;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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
 * discovery-1 regression lock. Testcontainers IT driving the LIVE {@code DiscoveryJobRunner}
 * through the synchronous admin endpoint with a purpose-built INGREDIENT-BEARING {@code
 * DiscoverySource}, proving that a discovered recipe with {@code >= 1} ingredient now ingests
 * end-to-end (it could not before: {@code ingredient_mapping_key} was dropped at every hop and the
 * NOT-NULL column rejected the insert, which the runner caught as {@code EXTRACTION_FAILED}).
 *
 * <p>Asserts the full chain holds against real Postgres: the recipe lands in the SYSTEM catalogue
 * as {@code WEB_DISCOVERED}, the {@code recipe_ingredients} rows persist, and every {@code
 * ingredient_mapping_key} is the canonical normalised form (lowercase + trimmed + collapsed) and
 * never null. One ingredient supplies an explicit key, another supplies none — exercising both the
 * carry path and the {@code normalise(displayName)} v1 fallback.
 *
 * <p>Mirrors the {@code @SpringBootTest + @AutoConfigureMockMvc + TestContainersConfig} convention
 * and sync-run entry point established by {@code DiscoveryRunnerPhasesIT} / {@code
 * DiscoveryRunJobSyncIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, DiscoveryIngredientIngestIT.IngredientSourceConfig.class})
@ActiveProfiles("test")
class DiscoveryIngredientIngestIT {

  private static final String SOURCE_KEY = "ingredient_ingest_src";
  private static final String RECIPE_URL = "https://example.test/ingredient-ingest/1";

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
    // The AI candidate filter dispatches one cheap-tier task per candidate; accept it so the
    // candidate reaches the fetch + persist branch under test.
    testAiService.register(
        TaskType.DISCOVERY_FILTERING,
        new CandidateFilterResult(true, new BigDecimal("0.90"), "looks like a recipe"));
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM discovery_scrape_log");
    jdbcTemplate.update("DELETE FROM discovery_jobs");
    jdbcTemplate.update("DELETE FROM discovery_sources");
    // Break the recipe<->branch FK cycle before deleting the WEB_DISCOVERED rows this test writes.
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

  @Test
  void ingredientBearingRecipe_ingestsEndToEnd_withNormalisedNonNullMappingKeys() throws Exception {
    AuthedUser user = registerUser();
    seedSource();

    DiscoveryJob job = runSyncToTerminal(user);

    // The runner persisted the recipe via the real SPI: a SUCCESS scrape row carrying the recipeId.
    List<DiscoveryScrapeLog> rows = scrapeLogRepository.findByJobId(job.getId());
    DiscoveryScrapeLog success =
        rows.stream()
            .filter(r -> r.getStatus() == ScrapeOutcome.SUCCESS && r.getRecipeId() != null)
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "expected a SUCCESS scrape row with a recipeId; ingredient-bearing recipe"
                            + " did not ingest. rows="
                            + rows));
    assertThat(job.getRecipesIngested()).isGreaterThanOrEqualTo(1);

    UUID recipeId = success.getRecipeId();

    // The recipe landed in the SYSTEM catalogue as WEB_DISCOVERED.
    Integer recipeCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM recipe_recipes WHERE id = ? AND catalogue = 'SYSTEM' AND"
                + " data_quality = 'WEB_DISCOVERED'",
            Integer.class,
            recipeId);
    assertThat(recipeCount).isEqualTo(1);

    // The ingredient rows persisted under the recipe's current version with NON-NULL, NORMALISED
    // mapping keys (the whole point of discovery-1).
    UUID versionId =
        jdbcTemplate.queryForObject(
            "SELECT v.id FROM recipe_versions v"
                + " JOIN recipe_recipes r ON r.id = v.recipe_id AND r.current_branch_id = v.branch_id"
                + " WHERE v.recipe_id = ? AND v.version_number = r.current_version",
            UUID.class,
            recipeId);

    List<Map<String, Object>> ingredients =
        jdbcTemplate.queryForList(
            "SELECT display_name, ingredient_mapping_key, optional FROM recipe_ingredients"
                + " WHERE version_id = ? ORDER BY line_order",
            versionId);

    assertThat(ingredients).hasSize(2);
    assertThat(ingredients)
        .allSatisfy(
            row -> {
              String key = (String) row.get("ingredient_mapping_key");
              assertThat(key).as("ingredient_mapping_key must be non-null").isNotNull();
              assertThat(key).as("ingredient_mapping_key must be non-blank").isNotBlank();
              // Canonical normalised form: lowercase, trimmed, single-spaced.
              assertThat(key).isEqualTo(key.trim().toLowerCase().replaceAll("\\s+", " "));
            });

    // Explicit-key ingredient → carried + normalised. No-key ingredient → normalise(displayName).
    assertThat(ingredients.get(0).get("display_name")).isEqualTo("2 Cups   Plain Flour");
    assertThat(ingredients.get(0).get("ingredient_mapping_key")).isEqualTo("plain flour");
    assertThat(ingredients.get(1).get("display_name")).isEqualTo("  Olive   Oil ");
    assertThat(ingredients.get(1).get("ingredient_mapping_key")).isEqualTo("olive oil");
  }

  // -------- helpers (mirroring DiscoveryRunnerPhasesIT) --------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("ingest-" + AuthTestData.shortId());
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

  private void seedSource() {
    DiscoverySource src = DiscoveryTestData.sampleSource(SOURCE_KEY);
    src.setEnabled(true);
    sourceRepository.saveAndFlush(src);
  }

  private DiscoveryJob runSyncToTerminal(AuthedUser user) throws Exception {
    StartDiscoveryJobRequest request =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START,
            3,
            DiscoveryTestData.sampleConstraints(),
            List.of(SOURCE_KEY),
            null);
    MvcResult res =
        mvc.perform(
                post("/api/v1/discovery/admin/jobs/sync?timeoutSeconds=20")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andReturn();
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

  // -------- ingredient-bearing test source --------

  @TestConfiguration
  static class IngredientSourceConfig {

    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource ingredientIngestSource() {
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
          return List.of(
              new DiscoveryCandidate(SOURCE_KEY, RECIPE_URL, "Flatbread", "d", Map.of()));
        }

        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          return new ParsedRecipe(
              RECIPE_URL,
              "Simple Flatbread",
              "A two-ingredient flatbread.",
              List.of(
                  // Supplies an explicit (already-normalised) key — carried through.
                  new ParsedRecipe.ParsedIngredient(
                      "2 Cups   Plain Flour",
                      "plain flour",
                      new BigDecimal("2"),
                      "cup",
                      null,
                      false),
                  // Supplies NO key + a messy displayName — runner derives normalise(displayName).
                  new ParsedRecipe.ParsedIngredient(
                      "  Olive   Oil ", null, new BigDecimal("1"), "tbsp", null, true)),
              List.of(new ParsedRecipe.ParsedMethodStep(1, "Mix and griddle.", 10)),
              new ParsedRecipe.ParsedRecipeMetadata(
                  4, 10, 10, 20, List.of(), "Mediterranean", List.of("lunch")),
              "jsonld",
              new BigDecimal("0.90"));
        }
      };
    }
  }
}
