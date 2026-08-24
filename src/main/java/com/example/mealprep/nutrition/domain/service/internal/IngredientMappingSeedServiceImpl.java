package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedReport;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedReport.RejectedSeedRow;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedReport.SeedCollision;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedRequest;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedRow;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.service.IngredientMappingSeedService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * G05 seed job. Processes the artifact rows in chunks of {@value #CHUNK_SIZE}, one transaction per
 * chunk (a mid-run crash is resumable because every row outcome is idempotent — no partial-row
 * state exists). A concurrent-insert race ({@link DataIntegrityViolationException} on the unique
 * {@code search_term}) rolls the chunk back and is retried once: the re-read reclassifies the raced
 * rows as identical/collision — the same first-writer-wins convention as {@code
 * IngredientMappingPipeline.persistOrReread}, lifted to chunk granularity.
 *
 * <p>Seeded rows carry {@code confidence = 1.000} (human-QA'd reference rows — intentionally above
 * the live pipeline's no-AI cap of 0.85) and {@code needsReview = false} (a {@code true} value
 * would force every consuming recipe to nutrition status {@code partial}). The document stores all
 * micros — vitamins included — under canonical keys in {@code micros} (the recompute reads {@code
 * doc.micros()} only) and an empty {@code vitamins} map.
 */
@Service
public class IngredientMappingSeedServiceImpl implements IngredientMappingSeedService {

  private static final Logger log = LoggerFactory.getLogger(IngredientMappingSeedServiceImpl.class);

  static final int CHUNK_SIZE = 200;
  static final BigDecimal SEED_CONFIDENCE = new BigDecimal("1.000");
  private static final String COLLISION_NOTE =
      "Existing row left untouched. search_term is updatable=false: adjudication means"
          + " delete + re-seed under human review (G05 first-writer-wins).";

  private final IngredientMappingRepository repository;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public IngredientMappingSeedServiceImpl(
      IngredientMappingRepository repository,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.repository = repository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public IngredientMappingSeedReport seed(IngredientMappingSeedRequest request) {
    List<IngredientMappingSeedRow> rows = request.rows();
    ChunkOutcome total = new ChunkOutcome();
    for (int from = 0; from < rows.size(); from += CHUNK_SIZE) {
      List<IngredientMappingSeedRow> chunk =
          rows.subList(from, Math.min(from + CHUNK_SIZE, rows.size()));
      total.add(seedChunkWithRaceRetry(chunk));
    }
    String status =
        total.collisions.isEmpty()
            ? IngredientMappingSeedReport.STATUS_OK
            : IngredientMappingSeedReport.STATUS_FAILED;
    log.info(
        "graph mapping seed: inserted={} skippedIdentical={} rejected={} collisions={} status={}",
        total.inserted,
        total.skippedIdentical,
        total.rejected.size(),
        total.collisions.size(),
        status);
    return new IngredientMappingSeedReport(
        total.inserted,
        total.skippedIdentical,
        List.copyOf(total.rejected),
        List.copyOf(total.collisions),
        status,
        request.meta());
  }

  private ChunkOutcome seedChunkWithRaceRetry(List<IngredientMappingSeedRow> chunk) {
    try {
      return transactionTemplate.execute(tx -> seedChunk(chunk));
    } catch (DataIntegrityViolationException race) {
      // Concurrent insert won a search_term inside this chunk; the chunk rolled back. Re-run
      // once — the fresh read reclassifies raced rows as identical/collision.
      log.info("graph mapping seed chunk race — retrying once ({})", race.getMessage());
      return transactionTemplate.execute(tx -> seedChunk(chunk));
    }
  }

  private ChunkOutcome seedChunk(List<IngredientMappingSeedRow> chunk) {
    ChunkOutcome outcome = new ChunkOutcome();
    List<IngredientMappingSeedRow> valid = new ArrayList<>(chunk.size());
    TreeSet<String> terms = new TreeSet<>();
    for (IngredientMappingSeedRow row : chunk) {
      String reason = rejectionReason(row);
      if (reason != null) {
        outcome.rejected.add(new RejectedSeedRow(row.searchTerm(), reason));
        continue;
      }
      valid.add(row);
      terms.add(row.searchTerm());
    }
    Map<String, IngredientMapping> existingByTerm = new HashMap<>();
    if (!terms.isEmpty()) {
      for (IngredientMapping m : repository.findBySearchTermIn(terms)) {
        existingByTerm.put(m.getSearchTerm(), m);
      }
    }
    Instant now = Instant.now(clock);
    List<IngredientMapping> toInsert = new ArrayList<>();
    for (IngredientMappingSeedRow row : valid) {
      IngredientMapping existing = existingByTerm.get(row.searchTerm());
      if (existing == null) {
        toInsert.add(buildEntity(row, now));
        outcome.inserted++;
        continue;
      }
      String divergingField = firstDivergingField(existing, row);
      if (divergingField == null) {
        outcome.skippedIdentical++;
      } else {
        outcome.collisions.add(
            new SeedCollision(
                row.searchTerm(),
                existing.getSource(),
                existing.getExternalId(),
                divergingField,
                COLLISION_NOTE));
      }
    }
    if (!toInsert.isEmpty()) {
      repository.saveAll(toInsert);
    }
    return outcome;
  }

  /** Row-level refusal reason, or {@code null} when the row is seedable. Report, not exception. */
  public static String rejectionReason(IngredientMappingSeedRow row) {
    if (row.searchTerm() == null || row.searchTerm().isBlank()) {
      return "searchTerm is required";
    }
    if (!row.searchTerm().equals(IngredientMappingKeys.normalise(row.searchTerm()))) {
      return "searchTerm is not engine normal-form (normalise(searchTerm) != searchTerm)";
    }
    if (row.source() == null) {
      return "source is required";
    }
    if (row.nutritionPer100g() == null) {
      return "nutritionPer100g is required";
    }
    return null;
  }

  private IngredientMapping buildEntity(IngredientMappingSeedRow row, Instant now) {
    return IngredientMapping.builder()
        .id(UUID.randomUUID())
        .searchTerm(row.searchTerm())
        .source(row.source())
        .externalId(row.externalId())
        .nutritionPer100g(buildDocument(row.nutritionPer100g()))
        .defaultPieceGrams(null)
        .confidence(SEED_CONFIDENCE)
        .needsReview(false)
        .lastVerifiedAt(now)
        .basisNote(row.basisNote())
        .build();
  }

  /**
   * Engine-document build: typed macros + canonical-key {@code micros}, empty {@code vitamins}. The
   * artifact's {@code calories} is fractional (linear per-100g translation); the entity field is
   * {@code Integer} — rounding HALF_UP happens here, at document-build time (G04 note).
   */
  public static IngredientNutritionDocument buildDocument(
      IngredientMappingSeedRow.SeedNutrition n) {
    Integer calories =
        n.calories() == null
            ? null
            : n.calories().setScale(0, RoundingMode.HALF_UP).intValueExact();
    Map<String, BigDecimal> micros =
        n.micros() == null ? Map.of() : new LinkedHashMap<>(n.micros());
    return new IngredientNutritionDocument(
        calories,
        n.proteinG(),
        n.carbsG(),
        n.fatG(),
        n.fibreG(),
        n.saturatedFatG(),
        null,
        micros,
        Map.of());
  }

  /**
   * Deep-equality classifier over (source, externalId, nutritionPer100g). Returns the first
   * diverging field name, or {@code null} when the existing row is identical to what the seed would
   * write (idempotent re-run). Numeric comparison is {@code compareTo}-based so BigDecimal scale
   * differences ({@code 0.40} vs {@code 0.4}) never manufacture a collision; empty and {@code null}
   * maps are equivalent.
   */
  public static String firstDivergingField(
      IngredientMapping existing, IngredientMappingSeedRow row) {
    if (existing.getSource() != row.source()) {
      return "source";
    }
    if (!Objects.equals(existing.getExternalId(), row.externalId())) {
      return "externalId";
    }
    IngredientNutritionDocument want = buildDocument(row.nutritionPer100g());
    IngredientNutritionDocument have = existing.getNutritionPer100g();
    if (have == null) {
      return "nutritionPer100g";
    }
    if (!Objects.equals(have.calories(), want.calories())) {
      return "nutritionPer100g.calories";
    }
    if (!numericallyEqual(have.proteinG(), want.proteinG())) {
      return "nutritionPer100g.proteinG";
    }
    if (!numericallyEqual(have.carbsG(), want.carbsG())) {
      return "nutritionPer100g.carbsG";
    }
    if (!numericallyEqual(have.fatG(), want.fatG())) {
      return "nutritionPer100g.fatG";
    }
    if (!numericallyEqual(have.fibreG(), want.fibreG())) {
      return "nutritionPer100g.fibreG";
    }
    if (!numericallyEqual(have.saturatedFatG(), want.saturatedFatG())) {
      return "nutritionPer100g.saturatedFatG";
    }
    if (!numericallyEqual(have.sugarG(), want.sugarG())) {
      return "nutritionPer100g.sugarG";
    }
    String microField = firstDivergingMapEntry(have.micros(), want.micros(), "micros");
    if (microField != null) {
      return microField;
    }
    return firstDivergingMapEntry(have.vitamins(), want.vitamins(), "vitamins");
  }

  private static String firstDivergingMapEntry(
      Map<String, BigDecimal> have, Map<String, BigDecimal> want, String mapName) {
    Map<String, BigDecimal> a = have == null ? Map.of() : have;
    Map<String, BigDecimal> b = want == null ? Map.of() : want;
    for (String key : new TreeSet<>(union(a, b))) {
      if (!a.containsKey(key) || !b.containsKey(key) || !numericallyEqual(a.get(key), b.get(key))) {
        return "nutritionPer100g." + mapName + "." + key;
      }
    }
    return null;
  }

  private static TreeSet<String> union(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
    TreeSet<String> keys = new TreeSet<>(a.keySet());
    keys.addAll(b.keySet());
    return keys;
  }

  private static boolean numericallyEqual(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return a.compareTo(b) == 0;
  }

  /** Mutable accumulator for one chunk (merged across chunks by {@link #seed}). */
  private static final class ChunkOutcome {
    int inserted;
    int skippedIdentical;
    final List<RejectedSeedRow> rejected = new ArrayList<>();
    final List<SeedCollision> collisions = new ArrayList<>();

    void add(ChunkOutcome other) {
      inserted += other.inserted;
      skippedIdentical += other.skippedIdentical;
      rejected.addAll(other.rejected);
      collisions.addAll(other.collisions);
    }
  }
}
