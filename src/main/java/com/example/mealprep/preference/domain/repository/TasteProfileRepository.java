package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link TasteProfile}. Package-private to the preference module —
 * cross-module callers go through {@code TasteProfileQueryService} / {@code
 * TasteProfileUpdateService}.
 */
public interface TasteProfileRepository extends JpaRepository<TasteProfile, UUID> {

  Optional<TasteProfile> findByUserId(UUID userId);

  List<TasteProfile> findByUserIdIn(Collection<UUID> userIds);

  /**
   * Used by the async embedding listener / a backfill job to find profiles needing (re)embedding.
   */
  List<TasteProfile> findByTasteVectorStatus(TasteVectorStatus status);

  /**
   * Direct UPDATE for the taste-vector columns — bypasses Hibernate's full-entity save (which would
   * re-write the JSONB {@code document} column and bump {@code @Version}, colliding with any
   * concurrent write and needlessly churning the row). The async listener calls this exclusively.
   * Native SQL casts the bound varchar parameter to pgvector and flips {@code taste_vector_status}
   * to {@code EMBEDDED} only when the supplied {@code docVersion} still matches the profile's
   * current {@code document_version} — a stale embedding (the document moved on while the embed was
   * in flight) does NOT clobber a fresher state; it leaves the status PENDING so the newer change's
   * embed wins. Returns the number of rows updated (0 = stale / vanished).
   */
  @Modifying
  @Query(
      value =
          "UPDATE preference_taste_profile"
              + " SET taste_vector = CAST(:vector AS vector),"
              + " taste_vector_model_id = :modelId,"
              + " taste_vector_doc_version = :docVersion,"
              + " taste_vector_embedded_at = :embeddedAt,"
              + " taste_vector_status = 'EMBEDDED'"
              + " WHERE id = :id AND document_version = :docVersion",
      nativeQuery = true)
  int updateTasteVector(
      @Param("id") UUID id,
      @Param("vector") String vector,
      @Param("modelId") String modelId,
      @Param("docVersion") int docVersion,
      @Param("embeddedAt") Instant embeddedAt);

  /**
   * Flip {@code taste_vector_status} to {@code FAILED} without touching the vector (it was never
   * set) — but only if the profile is still at {@code docVersion}, so a terminal failure for an old
   * document does not stamp FAILED over a newer change that is already re-embedding. Returns rows
   * updated.
   */
  @Modifying
  @Query(
      value =
          "UPDATE preference_taste_profile SET taste_vector_status = 'FAILED'"
              + " WHERE id = :id AND document_version = :docVersion",
      nativeQuery = true)
  int markTasteVectorFailed(@Param("id") UUID id, @Param("docVersion") int docVersion);

  /**
   * Cosine-distance nearest neighbours to {@code queryVector} among profiles that already have an
   * embedded vector, excluding {@code excludeUserId} (typically the querying user). Uses the
   * pgvector {@code <=>} cosine-distance operator backed by the partial HNSW index. Returns rows
   * ordered nearest-first as {@code [user_id (uuid), distance (double precision)]} tuples; cosine
   * distance ∈ [0,2], where smaller = more similar (similarity = 1 - distance).
   *
   * <p>The query vector is bound as the pgvector text literal and cast server-side, mirroring the
   * write path. Self-scoped recommendation use-cases pass the user's own id as {@code
   * excludeUserId} so the user does not match themselves.
   */
  @Query(
      value =
          "SELECT p.user_id, (p.taste_vector <=> CAST(:queryVector AS vector)) AS distance"
              + " FROM preference_taste_profile p"
              + " WHERE p.taste_vector IS NOT NULL AND p.user_id <> :excludeUserId"
              + " ORDER BY distance ASC LIMIT :limit",
      nativeQuery = true)
  List<Object[]> findNearestUsersByTasteVector(
      @Param("queryVector") String queryVector,
      @Param("excludeUserId") UUID excludeUserId,
      @Param("limit") int limit);
}
