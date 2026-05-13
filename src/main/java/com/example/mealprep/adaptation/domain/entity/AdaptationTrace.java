package com.example.mealprep.adaptation.domain.entity;

import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * Append-only diagnostic row for a job's full A -> B -> C -> Apply trace. No {@code @Version} —
 * once written, never mutated. {@code rawAiResponse} is null when Stage C is auto-skipped
 * (deterministic top-2x rule).
 *
 * <p>See {@code lld/adaptation-pipeline.md} §V20260615120200 (lines 164-190) and §Entities (line
 * 278).
 */
@Entity
@Table(name = "adaptation_traces")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AdaptationTrace {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "job_id", nullable = false, unique = true)
  private UUID jobId;

  @Column(name = "recipe_id", nullable = false)
  private UUID recipeId;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 24)
  private JobSource source;

  @Column(name = "prompt_template_name", nullable = false, length = 128)
  private String promptTemplateName;

  @Column(name = "prompt_template_version", nullable = false, length = 40)
  private String promptTemplateVersion;

  @Column(name = "ai_call_id")
  private UUID aiCallId;

  @Type(JsonBinaryType.class)
  @Column(name = "inputs_snapshot", nullable = false, columnDefinition = "jsonb")
  private JsonNode inputsSnapshot;

  @Type(JsonBinaryType.class)
  @Column(name = "raw_ai_response", columnDefinition = "jsonb")
  private JsonNode rawAiResponse;

  @Type(JsonBinaryType.class)
  @Column(name = "candidates", nullable = false, columnDefinition = "jsonb")
  private JsonNode candidates;

  @Column(name = "chosen_candidate_index")
  private Integer chosenCandidateIndex;

  @Enumerated(EnumType.STRING)
  @Column(name = "classification_decision", length = 16)
  private AdaptationClassification classificationDecision;

  @Type(JsonBinaryType.class)
  @Column(name = "final_diff", columnDefinition = "jsonb")
  private JsonNode finalDiff;

  @Column(name = "confidence", precision = 4, scale = 3)
  private BigDecimal confidence;

  @Column(name = "character_preservation_score", precision = 4, scale = 3)
  private BigDecimal characterPreservationScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "validation_result", nullable = false, length = 16)
  private ValidationResult validationResult;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome_kind", nullable = false, length = 24)
  private OutcomeKind outcomeKind;

  @Column(name = "outcome_target_id")
  private UUID outcomeTargetId;

  @Column(name = "duration_ms", nullable = false)
  private int durationMs;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
