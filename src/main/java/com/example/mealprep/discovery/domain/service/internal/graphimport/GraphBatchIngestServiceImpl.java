package com.example.mealprep.discovery.domain.service.internal.graphimport;

import com.example.mealprep.discovery.api.dto.GraphBatchIngestReport;
import com.example.mealprep.discovery.api.dto.GraphBatchIngestReport.IngestedDish;
import com.example.mealprep.discovery.api.dto.GraphBatchIngestReport.RejectedDish;
import com.example.mealprep.discovery.config.GraphImportProperties;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import com.example.mealprep.recipe.spi.ImportedRecipeResult;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * G06 graph-batch ingest runner (design doc §6, component #11; D4 option (a)). Reads a batch
 * artifact directory ({@code manifest.json}, {@code review/approved.json}, {@code recipes/*.json})
 * and imports each APPROVED dish through {@code RecipeWriteApi.saveImportedRecipe} — the seam
 * ArchUnit already allows discovery to use ({@code RecipeBoundaryTest}, no rule change).
 *
 * <p>Order of gates (all zero-write until the import loop): feature flag (G11) → path sanity →
 * manifest schema/stamp/lint checks (graph-batch/1, G19/G20) → verdict contract (graph-review/1,
 * G09 — sha-bound, replay-refusing) → pre-flight key resolution against {@code IngredientMapping}
 * (re-asserts the G05 seed ordering) → restricted-diet policy (G11 Nadia gate) → per-dish
 * fail-closed validation matrix ({@link GraphBatchValidator}) → import with per-dish {@code
 * RuntimeException} catch (mirror of {@code DiscoveryJobRunner}'s per-candidate skip+log).
 *
 * <p>One {@code jobId} per batch is load-bearing for the G11 withdraw procedure. The landed export
 * format already stamps a deterministic per-batch {@code jobId} (UUIDv5 over the batch id) into
 * every payload; this runner uses it as-is and ABORTS if the payloads disagree — a fresh per-ingest
 * UUID would break re-run traceability for no gain (recorded deviation from the ticket text, which
 * predates the landed exporter).
 *
 * <p>Post-import nutrition recompute is G07's {@link GraphImportNutritionRecalc}: per imported
 * dish, the ENGINE recomputes per-serving nutrition from the artifact's exact-grams lines × the
 * G05-seeded mappings and persists via the writer SPI — spike numbers are never persisted (standing
 * law #2). A recompute gate failure counts the dish rejected; its recipe row stays honestly {@code
 * PENDING}.
 */
@Service
public class GraphBatchIngestServiceImpl
    implements com.example.mealprep.discovery.domain.service.GraphBatchIngestService {

  private static final Logger log = LoggerFactory.getLogger(GraphBatchIngestServiceImpl.class);

  static final String MANIFEST_SCHEMA = "graph-batch/1";
  static final Pattern STAMP_PATTERN = Pattern.compile("^graph@[0-9a-f]{7}\\+c@[0-9a-f]{16}$");
  static final Set<String> RESTRICTED_DIET_FLAGS = Set.of("vegan", "gluten_free");
  static final String INGEST_REPORT_FILENAME = "ingest_report.json";
  private static final String DEDUP_NOTE =
      "dedupSkipped > 0: fingerprint dedup returns the existing recipe WITHOUT comparing"
          + " content — a re-exported dish whose content changed under the same fingerprint"
          + " keeps the old content. G08's divergence check is the detector.";

  private final GraphImportProperties properties;
  private final ObjectMapper objectMapper;
  private final NutritionQueryService nutritionQueryService;
  private final RecipeWriteApi recipeWriteApi;
  private final JdbcTemplate jdbcTemplate;
  private final GraphImportNutritionRecalc nutritionRecalc;

  public GraphBatchIngestServiceImpl(
      GraphImportProperties properties,
      ObjectMapper objectMapper,
      NutritionQueryService nutritionQueryService,
      RecipeWriteApi recipeWriteApi,
      JdbcTemplate jdbcTemplate,
      GraphImportNutritionRecalc nutritionRecalc) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.nutritionQueryService = nutritionQueryService;
    this.recipeWriteApi = recipeWriteApi;
    this.jdbcTemplate = jdbcTemplate;
    this.nutritionRecalc = nutritionRecalc;
  }

  @Override
  public GraphBatchIngestReport ingest(String batchPath) {
    // 1. Flag gate (G11): hard stop, nothing read.
    if (!properties.enabled()) {
      return abort(null, GraphBatchIngestReport.STATUS_DISABLED, List.of(), null);
    }

    // 2. Path sanity (admin-gated single-operator tool — deliberately minimal).
    Path dir = Path.of(batchPath == null ? "" : batchPath);
    if (!dir.isAbsolute() || !Files.isRegularFile(dir.resolve("manifest.json"))) {
      return abort(
          null,
          GraphBatchIngestReport.STATUS_INVALID_BATCH,
          List.of("batchPath must be an absolute directory path containing manifest.json"),
          null);
    }
    if (Files.exists(dir.resolve("LINT_FAILED"))) {
      return abort(
          null,
          GraphBatchIngestReport.STATUS_INVALID_BATCH,
          List.of("LINT_FAILED marker present — batch is unshippable (G20)"),
          null);
    }

    // 3. Manifest schema + stamp (graph-batch/1, G19).
    JsonNode manifest;
    try {
      manifest = objectMapper.readTree(dir.resolve("manifest.json").toFile());
    } catch (IOException e) {
      return abort(
          null,
          GraphBatchIngestReport.STATUS_INVALID_BATCH,
          List.of("manifest.json unparseable: " + e.getClass().getSimpleName()),
          null);
    }
    String batchId = manifest.path("batch_id").asText(null);
    List<String> manifestErrors = manifestErrors(manifest);
    if (!manifestErrors.isEmpty()) {
      return abort(batchId, GraphBatchIngestReport.STATUS_INVALID_BATCH, manifestErrors, null);
    }
    String stamp = manifest.path("engine_stamp").path("extraction_method").asText();
    String sourceKey = manifest.path("engine_stamp").path("source_key").asText();

    // 4. Verdict contract (graph-review/1, G09): only a valid+complete file may drive an ingest.
    GraphReviewContract.Result verdicts = GraphReviewContract.validate(dir, objectMapper);
    if (verdicts.status() != GraphReviewContract.Status.COMPLETE) {
      List<String> errors = new ArrayList<>(verdicts.errors());
      if (verdicts.status() == GraphReviewContract.Status.INCOMPLETE) {
        errors.add("review incomplete: not every batch fingerprint has a verdict (G09)");
      }
      return abort(batchId, GraphBatchIngestReport.STATUS_INVALID_BATCH, errors, null);
    }

    // Only approved fingerprints are read from recipes/; the rest are counted notApproved.
    TreeSet<String> batchFps = listRecipeFps(dir);
    int notApproved =
        (int) batchFps.stream().filter(fp -> !verdicts.approved().contains(fp)).count();

    List<RejectedDish> rejected = new ArrayList<>();
    Map<String, ImportedRecipeData> parsed = new LinkedHashMap<>();
    for (String fp : verdicts.approved()) {
      Path file = dir.resolve("recipes").resolve(fp + ".json");
      if (!Files.isRegularFile(file)) {
        rejected.add(new RejectedDish(fp, "approved fingerprint has no recipes/ file"));
        continue;
      }
      ImportedRecipeData data;
      try {
        data = objectMapper.readValue(file.toFile(), ImportedRecipeData.class);
      } catch (IOException e) {
        rejected.add(new RejectedDish(fp, "payload unparseable: " + e.getClass().getSimpleName()));
        continue;
      }
      String structural = structuralViolation(fp, data, stamp, sourceKey);
      if (structural != null) {
        rejected.add(new RejectedDish(fp, structural));
        continue;
      }
      parsed.put(fp, data);
    }

    // One jobId per batch (engine-side batch handle for G11's withdraw procedure).
    Set<String> jobIds = new HashSet<>();
    parsed.values().forEach(d -> jobIds.add(String.valueOf(d.jobId())));
    if (jobIds.size() > 1) {
      return abort(
          batchId,
          GraphBatchIngestReport.STATUS_INVALID_BATCH,
          List.of("one-jobId-per-batch violated: payloads carry " + new TreeSet<>(jobIds)),
          null);
    }
    String jobId = jobIds.isEmpty() ? null : jobIds.iterator().next();

    // 5a. Pre-flight: every key must already resolve in IngredientMapping (G05 seeded, via the
    // nutrition read seam). Whole-batch abort — this re-asserts the export-side guarantee and
    // enforces the G05-before-ingest ordering.
    TreeSet<String> keys = new TreeSet<>();
    for (ImportedRecipeData data : parsed.values()) {
      if (data.ingredients() != null) {
        data.ingredients().forEach(i -> keys.add(i.ingredientMappingKey()));
      }
    }
    Set<String> resolved = new HashSet<>();
    for (IngredientNutritionDto dto : nutritionQueryService.lookupIngredients(keys)) {
      resolved.add(dto.searchTerm());
    }
    TreeSet<String> missingKeys = new TreeSet<>(keys);
    missingKeys.removeAll(resolved);
    if (!missingKeys.isEmpty()) {
      return abort(
          batchId,
          GraphBatchIngestReport.STATUS_ABORTED_MISSING_KEYS,
          List.of(
              "pre-flight failed: ingredient keys unresolved in IngredientMapping — run the"
                  + " G05 seed first"),
          List.copyOf(missingKeys));
    }

    // 5b. Restricted-diet exposure policy (G11 enforcement point): while the Nadia gate is
    // closed, any dish certifying vegan/gluten_free rejects the WHOLE batch.
    if (!properties.allowRestrictedDietFlags()) {
      TreeSet<String> offending = new TreeSet<>();
      for (Map.Entry<String, ImportedRecipeData> entry : parsed.entrySet()) {
        List<String> flags =
            entry.getValue().tags() == null ? null : entry.getValue().tags().dietaryFlags();
        if (flags != null && flags.stream().anyMatch(RESTRICTED_DIET_FLAGS::contains)) {
          offending.add(entry.getKey());
        }
      }
      if (!offending.isEmpty()) {
        return abort(
            batchId,
            GraphBatchIngestReport.STATUS_REJECTED_RESTRICTED_DIET,
            List.of(
                "restricted-diet flags present while"
                    + " mealprep.graph.import.allow-restricted-diet-flags=false: "
                    + offending),
            null);
      }
    }

    // 6. Import loop — fingerprint ascending (parsed preserves the approved sorted order).
    Set<String> equipmentCatalogue = equipmentCatalogue();
    int created = 0;
    int dedupSkipped = 0;
    List<IngestedDish> ingested = new ArrayList<>();
    for (Map.Entry<String, ImportedRecipeData> entry : parsed.entrySet()) {
      String fp = entry.getKey();
      ImportedRecipeData data = entry.getValue();
      List<String> violations = GraphBatchValidator.dishViolations(data, equipmentCatalogue);
      if (!violations.isEmpty()) {
        rejected.add(new RejectedDish(fp, String.join("; ", violations)));
        continue;
      }
      try {
        // G10: every graph dish is stamped AI_GENERATED regardless of what the payload carries —
        // the honesty rule's typed channel (recipe.dataQuality + recipe_imports.source_type).
        ImportedRecipeResult result =
            recipeWriteApi.saveImportedRecipe(
                data.withDataQuality(com.example.mealprep.core.types.DataQuality.AI_GENERATED));
        // G07: explicit engine recompute from the artifact's exact-grams lines. Its honesty
        // gates throw BEFORE any nutrition write, so a gate failure lands the dish in rejected
        // with the recipe row honestly PENDING (dedup re-runs rewrite identical values).
        GraphImportNutritionRecalc.Outcome outcome =
            nutritionRecalc.recompute(data, result.recipeId(), result.versionId());
        if (result.newlyCreated()) {
          created++;
        } else {
          dedupSkipped++;
        }
        ingested.add(
            new IngestedDish(fp, result.recipeId(), result.versionId(), outcome.nutritionStatus()));
      } catch (RuntimeException ex) {
        log.warn(
            "graph ingest import failed for {} ({}): {}",
            fp,
            ex.getClass().getSimpleName(),
            ex.getMessage());
        rejected.add(new RejectedDish(fp, ex.getClass().getSimpleName() + ": " + ex.getMessage()));
      }
    }

    GraphBatchIngestReport report =
        new GraphBatchIngestReport(
            batchId,
            jobId,
            GraphBatchIngestReport.STATUS_OK,
            created,
            dedupSkipped,
            notApproved,
            List.copyOf(rejected),
            List.copyOf(ingested),
            List.of(),
            List.of(),
            dedupSkipped > 0 ? DEDUP_NOTE : null);
    writeIngestReport(dir, report);
    log.info(
        "graph ingest batch={} jobId={} created={} dedupSkipped={} notApproved={} rejected={}",
        batchId,
        jobId,
        created,
        dedupSkipped,
        notApproved,
        rejected.size());
    return report;
  }

  /** Manifest checks beyond parseability — schema, identity fields, stamp shape, lint stamp. */
  public static List<String> manifestErrors(JsonNode manifest) {
    List<String> errors = new ArrayList<>();
    if (!MANIFEST_SCHEMA.equals(manifest.path("schema").asText(null))) {
      errors.add("manifest schema must be \"" + MANIFEST_SCHEMA + "\"");
    }
    for (String field : List.of("batch_id", "spike_commit", "created_utc")) {
      if (manifest.path(field).asText("").isBlank()) {
        errors.add("manifest." + field + " is required");
      }
    }
    // One batch = one spike commit = one corpus fingerprint (design §10).
    if (!manifest.path("corpus_fingerprint").isObject()
        || manifest.path("corpus_fingerprint").path("sha").asText("").isBlank()) {
      errors.add("manifest.corpus_fingerprint.sha is required");
    }
    if (manifest.path("campaign").path("campaign_id").asText("").isBlank()) {
      errors.add("manifest.campaign.campaign_id is required");
    }
    String stamp = manifest.path("engine_stamp").path("extraction_method").asText("");
    if (!STAMP_PATTERN.matcher(stamp).matches()) {
      errors.add(
          "manifest.engine_stamp.extraction_method must match graph@<7hex>+c@<16hex>"
              + " (32 chars — the recipe_imports.extraction_method column), got \""
              + stamp
              + "\"");
    }
    if (!"passed".equals(manifest.path("licence").path("lint").asText(null))) {
      errors.add("manifest.licence.lint must be \"passed\" (G20 stamped batch)");
    }
    return errors;
  }

  /**
   * Per-payload identity pins: the file's fingerprint field must equal its filename fp, and the
   * stamp/sourceKey must be the manifest's (the payload IS what {@code saveImportedRecipe} persists
   * into {@code recipe_imports}).
   */
  public static String structuralViolation(
      String fp, ImportedRecipeData data, String stamp, String sourceKey) {
    if (!fp.equals(data.contentFingerprint())) {
      return "contentFingerprint field does not match filename fingerprint";
    }
    if (!stamp.equals(data.extractionMethod())) {
      return "extractionMethod does not match manifest engine_stamp.extraction_method";
    }
    if (!sourceKey.equals(data.sourceKey())) {
      return "sourceKey does not match manifest engine_stamp.source_key";
    }
    if (data.jobId() == null || data.traceId() == null) {
      return "jobId/traceId missing from payload";
    }
    return null;
  }

  private TreeSet<String> listRecipeFps(Path dir) {
    TreeSet<String> fps = new TreeSet<>();
    try (var files = Files.list(dir.resolve("recipes"))) {
      files
          .map(p -> p.getFileName().toString())
          .filter(name -> name.endsWith(".json"))
          .forEach(name -> fps.add(name.substring(0, name.length() - ".json".length())));
    } catch (IOException e) {
      // The verdict contract already validated this listing; a race here is a per-run anomaly.
      log.warn("recipes/ listing failed post-verdict: {}", e.getMessage());
    }
    return fps;
  }

  private Set<String> equipmentCatalogue() {
    return new HashSet<>(
        jdbcTemplate.queryForList("SELECT name FROM provision_equipment_catalogue", String.class));
  }

  private void writeIngestReport(Path dir, GraphBatchIngestReport report) {
    DefaultPrettyPrinter printer =
        new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter(" ", "\n")); // LF always (batch artifact law)
    try {
      Files.writeString(
          dir.resolve(INGEST_REPORT_FILENAME),
          objectMapper.writer(printer).writeValueAsString(report) + "\n");
    } catch (IOException e) {
      // The DB writes committed; a report-file failure must not fail the batch. Loud log.
      log.error("failed to write {} into {}: {}", INGEST_REPORT_FILENAME, dir, e.getMessage());
    }
  }

  private static GraphBatchIngestReport abort(
      String batchId, String status, List<String> errors, List<String> missingKeys) {
    return new GraphBatchIngestReport(
        batchId,
        null,
        status,
        0,
        0,
        0,
        List.of(),
        List.of(),
        missingKeys == null ? List.of() : missingKeys,
        errors,
        null);
  }
}
