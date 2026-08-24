package com.example.mealprep.discovery.graphimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AdminAccessProperties;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.discovery.api.dto.GraphBatchIngestReport;
import com.example.mealprep.discovery.api.dto.GraphBatchIngestRequest;
import com.example.mealprep.discovery.config.GraphImportProperties;
import com.example.mealprep.discovery.domain.service.GraphBatchIngestService;
import com.example.mealprep.discovery.domain.service.internal.graphimport.GraphBatchIngestServiceImpl;
import com.example.mealprep.discovery.domain.service.internal.graphimport.GraphImportNutritionRecalc;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * G06 ingest IT (TestContainers Postgres). The batch dir is assembled at runtime from the classpath
 * fixture payloads ({@code graph-batch-fixture/recipes/}) so the {@code manifest_sha256} binding is
 * computed over the exact on-disk bytes (CRLF-proof) and each test can corrupt exactly one contract
 * surface.
 *
 * <p>Covers the ticket's acceptance criteria: mixed-validity batch (1 clean / 1 empty-mealTypes / 1
 * unknown-equipment) → {@code created:1, rejected:2} with exact reasons + one {@code
 * recipe_imports} row carrying the batch jobId/sourceKey/32-char stamp and a null canonicalUrl
 * (closes design-doc open item V1); fingerprint-dedup re-run idempotency; flag-off refusal;
 * pre-flight missing-key abort; restricted-diet batch rejection; verdict-contract aborts
 * (sha-mismatch replay + missing verdict); ingest_report.json equals the response; 401/403 gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "mealprep.graph.import.enabled=true")
class GraphBatchIngestServiceIT {

  private static final String INGEST_ENDPOINT = "/api/v1/discovery/admin/graph-batches/ingest";
  private static final String FP_A = "a".repeat(64); // clean dish (rice + broccoli)
  private static final String FP_B = "b".repeat(64); // empty mealTypes
  private static final String FP_C = "c".repeat(64); // unknown equipment "wok"
  private static final String STAMP = "graph@1234abc+c@0123456789abcdef";
  private static final String SOURCE_KEY = "graph:camp-it-2026-07";
  private static final String JOB_ID = "5d8f2c1e-0000-5000-8000-2f6b0e6c9a11";

  @Autowired private GraphBatchIngestService ingestService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IngredientMappingRepository mappingRepository;
  @Autowired private NutritionQueryService nutritionQueryService;
  @Autowired private RecipeWriteApi recipeWriteApi;
  @Autowired private RecipeQueryService recipeQueryService;
  @Autowired private GraphImportNutritionRecalc nutritionRecalc;
  @Autowired private MockMvc mvc;
  @Autowired private AuthProperties authProperties;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;

  @MockBean private AdminAccessProperties adminProperties;

  @TempDir Path tempDir;

  @BeforeEach
  void seedMappings() {
    // Minimal G05-style subset seeded directly through the repository (test package — allowed).
    // Micros carry the canonical-key shape incl. the saturated_fat_g bridge (G04); the G07
    // recompute must reproduce exactly these values × grams/100 — no other micro key may appear.
    for (String key : List.of("rice", "broccoli")) {
      mappingRepository.save(
          IngredientMapping.builder()
              .id(UUID.randomUUID())
              .searchTerm(key)
              .source(IngredientMappingSource.USDA)
              .externalId("169757")
              .nutritionPer100g(
                  new IngredientNutritionDocument(
                      100,
                      BigDecimal.ONE,
                      BigDecimal.ONE,
                      BigDecimal.ONE,
                      BigDecimal.ONE,
                      null,
                      null,
                      Map.of("iron_mg", BigDecimal.ONE, "saturated_fat_g", new BigDecimal("0.5")),
                      Map.of()))
              .confidence(new BigDecimal("1.000"))
              .needsReview(false)
              .basisNote("consumed-basis; IT seed")
              .build());
    }
  }

  @AfterEach
  void cleanup() {
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
    jdbcTemplate.update("DELETE FROM nutrition_ingredient_mapping");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  // ===== batch-dir assembly (landed graph-batch/1 + graph-review/1 format) =====

  private Path buildBatch() throws Exception {
    return buildBatch((fp, node) -> {}, verdicts -> {});
  }

  private Path buildBatch(
      BiConsumer<String, ObjectNode> payloadMutator, Consumer<ObjectNode> verdictMutator)
      throws Exception {
    Path dir = tempDir.resolve("batch-20260721-1");
    Files.createDirectories(dir.resolve("recipes"));
    Files.createDirectories(dir.resolve("review"));

    for (String fp : List.of(FP_A, FP_B, FP_C)) {
      ObjectNode payload;
      try (var in =
          new ClassPathResource("graph-batch-fixture/recipes/" + fp + ".json").getInputStream()) {
        payload = (ObjectNode) objectMapper.readTree(in);
      }
      payloadMutator.accept(fp, payload);
      Files.writeString(
          dir.resolve("recipes").resolve(fp + ".json"), objectMapper.writeValueAsString(payload));
    }

    ObjectNode manifest = objectMapper.createObjectNode();
    manifest.put("schema", "graph-batch/1");
    manifest.put("batch_id", "batch-20260721-1");
    manifest.put("created_utc", "2026-07-21T10:00:00Z");
    manifest.put("spike_commit", "1234abc");
    manifest.put("spike_dirty", false);
    ObjectNode corpusFp = manifest.putObject("corpus_fingerprint");
    corpusFp.put("n_corpus", 1689);
    corpusFp.put("sha", "0123456789abcdef");
    corpusFp.put("n_dedupe", 39);
    ObjectNode campaign = manifest.putObject("campaign");
    campaign.put("campaign_id", "camp-it-2026-07");
    campaign.putArray("meal_types").add("dinner");
    ObjectNode counts = manifest.putObject("counts");
    counts.put("records", 3);
    counts.put("exported", 3);
    counts.put("dup_fingerprint", 0);
    ObjectNode stampNode = manifest.putObject("engine_stamp");
    stampNode.put("source_key", SOURCE_KEY);
    stampNode.put("extraction_method", STAMP);
    ObjectNode review = manifest.putObject("review");
    review.put("mode", "full");
    review.putNull("sample_rate");
    ObjectNode licence = manifest.putObject("licence");
    licence.put("note", "IT fixture batch");
    licence.put("basis", "consumed-basis grams");
    licence.put("lint", "passed");
    byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);
    Files.write(dir.resolve("manifest.json"), manifestBytes);

    ObjectNode verdicts = objectMapper.createObjectNode();
    verdicts.put("schema", "graph-review/1");
    verdicts.put("batch_id", "batch-20260721-1");
    verdicts.put(
        "manifest_sha256",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)));
    verdicts.put("review_mode", "full");
    verdicts.put("reviewed_by", "irene");
    verdicts.put("reviewed_at", "2026-07-21T11:00:00Z");
    verdicts.putArray("approved").add(FP_A).add(FP_B).add(FP_C);
    verdicts.putArray("rejected");
    verdicts.put("notes", "");
    verdictMutator.accept(verdicts);
    Files.writeString(
        dir.resolve("review").resolve("approved.json"), objectMapper.writeValueAsString(verdicts));
    return dir;
  }

  private long importedRecipeCount() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM recipe_recipes WHERE catalogue = 'SYSTEM'", Long.class);
  }

  private long importRowCount() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM recipe_imports", Long.class);
  }

  // ===== the acceptance-criteria ITs =====

  @Test
  void mixedBatch_createsOneRejectsTwo_thenIdempotentRerun_andReportFileMatches() throws Exception {
    Path dir = buildBatch();
    GraphBatchIngestReport report = ingestService.ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_OK);
    assertThat(report.batchId()).isEqualTo("batch-20260721-1");
    assertThat(report.jobId()).isEqualTo(JOB_ID);
    assertThat(report.created()).isEqualTo(1);
    assertThat(report.dedupSkipped()).isZero();
    assertThat(report.rejected()).hasSize(2);
    assertThat(report.rejected())
        .anyMatch(r -> r.fp().equals(FP_B) && r.reason().equals("mealTypes empty"));
    assertThat(report.rejected())
        .anyMatch(r -> r.fp().equals(FP_C) && r.reason().equals("unknown equipment: wok"));
    assertThat(report.recipeIds()).hasSize(1);
    assertThat(report.recipeIds().get(0).fp()).isEqualTo(FP_A);
    // G07: the import loop triggers the ENGINE recompute — the dish lands CALCULATED, not
    // PENDING, with real per-serving figures persisted through the writer SPI.
    assertThat(report.recipeIds().get(0).nutritionStatus()).isEqualTo("CALCULATED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT nutrition_status FROM recipe_recipes WHERE catalogue = 'SYSTEM'",
                String.class))
        .isEqualTo("CALCULATED");
    // Hand-computed Σ(grams/100 × per-100g)/servings — proves grams actually flow (the
    // finding-3 zero-kcal regression pin): rice 180 g + broccoli 120 g on the 100 kcal/100g
    // seed docs at servings=1 → 300 kcal; protein 3.00 g; iron 3.00 mg; satfat bridge 1.50 g.
    String nutritionJson =
        jdbcTemplate.queryForObject(
            "SELECT nutrition_per_serving FROM recipe_versions WHERE id = ?",
            String.class,
            report.recipeIds().get(0).versionId());
    assertThat(nutritionJson).isNotNull();
    JsonNode persisted = objectMapper.readTree(nutritionJson);
    assertThat(persisted.get("nutritionStatus").asText()).isEqualTo("calculated");
    assertThat(persisted.get("caloriesPerServing").asInt()).isEqualTo(300);
    assertThat(persisted.get("proteinPerServingG").decimalValue()).isEqualByComparingTo("3.00");
    assertThat(persisted.get("microsPerServing").get("iron_mg").decimalValue())
        .isEqualByComparingTo("3.00");
    assertThat(persisted.get("microsPerServing").get("saturated_fat_g").decimalValue())
        .isEqualByComparingTo("1.50");
    // The 5 engine micros with no spike source stay ABSENT (not fabricated) — G04 contract.
    for (String absent :
        List.of("biotin_mcg", "chloride_mg", "iodine_mcg", "chromium_mcg", "molybdenum_mcg")) {
      assertThat(persisted.get("microsPerServing").has(absent)).isFalse();
    }

    // one recipe_imports row with the batch jobId, manifest sourceKey, 32-char stamp, and a
    // null canonical_url (design-doc open item V1 formally closed here)
    Map<String, Object> importRow =
        jdbcTemplate.queryForMap(
            "SELECT job_id, source_key, extraction_method, canonical_url, content_fingerprint,"
                + " source_type FROM recipe_imports");
    assertThat(String.valueOf(importRow.get("job_id"))).isEqualTo(JOB_ID);
    assertThat(importRow.get("source_key")).isEqualTo(SOURCE_KEY);
    assertThat(importRow.get("extraction_method")).isEqualTo(STAMP);
    assertThat(String.valueOf(importRow.get("extraction_method")).length()).isEqualTo(32);
    assertThat(importRow.get("canonical_url")).isNull();
    assertThat(importRow.get("content_fingerprint")).isEqualTo(FP_A);
    // G10: graph dishes stop masquerading as scraped ones — provenance + recipe row both say
    // AI_GENERATED (the runner stamps every dish regardless of payload contents).
    assertThat(importRow.get("source_type")).isEqualTo("AI_GENERATED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT data_quality FROM recipe_recipes WHERE catalogue = 'SYSTEM'", String.class))
        .isEqualTo("AI_GENERATED");

    // ingest_report.json written into the batch dir and equal to the HTTP-shaped response
    JsonNode reportFile = objectMapper.readTree(dir.resolve("ingest_report.json").toFile());
    assertThat(reportFile).isEqualTo(objectMapper.valueToTree(report));

    // re-run the same batch → created 0, dedupSkipped 1, no new rows (M-EXPORT idempotency)
    long recipes = importedRecipeCount();
    long versions = jdbcTemplate.queryForObject("SELECT count(*) FROM recipe_versions", Long.class);
    long imports = importRowCount();
    GraphBatchIngestReport rerun = ingestService.ingest(dir.toString());
    assertThat(rerun.status()).isEqualTo(GraphBatchIngestReport.STATUS_OK);
    assertThat(rerun.created()).isZero();
    assertThat(rerun.dedupSkipped()).isEqualTo(1);
    assertThat(rerun.note()).isNotNull(); // dedup-without-content-comparison landmine surfaced
    assertThat(rerun.recipeIds()).hasSize(1); // ids still collected on resume
    // G07 writer idempotency on the dedup path: the recompute re-runs and rewrites values that
    // are byte-identical to the first pass.
    assertThat(rerun.recipeIds().get(0).nutritionStatus()).isEqualTo("CALCULATED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT nutrition_per_serving FROM recipe_versions WHERE id = ?",
                String.class,
                rerun.recipeIds().get(0).versionId()))
        .isEqualTo(nutritionJson);
    assertThat(importedRecipeCount()).isEqualTo(recipes);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM recipe_versions", Long.class))
        .isEqualTo(versions);
    assertThat(importRowCount()).isEqualTo(imports);
  }

  @Test
  void flagOff_returnsDisabled_zeroWrites() throws Exception {
    Path dir = buildBatch();
    // same production class, disabled flag pair — the properties bean is the only difference
    GraphBatchIngestService disabled =
        new GraphBatchIngestServiceImpl(
            new GraphImportProperties(false, false),
            objectMapper,
            nutritionQueryService,
            recipeWriteApi,
            jdbcTemplate,
            nutritionRecalc);
    GraphBatchIngestReport report = disabled.ingest(dir.toString());
    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_DISABLED);
    assertThat(importedRecipeCount()).isZero();
    assertThat(importRowCount()).isZero();
    assertThat(Files.exists(dir.resolve("ingest_report.json"))).isFalse();
  }

  @Test
  void unseededKey_preFlightAbort_zeroWrites_missingKeysNamed() throws Exception {
    Path dir =
        buildBatch(
            (fp, payload) -> {
              if (fp.equals(FP_A)) {
                ObjectNode line = (ObjectNode) payload.withArray("ingredients").get(0);
                line.put("displayName", "quinoa");
                line.put("ingredientMappingKey", "quinoa"); // not seeded
              }
            },
            verdicts -> {});
    GraphBatchIngestReport report = ingestService.ingest(dir.toString());
    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_ABORTED_MISSING_KEYS);
    assertThat(report.missingMappingKeys()).containsExactly("quinoa");
    assertThat(importedRecipeCount()).isZero();
    assertThat(importRowCount()).isZero();
  }

  @Test
  void restrictedDietFlags_rejectWholeBatch_whileNadiaGateClosed() throws Exception {
    Path dir =
        buildBatch(
            (fp, payload) -> {
              if (fp.equals(FP_A)) {
                ((ObjectNode) payload.get("tags")).putArray("dietaryFlags").add("vegan");
              }
            },
            verdicts -> {});
    GraphBatchIngestReport report = ingestService.ingest(dir.toString());
    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_REJECTED_RESTRICTED_DIET);
    assertThat(report.errors()).anyMatch(e -> e.contains(FP_A));
    assertThat(importedRecipeCount()).isZero();
    assertThat(importRowCount()).isZero();
  }

  @Test
  void manifestShaMismatch_isReplayRefusal_zeroWrites() throws Exception {
    Path dir =
        buildBatch(
            (fp, payload) -> {}, verdicts -> verdicts.put("manifest_sha256", "0".repeat(64)));
    GraphBatchIngestReport report = ingestService.ingest(dir.toString());
    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.errors()).anyMatch(e -> e.contains("replay refused"));
    assertThat(importedRecipeCount()).isZero();
    assertThat(importRowCount()).isZero();
  }

  @Test
  void fingerprintWithNoVerdict_aborts_zeroWrites() throws Exception {
    Path dir =
        buildBatch(
            (fp, payload) -> {},
            verdicts -> {
              verdicts.putArray("approved").add(FP_A).add(FP_B); // FP_C in neither list
            });
    GraphBatchIngestReport report = ingestService.ingest(dir.toString());
    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.errors()).anyMatch(e -> e.contains("review incomplete"));
    assertThat(importedRecipeCount()).isZero();
    assertThat(importRowCount()).isZero();
  }

  // ===== HTTP surface (admin gating + status mapping) =====

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser register() throws Exception {
    String username = "gadm-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userId =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userId), cookie);
  }

  @Test
  void httpHappyPath_adminGetsOkReport() throws Exception {
    AuthedUser admin = register();
    given(adminProperties.isAdmin(admin.userId())).willReturn(true);
    Path dir = buildBatch();
    mvc.perform(
            post(INGEST_ENDPOINT)
                .cookie(admin.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new GraphBatchIngestRequest(dir.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OK"))
        .andExpect(jsonPath("$.created").value(1))
        .andExpect(jsonPath("$.jobId").value(JOB_ID));
  }

  @Test
  void httpInvalidPath_adminGets409() throws Exception {
    AuthedUser admin = register();
    given(adminProperties.isAdmin(admin.userId())).willReturn(true);
    mvc.perform(
            post(INGEST_ENDPOINT)
                .cookie(admin.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new GraphBatchIngestRequest("relative/nope"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("INVALID_BATCH"));
  }

  @Test
  void httpAnonymous_returns401() throws Exception {
    mvc.perform(
            post(INGEST_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new GraphBatchIngestRequest("x"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void httpAuthenticatedNonAdmin_returns403() throws Exception {
    AuthedUser user = register(); // not allowlisted → fail-closed 403
    mvc.perform(
            post(INGEST_ENDPOINT)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new GraphBatchIngestRequest("x"))))
        .andExpect(status().isForbidden());
  }

  // ===== G11 withdraw/restore (archive lever by batch jobId) =====

  private static String withdrawPath(String jobId) {
    return "/api/v1/discovery/admin/graph-batches/" + jobId + "/withdraw";
  }

  private static String restorePath(String jobId) {
    return "/api/v1/discovery/admin/graph-batches/" + jobId + "/restore";
  }

  private Object archivedAt(UUID recipeId) {
    return jdbcTemplate.queryForObject(
        "SELECT archived_at FROM recipe_recipes WHERE id = ?", Object.class, recipeId);
  }

  private List<UUID> plannableDinnerIds(UUID userId) {
    return recipeQueryService.findPlannableCandidatesByKind(userId, "dinner", 50, null).stream()
        .map(com.example.mealprep.recipe.api.dto.RecipeDto::id)
        .toList();
  }

  @Test
  void withdrawByJobId_archivesReversiblyIdempotently_andLeavesPlannableReads() throws Exception {
    Path dir = buildBatch();
    GraphBatchIngestReport ingest = ingestService.ingest(dir.toString());
    assertThat(ingest.created()).isEqualTo(1);
    UUID recipeId = ingest.recipeIds().get(0).recipeId();

    // Before withdraw: the dish is in the per-kind plannable read (SYSTEM row, dinner tag).
    UUID anyUser = UUID.randomUUID();
    assertThat(plannableDinnerIds(anyUser)).contains(recipeId);

    AuthedUser admin = register();
    given(adminProperties.isAdmin(admin.userId())).willReturn(true);

    // Withdraw: archived, out of the plannable read immediately (G08-verified archive lever).
    mvc.perform(post(withdrawPath(JOB_ID)).cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("WITHDRAWN"))
        .andExpect(jsonPath("$.matched").value(1))
        .andExpect(jsonPath("$.changedRecipeIds[0]").value(recipeId.toString()))
        .andExpect(jsonPath("$.skippedRecipeIds").isEmpty());
    assertThat(archivedAt(recipeId)).isNotNull();
    assertThat(plannableDinnerIds(anyUser)).doesNotContain(recipeId);

    // Idempotent repeat: same match, nothing changed, still archived.
    mvc.perform(post(withdrawPath(JOB_ID)).cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(1))
        .andExpect(jsonPath("$.changedRecipeIds").isEmpty());
    assertThat(archivedAt(recipeId)).isNotNull();

    // Restore: archived_at cleared, plannable again — full reversal.
    mvc.perform(post(restorePath(JOB_ID)).cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("RESTORED"))
        .andExpect(jsonPath("$.matched").value(1))
        .andExpect(jsonPath("$.changedRecipeIds[0]").value(recipeId.toString()));
    assertThat(archivedAt(recipeId)).isNull();
    assertThat(plannableDinnerIds(anyUser)).contains(recipeId);

    // Idempotent restore repeat.
    mvc.perform(post(restorePath(JOB_ID)).cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changedRecipeIds").isEmpty());
  }

  @Test
  void withdrawUnknownJobId_returns404() throws Exception {
    AuthedUser admin = register();
    given(adminProperties.isAdmin(admin.userId())).willReturn(true);
    mvc.perform(post(withdrawPath(UUID.randomUUID().toString())).cookie(admin.cookie()))
        .andExpect(status().isNotFound());
    mvc.perform(post(restorePath(UUID.randomUUID().toString())).cookie(admin.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void withdraw_neverSweepsADiscoveryCrawlSharingTheJobIdColumn() throws Exception {
    // A WEB_DISCOVERED import (13-arg SPI ctor → null dataQuality → WEB_DISCOVERED, the pre-G10
    // regression pin) carrying a jobId must be invisible to the graph withdraw endpoint: the
    // sourceType=AI_GENERATED match returns nothing → 404, row untouched.
    UUID crawlJobId = UUID.randomUUID();
    var crawlDish =
        new com.example.mealprep.recipe.spi.ImportedRecipeData(
            "bbcgoodfood-sitemap",
            "https://example.test/crawl/1",
            "d".repeat(64),
            "Crawled dish",
            null,
            List.of(
                new com.example.mealprep.recipe.spi.ImportedRecipeData.ImportedIngredient(
                    1, "rice", "rice", new BigDecimal("100"), "g", null, false)),
            List.of(
                new com.example.mealprep.recipe.spi.ImportedRecipeData.ImportedMethodStep(
                    1, "Cook.", 5)),
            null,
            null,
            "json_ld",
            null,
            crawlJobId,
            UUID.randomUUID());
    UUID crawlRecipeId = recipeWriteApi.saveImportedRecipe(crawlDish).recipeId();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT source_type FROM recipe_imports WHERE recipe_id = ?",
                String.class,
                crawlRecipeId))
        .isEqualTo("WEB_DISCOVERED");

    AuthedUser admin = register();
    given(adminProperties.isAdmin(admin.userId())).willReturn(true);
    mvc.perform(post(withdrawPath(crawlJobId.toString())).cookie(admin.cookie()))
        .andExpect(status().isNotFound());
    assertThat(archivedAt(crawlRecipeId)).isNull();
  }

  @Test
  void withdraw_adminGated_401Anonymous_403NonAdmin() throws Exception {
    mvc.perform(post(withdrawPath(JOB_ID))).andExpect(status().isUnauthorized());
    AuthedUser user = register(); // not allowlisted → fail-closed 403
    mvc.perform(post(withdrawPath(JOB_ID)).cookie(user.cookie())).andExpect(status().isForbidden());
    mvc.perform(post(restorePath(JOB_ID)).cookie(user.cookie())).andExpect(status().isForbidden());
  }
}
