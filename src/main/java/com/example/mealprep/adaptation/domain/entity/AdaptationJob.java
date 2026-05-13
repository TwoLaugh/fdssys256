package com.example.mealprep.adaptation.domain.entity;

import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Aggregate root for one enqueued adaptation job. Status walks {@code PENDING -> RUNNING -> DONE |
 * FAILED}; lifecycle owned by the worker pipeline (01c). 01a ships the JPA shape only — no
 * service-layer setters or transitions.
 *
 * <p>See {@code lld/adaptation-pipeline.md} §V20260615120000 (lines 83-110).
 */
@Entity
@Table(name = "adaptation_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AdaptationJob {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "recipe_id", nullable = false)
  private UUID recipeId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "catalogue", nullable = false, length = 16)
  private Catalogue catalogue;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 24)
  private JobSource source;

  @Enumerated(EnumType.STRING)
  @Column(name = "priority", nullable = false, length = 8)
  private JobPriority priority;

  @Enumerated(EnumType.STRING)
  @Column(name = "approval_policy", nullable = false, length = 16)
  private ApprovalPolicy approvalPolicy;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private JobStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "failure_reason", length = 64)
  private JobFailureReason failureReason;

  @Column(name = "failure_excerpt", length = 512)
  private String failureExcerpt;

  @Type(JsonBinaryType.class)
  @Column(name = "inputs", nullable = false, columnDefinition = "jsonb")
  private JsonNode inputs;

  @Column(name = "prompt_template_version", length = 40)
  private String promptTemplateVersion;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Column(name = "parent_decision_id")
  private UUID parentDecisionId;

  @Column(name = "enqueued_at", nullable = false)
  private Instant enqueuedAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "duration_ms")
  private Integer durationMs;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
