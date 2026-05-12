package com.example.mealprep.recipe.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

/**
 * Append-only version row for a recipe. Owns the version body — ingredients + method steps +
 * metadata + tags — via {@code @OneToMany}/{@code @OneToOne(cascade=ALL, orphanRemoval=true)}.
 *
 * <p>Two list children means we cannot use a multi-attribute {@code @EntityGraph} (Hibernate throws
 * {@code MultipleBagFetchException}). The service touches each collection inside
 * {@code @Transactional(readOnly = true)} to force lazy load; the mapper applies explicit {@code
 * Comparator} ordering when building the DTO.
 */
@Entity
@Table(
    name = "recipe_versions",
    uniqueConstraints =
        @UniqueConstraint(columnNames = {"recipe_id", "branch_id", "version_number"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeVersion {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "recipe_id", nullable = false)
  private Recipe recipe;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "branch_id", nullable = false)
  private RecipeBranch branch;

  @Column(name = "version_number", nullable = false)
  private int versionNumber;

  @Column(name = "parent_version_id")
  private UUID parentVersionId;

  @Type(JsonBinaryType.class)
  @Column(name = "change_diff", nullable = false, columnDefinition = "jsonb")
  private JsonNode changeDiff;

  @Column(name = "change_reason")
  private String changeReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger", nullable = false, length = 32)
  private VersionTrigger trigger;

  @Type(JsonBinaryType.class)
  @Column(name = "character_fingerprint", columnDefinition = "jsonb")
  private JsonNode characterFingerprint;

  @Type(JsonBinaryType.class)
  @Column(name = "nutrition_per_serving", columnDefinition = "jsonb")
  private JsonNode nutritionPerServing;

  @Column(name = "embedding_status", nullable = false, length = 16)
  private String embeddingStatus;

  /**
   * Embedding vector (pgvector {@code vector(1536)}). NULL until the async listener succeeds.
   * Persisted via {@link RecipeEmbeddingConverter} which renders the {@code float[]} as the
   * pgvector text literal {@code '[v1,...,v1536]'} that pgvector implicitly casts to {@code
   * vector}.
   */
  // The AttributeConverter outputs the pgvector text format "[v1,v2,...]" as a String.
  // @JdbcTypeCode(SqlTypes.OTHER) tells Hibernate to bind via setObject(idx, str, Types.OTHER) —
  // the Postgres JDBC driver then sends an "unknown"-typed parameter that pgvector implicitly
  // casts to the column's vector(1536) type. Without this override Hibernate binds as varchar
  // and Postgres refuses (`column "embedding" is of type vector but expression is of type
  // character varying`, SQLState 42804).
  @Convert(converter = RecipeEmbeddingConverter.class)
  @JdbcTypeCode(SqlTypes.OTHER)
  @Column(name = "embedding", columnDefinition = "vector(1536)")
  private float[] embedding;

  @Column(name = "embedding_model_id", length = 96)
  private String embeddingModelId;

  @Column(name = "embedded_at")
  private Instant embeddedAt;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "created_by_actor", nullable = false, length = 64)
  private String createdByActor;

  @Column(name = "adapter_trace_id")
  private UUID adapterTraceId;

  // --- children: List<> + lazy-load inside @Transactional; no multi-bag @EntityGraph ---

  @OneToMany(
      mappedBy = "version",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<RecipeIngredient> ingredients = new ArrayList<>();

  @OneToMany(
      mappedBy = "version",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<RecipeMethodStep> methodSteps = new ArrayList<>();

  @OneToOne(
      mappedBy = "version",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  private RecipeMetadata metadata;

  @OneToOne(
      mappedBy = "version",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  private RecipeTags tags;
}
