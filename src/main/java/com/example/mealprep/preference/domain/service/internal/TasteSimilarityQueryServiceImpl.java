package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.api.dto.TasteSimilarUserDto;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.service.TasteSimilarityQueryService;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link TasteSimilarityQueryService}. Reads the querying user's stored taste
 * vector and runs the pgvector cosine-distance nearest-neighbour query (or a direct pairwise
 * cosine) via {@link TasteProfileRepository}. A user without an {@code EMBEDDED} vector yields
 * empty/neutral results — never an error.
 */
@Service
public class TasteSimilarityQueryServiceImpl implements TasteSimilarityQueryService {

  private static final Logger log = LoggerFactory.getLogger(TasteSimilarityQueryServiceImpl.class);

  private final TasteProfileRepository repository;

  public TasteSimilarityQueryServiceImpl(TasteProfileRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<float[]> getTasteVector(UUID userId) {
    return repository
        .findByUserId(userId)
        .filter(p -> p.getTasteVectorStatus() == TasteVectorStatus.EMBEDDED)
        .map(TasteProfile::getTasteVector)
        .filter(v -> v != null && v.length > 0);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> getTasteVectorLiteral(UUID userId) {
    // Reuse the exact same float[] → "[v0,v1,...]" serialisation findSimilarUsers binds, so the
    // planner's recipe query casts a byte-identical literal. No raw vector or dimension leaves the
    // module — only the opaque text literal the recipe side will CAST(... AS vector).
    return getTasteVector(userId).map(TasteProfileServiceImpl::formatPgVector);
  }

  @Override
  @Transactional(readOnly = true)
  public List<TasteSimilarUserDto> findSimilarUsers(UUID userId, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    Optional<float[]> queryVector = getTasteVector(userId);
    if (queryVector.isEmpty()) {
      log.debug(
          "findSimilarUsers: userId={} has no embedded taste vector; returning empty", userId);
      return List.of();
    }
    String pgVectorText = TasteProfileServiceImpl.formatPgVector(queryVector.get());
    List<Object[]> rows = repository.findNearestUsersByTasteVector(pgVectorText, userId, limit);
    return rows.stream()
        .map(
            row -> {
              UUID otherUserId = (UUID) row[0];
              double distance = ((Number) row[1]).doubleValue();
              // pgvector cosine distance = 1 - cosineSimilarity, range [0,2]. similarity = 1 -
              // distance, clamped to [0,1] defensively.
              double similarity = clamp01(1.0 - distance);
              return new TasteSimilarUserDto(otherUserId, similarity);
            })
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public OptionalDouble cosineSimilarity(UUID userIdA, UUID userIdB) {
    Optional<float[]> a = getTasteVector(userIdA);
    Optional<float[]> b = getTasteVector(userIdB);
    if (a.isEmpty() || b.isEmpty()) {
      return OptionalDouble.empty();
    }
    float[] va = a.get();
    float[] vb = b.get();
    if (va.length != vb.length) {
      return OptionalDouble.empty();
    }
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < va.length; i++) {
      dot += (double) va[i] * vb[i];
      normA += (double) va[i] * va[i];
      normB += (double) vb[i] * vb[i];
    }
    if (normA == 0 || normB == 0) {
      // Zero-norm vector → undefined cosine; the planner falls back to neutral.
      return OptionalDouble.empty();
    }
    double cos = dot / (Math.sqrt(normA) * Math.sqrt(normB));
    // Map raw cosine [-1,1] → [0,1] per the planner's PreferenceSubScore convention.
    return OptionalDouble.of(clamp01((cos + 1.0) / 2.0));
  }

  private static double clamp01(double v) {
    if (v < 0.0) {
      return 0.0;
    }
    return Math.min(v, 1.0);
  }
}
