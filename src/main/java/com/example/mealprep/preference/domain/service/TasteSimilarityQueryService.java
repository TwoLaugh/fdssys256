package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.TasteSimilarUserDto;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Similarity surface over the taste-vector pgvector index (preference-5). The cosine-distance
 * machinery lives behind this interface so recommendation / planner consumers do not reach into the
 * preference repository. Re-exported via {@code PreferenceModule#tasteSimilarity()}.
 *
 * <p>All operations are user-scoped: callers pass a {@code userId}, and the service reads that
 * user's stored taste vector (no inbound raw vectors from other modules — keeps the embedding model
 * + dimension an internal detail). Returns empty / neutral results when a vector is not yet
 * embedded (status PENDING / FAILED), so a missing embedding is never an error.
 */
public interface TasteSimilarityQueryService {

  /**
   * The user's stored taste embedding ({@code text-embedding-3-small} → 1536 dims), or empty when
   * the vector is not yet computed (status PENDING / FAILED) or the user has no profile. Exposed as
   * the cross-module read seam the planner's {@code PreferenceSubScore} consumes once recipe-side
   * embeddings are surfaced on the recipe DTOs (see lld/preference.md §Similarity surface).
   */
  Optional<float[]> getTasteVector(UUID userId);

  /**
   * The user's stored taste embedding rendered as the pgvector text literal ({@code [v0,v1,...]})
   * that a recipe-side similarity query binds and casts server-side (mirroring {@link
   * #findSimilarUsers}, which binds the same format internally). Empty when the vector is not yet
   * computed (status PENDING / FAILED) or the user has no profile.
   *
   * <p>Lets the planner's recipe pool source rank candidates by taste cosine ({@code embedding <=>
   * :tasteVector}) without ever handling the raw {@code float[]} or knowing the embedding dimension
   * — the model/dimension stay an internal detail of this module; the planner just relays an opaque
   * literal string to the recipe query.
   */
  Optional<String> getTasteVectorLiteral(UUID userId);

  /**
   * Users whose taste profile is most similar to {@code userId}'s, nearest-first, excluding {@code
   * userId} itself. Backed by the pgvector cosine-distance operator over the partial HNSW index.
   * Empty when {@code userId} has no embedded vector yet. {@code limit} caps the result count.
   */
  List<TasteSimilarUserDto> findSimilarUsers(UUID userId, int limit);

  /**
   * Cosine similarity in {@code [0,1]} between two users' taste vectors, mapped from the raw cosine
   * {@code [-1,1]} via {@code (cos + 1) / 2} to match the planner's {@code PreferenceSubScore}
   * convention. Empty when either user lacks an embedded vector — the caller falls back to the
   * neutral {@code 0.5}.
   */
  OptionalDouble cosineSimilarity(UUID userIdA, UUID userIdB);
}
