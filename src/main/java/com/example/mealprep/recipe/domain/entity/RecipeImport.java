package com.example.mealprep.recipe.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
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
 * Provenance row for a recipe that was imported (currently URL only). One row per recipe (UNIQUE on
 * {@code recipe_id}); manually-created recipes from 01a's {@code POST /api/v1/recipes} have no
 * {@code RecipeImport} row.
 *
 * <p>The {@code source_payload} JSONB column is fetched LAZY so the hot {@code getById} read path
 * does not hydrate a multi-KB HTML excerpt. Only the {@code getImportProvenance} endpoint touches
 * the field.
 */
@Entity
@Table(name = "recipe_imports", uniqueConstraints = @UniqueConstraint(columnNames = {"recipe_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeImport {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "recipe_id", nullable = false, updatable = false)
  private UUID recipeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 16)
  private ImportSource sourceType;

  @Column(name = "source_url", columnDefinition = "text")
  private String sourceUrl;

  @Type(JsonBinaryType.class)
  @Basic(fetch = FetchType.LAZY)
  @Column(name = "source_payload", columnDefinition = "jsonb")
  private JsonNode sourcePayload;

  @Column(name = "extraction_method", length = 32)
  private String extractionMethod;

  @Column(name = "duplicate_of_recipe_id")
  private UUID duplicateOfRecipeId;

  @Column(name = "imported_at", nullable = false)
  private Instant importedAt;

  @Column(name = "imported_by_user_id", nullable = false)
  private UUID importedByUserId;

  /**
   * SHA-256 fingerprint of the parsed recipe content used by the discovery dedup probe. NULL for
   * 01b URL imports (which never wrote the field); discovery-01g writes this for every
   * WEB_DISCOVERED row and the (partial) UNIQUE index on this column anchors the dedup contract.
   */
  @Column(name = "content_fingerprint", length = 64)
  private String contentFingerprint;

  /**
   * Discovery source key (e.g. {@code "bbcgoodfood-sitemap"}) — discovery-fed rows only. NULL for
   * 01b URL imports.
   */
  @Column(name = "source_key", length = 64)
  private String sourceKey;

  /** Resolved canonical URL of the imported page — discovery-fed rows only. */
  @Column(name = "canonical_url", length = 2048)
  private String canonicalUrl;

  /** Extractor confidence (0.000-1.000) reported by the discovery pipeline. */
  @Column(name = "extraction_confidence", precision = 4, scale = 3)
  private BigDecimal extractionConfidence;

  /** Discovery job id (soft FK — discovery_jobs table is in a different module). */
  @Column(name = "job_id")
  private UUID jobId;
}
