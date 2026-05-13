package com.example.mealprep.adaptation.domain.entity;

import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Planner-hint row scoped to a specific recipe version. {@code invalidated_at} is the only
 * post-insert-mutable column, set when a new version supersedes this hint's parent — no
 * {@code @Version} since there's no concurrent-edit risk (one writer per version transition).
 *
 * <p>See {@code lld/adaptation-pipeline.md} §V20260615120400 (lines 218-238) and §Entities (line
 * 280).
 */
@Entity
@Table(name = "adaptation_planner_hints")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PlannerHintRecord {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "recipe_id", nullable = false)
  private UUID recipeId;

  @Column(name = "version_id", nullable = false)
  private UUID versionId;

  @Column(name = "branch_id", nullable = false)
  private UUID branchId;

  @Enumerated(EnumType.STRING)
  @Column(name = "hint_type", nullable = false, length = 48)
  private HintType hintType;

  @Column(name = "description", nullable = false, columnDefinition = "text")
  private String description;

  @Type(JsonBinaryType.class)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private JsonNode payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 16)
  private HintSeverity severity;

  @Column(name = "emitted_by_job_id")
  private UUID emittedByJobId;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "invalidated_at")
  private Instant invalidatedAt;
}
