package com.example.mealprep.recipe.domain.entity;

import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
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
 * Standalone aggregate root per LLD §Entities line 280. Overlays an ingredient swap on top of a
 * {@code RecipeVersion} without mutating the base version row.
 *
 * <p>State machine: {@code PROPOSED} on create; user-driven transitions to {@code ACCEPTED} or
 * {@code REJECTED}; {@code SUPERSEDED} on promote-to-version (terminal — a new {@code
 * RecipeVersion} is written with the substitution baked in).
 */
@Entity
@Table(name = "recipe_substitutions")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeSubstitution {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "recipe_id", nullable = false, updatable = false)
  private UUID recipeId;

  @Column(name = "version_id", nullable = false, updatable = false)
  private UUID versionId;

  @Column(name = "branch_id", nullable = false, updatable = false)
  private UUID branchId;

  @Column(name = "original_mapping_key", nullable = false, updatable = false, length = 160)
  private String originalMappingKey;

  @Column(
      name = "original_quantity",
      nullable = false,
      updatable = false,
      precision = 10,
      scale = 3)
  private BigDecimal originalQuantity;

  @Column(name = "original_unit", nullable = false, updatable = false, length = 16)
  private String originalUnit;

  @Column(name = "substitute_mapping_key", nullable = false, updatable = false, length = 160)
  private String substituteMappingKey;

  @Column(
      name = "substitute_quantity",
      nullable = false,
      updatable = false,
      precision = 10,
      scale = 3)
  private BigDecimal substituteQuantity;

  @Column(name = "substitute_unit", nullable = false, updatable = false, length = 16)
  private String substituteUnit;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, updatable = false, length = 32)
  private SubstitutionReason reason;

  @Column(name = "constraint_ref", length = 160)
  private String constraintRef;

  @Type(JsonBinaryType.class)
  @Column(name = "method_overlay", columnDefinition = "jsonb")
  private List<MethodOverlayLine> methodOverlay;

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  @Column(name = "temporary", nullable = false)
  private boolean temporary;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "applied_in_plan_ids", nullable = false, columnDefinition = "uuid[]")
  private UUID[] appliedInPlanIds;

  @Column(name = "application_count", nullable = false)
  private int applicationCount;

  @Column(name = "last_applied_at")
  private Instant lastAppliedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private SubstitutionState state;

  @Column(name = "promoted_to_version_id")
  private UUID promotedToVersionId;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "created_by_actor", nullable = false, updatable = false, length = 64)
  private String createdByActor;

  @Column(name = "adapter_trace_id", updatable = false)
  private UUID adapterTraceId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;
}
