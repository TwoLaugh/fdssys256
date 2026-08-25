package com.example.mealprep.discovery.graphimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.DataQuality;
import com.example.mealprep.discovery.api.dto.GraphBatchIngestReport;
import com.example.mealprep.discovery.api.dto.GraphBatchIngestReport.IngestedDish;
import com.example.mealprep.discovery.api.dto.GraphBatchIngestReport.RejectedDish;
import com.example.mealprep.discovery.config.GraphImportProperties;
import com.example.mealprep.discovery.domain.service.internal.graphimport.GraphBatchIngestServiceImpl;
import com.example.mealprep.discovery.domain.service.internal.graphimport.GraphImportNutritionRecalc;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import com.example.mealprep.recipe.spi.ImportedRecipeResult;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * G06 ingest runner unit matrix: every abort gate hit from both sides, the mixed-batch import loop
 * against mocked collaborators, and the static manifest/payload checks. Batch dirs are real files
 * under a temp dir because the runner reads the artifact directly.
 */
class GraphBatchIngestServiceTest {

  private static final String BATCH_ID = "batch-20260801-1";
  private static final String STAMP = "graph@1234abc+c@0123456789abcdef";
  private static final String SOURCE_KEY = "graph:camp-unit-2026-08";
  private static final String JOB_ID = "5d8f2c1e-0000-5000-8000-2f6b0e6c9a11";
  private static final UUID JOB_UUID = UUID.fromString(JOB_ID);
  private static final UUID TRACE_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID RECIPE_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID VERSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
  private static final String FP_A = "a".repeat(64); // clean dish
  private static final String FP_B = "b".repeat(64); // empty mealTypes
  private static final String FP_C = "c".repeat(64); // payload fingerprint mismatch
  private static final String FP_D = "d".repeat(64); // rejected in review
  private static final String PATH_ERROR =
      "batchPath must be an absolute directory path containing manifest.json";

  private final ObjectMapper mapper = new ObjectMapper();
  private final NutritionQueryService nutritionQueryService = mock(NutritionQueryService.class);
  private final RecipeWriteApi recipeWriteApi = mock(RecipeWriteApi.class);
  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final GraphImportNutritionRecalc nutritionRecalc = mock(GraphImportNutritionRecalc.class);

  @TempDir Path tempDir;

  private GraphBatchIngestServiceImpl service(boolean enabled, boolean allowRestricted) {
    return new GraphBatchIngestServiceImpl(
        new GraphImportProperties(enabled, allowRestricted),
        mapper,
        nutritionQueryService,
        recipeWriteApi,
        jdbcTemplate,
        nutritionRecalc);
  }

  // ===== flag + path gates =====

  @Test
  void flagOff_refusesBeforeTouchingAnyCollaborator() {
    GraphBatchIngestReport report = service(false, false).ingest(tempDir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_DISABLED);
    assertThat(report.batchId()).isNull();
    assertThat(report.jobId()).isNull();
    assertThat(report.created()).isZero();
    assertThat(report.dedupSkipped()).isZero();
    assertThat(report.notApproved()).isZero();
    assertThat(report.rejected()).isEmpty();
    assertThat(report.recipeIds()).isEmpty();
    assertThat(report.missingMappingKeys()).isEmpty();
    assertThat(report.errors()).isEmpty();
    assertThat(report.note()).isNull();
    verifyNoInteractions(nutritionQueryService, recipeWriteApi, jdbcTemplate, nutritionRecalc);
  }

  @Test
  void nullBatchPath_isInvalidBatch() {
    GraphBatchIngestReport report = service(true, false).ingest(null);

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.errors()).containsExactly(PATH_ERROR);
    assertThat(report.batchId()).isNull();
    assertThat(report.missingMappingKeys()).isEmpty();
  }

  @Test
  void relativeBatchPath_isInvalidBatch() {
    GraphBatchIngestReport report = service(true, false).ingest("relative/batch");

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.errors()).containsExactly(PATH_ERROR);
  }

  @Test
  void absoluteDirWithoutManifest_isInvalidBatch() {
    GraphBatchIngestReport report = service(true, false).ingest(tempDir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.errors()).containsExactly(PATH_ERROR);
  }

  @Test
  void lintFailedMarker_makesBatchUnshippable() throws Exception {
    Path dir = tempDir.resolve("linted");
    Files.createDirectories(dir);
    Files.write(dir.resolve("manifest.json"), mapper.writeValueAsBytes(validManifest()));
    Files.writeString(dir.resolve("LINT_FAILED"), "");

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.errors()).hasSize(1);
    assertThat(report.errors().get(0)).startsWith("LINT_FAILED marker present");
  }

  // ===== manifest + verdict gates =====

  @Test
  void unparseableManifest_isInvalidBatch() throws Exception {
    Path dir = tempDir.resolve("garbled");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("manifest.json"), "{not json");

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.batchId()).isNull();
    assertThat(report.errors()).hasSize(1);
    assertThat(report.errors().get(0)).startsWith("manifest.json unparseable:");
  }

  @Test
  void manifestSchemaViolation_abortsWithBatchIdNamed() throws Exception {
    Path dir = tempDir.resolve("bad-schema");
    Files.createDirectories(dir);
    ObjectNode manifest = validManifest();
    manifest.put("schema", "graph-batch/2");
    Files.write(dir.resolve("manifest.json"), mapper.writeValueAsBytes(manifest));

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.batchId()).isEqualTo(BATCH_ID);
    assertThat(report.errors()).containsExactly("manifest schema must be \"graph-batch/1\"");
  }

  @Test
  void verdictShaMismatch_isReplayRefusal() throws Exception {
    Path dir =
        writeBatch(
            Map.of(FP_A, dish(FP_A, JOB_UUID, List.of("dinner"), List.of())),
            List.of(FP_A),
            Map.of(),
            verdicts -> verdicts.put("manifest_sha256", "0".repeat(64)));

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.errors())
        .containsExactly(
            "manifest_sha256 mismatch: verdict file does not bind to this batch's stamped"
                + " manifest (replay refused)");
  }

  @Test
  void fingerprintWithoutVerdict_isIncompleteReview() throws Exception {
    Map<String, ImportedRecipeData> payloads = new LinkedHashMap<>();
    payloads.put(FP_A, dish(FP_A, JOB_UUID, List.of("dinner"), List.of()));
    payloads.put(FP_B, dish(FP_B, JOB_UUID, List.of("dinner"), List.of()));
    Path dir = writeBatch(payloads, List.of(FP_A), Map.of(), verdicts -> {});

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.errors())
        .containsExactly("review incomplete: not every batch fingerprint has a verdict (G09)");
  }

  // ===== whole-batch aborts after the verdict gate =====

  @Test
  void conflictingJobIdsAcrossPayloads_abortTheBatch() throws Exception {
    Map<String, ImportedRecipeData> payloads = new LinkedHashMap<>();
    payloads.put(FP_A, dish(FP_A, JOB_UUID, List.of("dinner"), List.of()));
    payloads.put(FP_B, dish(FP_B, TRACE_UUID, List.of("dinner"), List.of()));
    Path dir = writeBatch(payloads, List.of(FP_A, FP_B), Map.of(), verdicts -> {});

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_INVALID_BATCH);
    assertThat(report.errors()).hasSize(1);
    assertThat(report.errors().get(0)).startsWith("one-jobId-per-batch violated:");
    verifyNoInteractions(nutritionQueryService, recipeWriteApi, nutritionRecalc);
  }

  @Test
  void unresolvedMappingKey_abortsPreFlightAndNamesIt() throws Exception {
    Path dir =
        writeBatch(
            Map.of(FP_A, dish(FP_A, JOB_UUID, List.of("dinner"), List.of())),
            List.of(FP_A),
            Map.of(),
            verdicts -> {});
    when(nutritionQueryService.lookupIngredients(any())).thenReturn(List.of(mappingHit("rice")));

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_ABORTED_MISSING_KEYS);
    assertThat(report.missingMappingKeys()).containsExactly("broccoli");
    assertThat(report.errors()).hasSize(1);
    assertThat(report.errors().get(0)).startsWith("pre-flight failed:");
    verifyNoInteractions(recipeWriteApi, nutritionRecalc);
  }

  @Test
  void restrictedDietFlag_rejectsWholeBatchWhileGateClosed() throws Exception {
    Path dir =
        writeBatch(
            Map.of(FP_A, dish(FP_A, JOB_UUID, List.of("dinner"), List.of("vegan"))),
            List.of(FP_A),
            Map.of(),
            verdicts -> {});
    stubResolvedKeys();

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_REJECTED_RESTRICTED_DIET);
    assertThat(report.errors()).hasSize(1);
    assertThat(report.errors().get(0)).startsWith("restricted-diet flags present");
    assertThat(report.errors().get(0)).contains(FP_A);
    verifyNoInteractions(recipeWriteApi, nutritionRecalc);
  }

  @Test
  void restrictedDietFlag_importsWhenGateOpen() throws Exception {
    Path dir =
        writeBatch(
            Map.of(FP_A, dish(FP_A, JOB_UUID, List.of("dinner"), List.of("vegan"))),
            List.of(FP_A),
            Map.of(),
            verdicts -> {});
    stubResolvedKeys();
    stubHealthyImport();

    GraphBatchIngestReport report = service(true, true).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_OK);
    assertThat(report.created()).isEqualTo(1);
  }

  // ===== import loop =====

  @Test
  void mixedBatch_importsCleanDish_rejectsPerDish_writesReport() throws Exception {
    Map<String, ImportedRecipeData> payloads = new LinkedHashMap<>();
    payloads.put(FP_A, dish(FP_A, JOB_UUID, List.of("dinner"), List.of()));
    payloads.put(FP_B, dish(FP_B, JOB_UUID, List.of(), List.of()));
    payloads.put(FP_C, dish("f".repeat(64), JOB_UUID, List.of("dinner"), List.of()));
    payloads.put(FP_D, dish(FP_D, JOB_UUID, List.of("dinner"), List.of()));
    Path dir =
        writeBatch(payloads, List.of(FP_A, FP_B, FP_C), Map.of(FP_D, "too bland"), verdicts -> {});
    Files.writeString(dir.resolve("recipes").resolve("notes.txt"), "scratch");
    stubResolvedKeys();
    stubHealthyImport();

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_OK);
    assertThat(report.batchId()).isEqualTo(BATCH_ID);
    assertThat(report.jobId()).isEqualTo(JOB_ID);
    assertThat(report.created()).isEqualTo(1);
    assertThat(report.dedupSkipped()).isZero();
    assertThat(report.notApproved()).isEqualTo(1);
    assertThat(report.rejected())
        .containsExactly(
            new RejectedDish(FP_C, "contentFingerprint field does not match filename fingerprint"),
            new RejectedDish(FP_B, "mealTypes empty"));
    assertThat(report.recipeIds())
        .containsExactly(new IngestedDish(FP_A, RECIPE_ID, VERSION_ID, "CALCULATED"));
    assertThat(report.missingMappingKeys()).isEmpty();
    assertThat(report.errors()).isEmpty();
    assertThat(report.note()).isNull();

    // only the clean dish reaches the writer, stamped AI_GENERATED
    ArgumentCaptor<ImportedRecipeData> saved = ArgumentCaptor.forClass(ImportedRecipeData.class);
    verify(recipeWriteApi).saveImportedRecipe(saved.capture());
    assertThat(saved.getValue().contentFingerprint()).isEqualTo(FP_A);
    assertThat(saved.getValue().dataQuality()).isEqualTo(DataQuality.AI_GENERATED);
    assertThat(saved.getValue().sourceKey()).isEqualTo(SOURCE_KEY);
    assertThat(saved.getValue().extractionMethod()).isEqualTo(STAMP);
    assertThat(saved.getValue().jobId()).isEqualTo(JOB_UUID);

    ArgumentCaptor<ImportedRecipeData> recomputed =
        ArgumentCaptor.forClass(ImportedRecipeData.class);
    verify(nutritionRecalc).recompute(recomputed.capture(), eq(RECIPE_ID), eq(VERSION_ID));
    assertThat(recomputed.getValue().contentFingerprint()).isEqualTo(FP_A);

    // pre-flight resolved the keys of every parsed dish in one batch lookup
    verify(nutritionQueryService).lookupIngredients(Set.of("broccoli", "rice"));

    JsonNode reportFile = mapper.readTree(dir.resolve("ingest_report.json").toFile());
    assertThat(reportFile).isEqualTo(mapper.valueToTree(report));
  }

  @Test
  void dedupRerun_countsSkippedAndSurfacesTheNote() throws Exception {
    Path dir =
        writeBatch(
            Map.of(FP_A, dish(FP_A, JOB_UUID, List.of("dinner"), List.of())),
            List.of(FP_A),
            Map.of(),
            verdicts -> {});
    stubResolvedKeys();
    when(jdbcTemplate.queryForList("SELECT name FROM provision_equipment_catalogue", String.class))
        .thenReturn(List.of("hob"));
    when(recipeWriteApi.saveImportedRecipe(any()))
        .thenReturn(new ImportedRecipeResult(RECIPE_ID, VERSION_ID, false, "fingerprint match"));
    when(nutritionRecalc.recompute(any(), any(), any()))
        .thenReturn(new GraphImportNutritionRecalc.Outcome("CALCULATED", 2));

    GraphBatchIngestReport report = service(true, false).ingest(dir.toString());

    assertThat(report.status()).isEqualTo(GraphBatchIngestReport.STATUS_OK);
    assertThat(report.created()).isZero();
    assertThat(report.dedupSkipped()).isEqualTo(1);
    assertThat(report.recipeIds())
        .containsExactly(new IngestedDish(FP_A, RECIPE_ID, VERSION_ID, "CALCULATED"));
    assertThat(report.note()).startsWith("dedupSkipped > 0");
  }

  // ===== manifestErrors =====

  @Test
  void validManifestPassesEveryCheck() {
    assertThat(GraphBatchIngestServiceImpl.manifestErrors(validManifest())).isEmpty();
  }

  @Test
  void emptyManifestNamesEveryDefect() {
    List<String> errors = GraphBatchIngestServiceImpl.manifestErrors(mapper.createObjectNode());

    assertThat(errors).hasSize(8);
    assertThat(errors)
        .contains(
            "manifest schema must be \"graph-batch/1\"",
            "manifest.batch_id is required",
            "manifest.spike_commit is required",
            "manifest.created_utc is required",
            "manifest.corpus_fingerprint.sha is required",
            "manifest.campaign.campaign_id is required",
            "manifest.licence.lint must be \"passed\" (G20 stamped batch)");
    assertThat(errors)
        .anyMatch(e -> e.startsWith("manifest.engine_stamp.extraction_method must match"));
  }

  @Test
  void corpusFingerprintObjectWithBlankSha_isStillRequired() {
    ObjectNode manifest = validManifest();
    ((ObjectNode) manifest.get("corpus_fingerprint")).put("sha", " ");

    assertThat(GraphBatchIngestServiceImpl.manifestErrors(manifest))
        .containsExactly("manifest.corpus_fingerprint.sha is required");
  }

  @Test
  void shortStampFailsThePatternCheck() {
    ObjectNode manifest = validManifest();
    ((ObjectNode) manifest.get("engine_stamp")).put("extraction_method", "graph@1234abc");

    List<String> errors = GraphBatchIngestServiceImpl.manifestErrors(manifest);
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).startsWith("manifest.engine_stamp.extraction_method must match");
  }

  @Test
  void lintNotPassed_isAManifestError() {
    ObjectNode manifest = validManifest();
    ((ObjectNode) manifest.get("licence")).put("lint", "failed");

    assertThat(GraphBatchIngestServiceImpl.manifestErrors(manifest))
        .containsExactly("manifest.licence.lint must be \"passed\" (G20 stamped batch)");
  }

  // ===== structuralViolation =====

  @Test
  void cleanPayloadHasNoStructuralViolation() {
    assertThat(
            GraphBatchIngestServiceImpl.structuralViolation(
                FP_A, payload(FP_A, JOB_UUID, TRACE_UUID, STAMP, SOURCE_KEY), STAMP, SOURCE_KEY))
        .isNull();
  }

  @Test
  void structuralViolationPinsEachIdentityField() {
    assertThat(
            GraphBatchIngestServiceImpl.structuralViolation(
                FP_A, payload(FP_B, JOB_UUID, TRACE_UUID, STAMP, SOURCE_KEY), STAMP, SOURCE_KEY))
        .isEqualTo("contentFingerprint field does not match filename fingerprint");
    assertThat(
            GraphBatchIngestServiceImpl.structuralViolation(
                FP_A,
                payload(FP_A, JOB_UUID, TRACE_UUID, "graph@9999999+c@ffffffffffffffff", SOURCE_KEY),
                STAMP,
                SOURCE_KEY))
        .isEqualTo("extractionMethod does not match manifest engine_stamp.extraction_method");
    assertThat(
            GraphBatchIngestServiceImpl.structuralViolation(
                FP_A, payload(FP_A, JOB_UUID, TRACE_UUID, STAMP, "graph:other"), STAMP, SOURCE_KEY))
        .isEqualTo("sourceKey does not match manifest engine_stamp.source_key");
    assertThat(
            GraphBatchIngestServiceImpl.structuralViolation(
                FP_A, payload(FP_A, null, TRACE_UUID, STAMP, SOURCE_KEY), STAMP, SOURCE_KEY))
        .isEqualTo("jobId/traceId missing from payload");
    assertThat(
            GraphBatchIngestServiceImpl.structuralViolation(
                FP_A, payload(FP_A, JOB_UUID, null, STAMP, SOURCE_KEY), STAMP, SOURCE_KEY))
        .isEqualTo("jobId/traceId missing from payload");
  }

  // ===== fixtures =====

  private void stubResolvedKeys() {
    when(nutritionQueryService.lookupIngredients(any()))
        .thenReturn(List.of(mappingHit("rice"), mappingHit("broccoli")));
  }

  private void stubHealthyImport() {
    when(jdbcTemplate.queryForList("SELECT name FROM provision_equipment_catalogue", String.class))
        .thenReturn(List.of("hob"));
    when(recipeWriteApi.saveImportedRecipe(any()))
        .thenReturn(new ImportedRecipeResult(RECIPE_ID, VERSION_ID, true, null));
    when(nutritionRecalc.recompute(any(), any(), any()))
        .thenReturn(new GraphImportNutritionRecalc.Outcome("CALCULATED", 2));
  }

  private static IngredientNutritionDto mappingHit(String key) {
    return new IngredientNutritionDto(key, null, null, null, null, null, false, null, 0L);
  }

  private static ImportedRecipeData.ImportedIngredient ingredient(
      int order, String key, String grams) {
    return new ImportedRecipeData.ImportedIngredient(
        order, key, key, new BigDecimal(grams), "g", null, false);
  }

  private static ImportedRecipeData dish(
      String fingerprint, UUID jobId, List<String> mealTypes, List<String> dietaryFlags) {
    return new ImportedRecipeData(
        SOURCE_KEY,
        null,
        fingerprint,
        "Rice with broccoli",
        "A plain rice bowl.",
        List.of(ingredient(1, "rice", "180"), ingredient(2, "broccoli", "120")),
        List.of(new ImportedRecipeData.ImportedMethodStep(1, "Cook.", 10)),
        new ImportedRecipeData.ImportedRecipeMetadata(
            1, 10, 20, 30, List.of("hob"), null, null, null, null, mealTypes),
        new ImportedRecipeData.ImportedRecipeTags("rice", "simmer", null, null, dietaryFlags),
        STAMP,
        null,
        jobId,
        TRACE_UUID);
  }

  private static ImportedRecipeData payload(
      String fingerprint, UUID jobId, UUID traceId, String stamp, String sourceKey) {
    return new ImportedRecipeData(
        sourceKey,
        null,
        fingerprint,
        "Rice with broccoli",
        null,
        List.of(ingredient(1, "rice", "180")),
        List.of(new ImportedRecipeData.ImportedMethodStep(1, "Cook.", 5)),
        new ImportedRecipeData.ImportedRecipeMetadata(
            1, 5, 10, 15, List.of("hob"), null, null, null, null, List.of("dinner")),
        new ImportedRecipeData.ImportedRecipeTags(null, null, null, null, List.of()),
        stamp,
        null,
        jobId,
        traceId);
  }

  private ObjectNode validManifest() {
    ObjectNode manifest = mapper.createObjectNode();
    manifest.put("schema", "graph-batch/1");
    manifest.put("batch_id", BATCH_ID);
    manifest.put("created_utc", "2026-08-01T10:00:00Z");
    manifest.put("spike_commit", "1234abc");
    manifest.putObject("corpus_fingerprint").put("sha", "0123456789abcdef");
    manifest.putObject("campaign").put("campaign_id", "camp-unit-2026-08");
    ObjectNode stampNode = manifest.putObject("engine_stamp");
    stampNode.put("source_key", SOURCE_KEY);
    stampNode.put("extraction_method", STAMP);
    manifest.putObject("licence").put("lint", "passed");
    return manifest;
  }

  /** Writes a landed-format batch dir: recipes/*.json, stamped manifest, bound verdict file. */
  private Path writeBatch(
      Map<String, ImportedRecipeData> payloadsByFp,
      List<String> approved,
      Map<String, String> rejectedReasons,
      Consumer<ObjectNode> verdictMutator)
      throws Exception {
    Path dir = tempDir.resolve(BATCH_ID);
    Files.createDirectories(dir.resolve("recipes"));
    Files.createDirectories(dir.resolve("review"));
    for (Map.Entry<String, ImportedRecipeData> entry : payloadsByFp.entrySet()) {
      Files.writeString(
          dir.resolve("recipes").resolve(entry.getKey() + ".json"),
          mapper.writeValueAsString(entry.getValue()));
    }
    byte[] manifestBytes = mapper.writeValueAsBytes(validManifest());
    Files.write(dir.resolve("manifest.json"), manifestBytes);

    ObjectNode verdicts = mapper.createObjectNode();
    verdicts.put("schema", "graph-review/1");
    verdicts.put("batch_id", BATCH_ID);
    verdicts.put(
        "manifest_sha256",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)));
    verdicts.put("review_mode", "full");
    verdicts.put("reviewed_by", "irene");
    verdicts.put("reviewed_at", "2026-08-01T11:00:00Z");
    ArrayNode approvedArr = verdicts.putArray("approved");
    approved.forEach(approvedArr::add);
    ArrayNode rejectedArr = verdicts.putArray("rejected");
    rejectedReasons.forEach(
        (fp, reason) -> rejectedArr.addObject().put("fp", fp).put("reason", reason));
    verdictMutator.accept(verdicts);
    Files.writeString(
        dir.resolve("review").resolve("approved.json"), mapper.writeValueAsString(verdicts));
    return dir;
  }
}
