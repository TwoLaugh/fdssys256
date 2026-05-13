package com.example.mealprep.adaptation.domain.entity;

import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
 * Aggregate root for a recipe-adaptation proposal awaiting user accept / reject / supersede.
 * Supersession atomicity is enforced by the partial unique index {@code (recipe_id,
 * change_dimension) WHERE status = 'PENDING'}; the {@code @Version} is still present per
 * style-guide standard so concurrent accept/reject calls collide on optimistic-lock rather than
 * silently last-writer-wins.
 *
 * <p>See {@code lld/adaptation-pipeline.md} §V20260615120100 (lines 120-153) and §Entities (line
 * 277).
 */
@Entity
@Table(name = "adaptation_pending_changes")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PendingChange {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "recipe_id", nullable = false)
  private UUID recipeId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "change_dimension", nullable = false, length = 48)
  private ChangeDimension changeDimension;

  @Type(JsonBinaryType.class)
  @Column(name = "proposed_diff", nullable = false, columnDefinition = "jsonb")
  private JsonNode proposedDiff;

  @Enumerated(EnumType.STRING)
  @Column(name = "proposed_classification", nullable = false, length = 16)
  private AdaptationClassification proposedClassification;

  @Column(name = "base_version_id", nullable = false)
  private UUID baseVersionId;

  @Column(name = "base_branch_id", nullable = false)
  private UUID baseBranchId;

  @Column(name = "reasoning", nullable = false, columnDefinition = "text")
  private String reasoning;

  @Column(name = "nutritional_notes", columnDefinition = "text")
  private String nutritionalNotes;

  @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
  private BigDecimal confidence;

  @Column(name = "impact_score", nullable = false, precision = 4, scale = 3)
  private BigDecimal impactScore;

  @Column(name = "prompt_template_version", nullable = false, length = 40)
  private String promptTemplateVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private PendingChangeStatus status;

  @Column(name = "superseded_by")
  private UUID supersededBy;

  @Column(name = "accepted_version_id")
  private UUID acceptedVersionId;

  @Type(JsonBinaryType.class)
  @Column(name = "user_edits", columnDefinition = "jsonb")
  private JsonNode userEdits;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;
}
