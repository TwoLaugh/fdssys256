package com.example.mealprep.adaptation.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Derivation-cache row for a recipe-version's character fingerprint. The catalogue holds the
 * fingerprint on the version row (read path); this table holds derivation provenance (which job +
 * body hash) so retries don't re-run the LLM. UPSERT on {@code (recipeId, branchId)}; unique {@code
 * versionId} answers "is this exact version cached?".
 *
 * <p>{@code fingerprint} stays as raw {@code JsonNode} in 01a to avoid coupling to {@code
 * CharacterFingerprintDocument} which lives in the recipe module. 01f's {@code
 * FingerprintRefresher} deserialises where it needs the typed shape. See {@code
 * lld/adaptation-pipeline.md} §Entities (line 279).
 */
@Entity
@Table(name = "adaptation_fingerprints")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AdaptationFingerprint {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "recipe_id", nullable = false)
  private UUID recipeId;

  @Column(name = "branch_id", nullable = false)
  private UUID branchId;

  @Column(name = "version_id", nullable = false, unique = true)
  private UUID versionId;

  @Column(name = "body_hash", nullable = false, length = 64)
  private String bodyHash;

  @Type(JsonBinaryType.class)
  @Column(name = "fingerprint", nullable = false, columnDefinition = "jsonb")
  private JsonNode fingerprint;

  @Column(name = "derived_by_job_id")
  private UUID derivedByJobId;

  @Column(name = "derived_at", nullable = false)
  private Instant derivedAt;
}
