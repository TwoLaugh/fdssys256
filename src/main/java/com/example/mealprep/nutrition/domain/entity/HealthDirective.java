package com.example.mealprep.nutrition.domain.entity;

import com.example.mealprep.nutrition.api.dto.DirectiveConfidence;
import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import com.example.mealprep.nutrition.api.dto.SafetyFindingDto;
import com.example.mealprep.nutrition.api.dto.SafetyGateVerdict;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
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
 * Aggregate root for a single health-platform directive (LLD §V20260502120700 lines 295-319). The
 * tuple {@code (sourcePlatform, externalDirectiveId)} is UNIQUE so the inbound endpoint is
 * idempotent. JSONB columns ({@code instruction_payload}, {@code user_modification_json}, {@code
 * safety_gate_findings}) carry the directive's payload + the user's accept-time override + the
 * gate's verdict reasoning.
 */
@Entity
@Table(
    name = "nutrition_health_directives",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_nutr_directives_platform_external",
            columnNames = {"source_platform", "external_directive_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HealthDirective {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "external_directive_id", nullable = false, updatable = false, length = 128)
  private String externalDirectiveId;

  @Column(name = "source_platform", nullable = false, updatable = false, length = 64)
  private String sourcePlatform;

  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private DirectiveStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "directive_type", nullable = false, updatable = false, length = 48)
  private DirectiveType directiveType;

  @Column(name = "evidence_summary", columnDefinition = "text")
  private String evidenceSummary;

  @Enumerated(EnumType.STRING)
  @Column(name = "evidence_confidence", length = 16)
  private DirectiveConfidence evidenceConfidence;

  @Type(JsonBinaryType.class)
  @Column(name = "instruction_payload", nullable = false, columnDefinition = "jsonb")
  private DirectiveInstructionDocument instructionPayload;

  @Column(name = "maps_to_model", nullable = false, length = 24)
  private String mapsToModel;

  @Column(name = "maps_to_tier", length = 48)
  private String mapsToTier;

  @Column(name = "temporary", nullable = false)
  private boolean temporary;

  @Column(name = "auto_expires_at")
  private Instant autoExpiresAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decided_by_user_id")
  private UUID decidedByUserId;

  @Type(JsonBinaryType.class)
  @Column(name = "user_modification_json", columnDefinition = "jsonb")
  private DirectiveInstructionDocument userModificationJson;

  @Column(name = "rejection_reason", length = 255)
  private String rejectionReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "safety_gate_verdict", length = 16)
  private SafetyGateVerdict safetyGateVerdict;

  @Type(JsonBinaryType.class)
  @Column(name = "safety_gate_findings", columnDefinition = "jsonb")
  private List<SafetyFindingDto> safetyGateFindings;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
