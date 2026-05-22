package com.example.mealprep.preference.domain.entity;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Aggregate root for the Tier 2 taste profile — the AI-maintained JSONB document described in
 * {@code design/preference-model.md} (lines 65-176). One row per user, unique on {@code user_id}.
 *
 * <p><b>Two version fields, deliberately.</b> {@link #documentVersion} is the HLD's monotonic
 * integer; it increments once per delta-batch apply and is used for history and rollback. {@link
 * #optimisticVersion} is JPA's {@code @Version} for concurrent-write safety (PUTs that race each
 * other → second writer sees an {@code OptimisticLockingFailureException} → 409). They serve
 * different purposes and are not interchangeable.
 *
 * <p>The {@code taste_vector} pgvector column itself is deferred to a follow-up ticket; only the
 * scalar status fields ({@link #tasteVectorStatus}, {@link #tasteVectorDocVersion}, {@link
 * #tasteVectorModelId}, {@link #tasteVectorEmbeddedAt}) ship in 01c so the future ticket adds only
 * the vector column + HNSW index.
 */
@Entity
@Table(name = "preference_taste_profile")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TasteProfile {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, unique = true, updatable = false)
  private UUID userId;

  @Type(JsonBinaryType.class)
  @Column(name = "document", nullable = false, columnDefinition = "jsonb")
  private TasteProfileDocument document;

  @Column(name = "document_version", nullable = false)
  private int documentVersion;

  @Column(name = "feedback_cursor", length = 64)
  private String feedbackCursor;

  @Column(name = "based_on_feedback_count", nullable = false)
  private int basedOnFeedbackCount;

  @Column(name = "last_delta_applied_at")
  private Instant lastDeltaAppliedAt;

  @Column(name = "last_token_estimate")
  private Integer lastTokenEstimate;

  @Enumerated(EnumType.STRING)
  @Column(name = "taste_vector_status", nullable = false, length = 16)
  private TasteVectorStatus tasteVectorStatus;

  @Column(name = "taste_vector_doc_version")
  private Integer tasteVectorDocVersion;

  @Column(name = "taste_vector_model_id", length = 96)
  private String tasteVectorModelId;

  @Column(name = "taste_vector_embedded_at")
  private Instant tasteVectorEmbeddedAt;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreatedDate
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
