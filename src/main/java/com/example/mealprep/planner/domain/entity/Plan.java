package com.example.mealprep.planner.domain.entity;

import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
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
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Aggregate root for a plan. Owns {@code days} via {@code cascade=ALL, orphanRemoval=true}; child
 * write paths bump this row's {@code @Version} via {@code OPTIMISTIC_FORCE_INCREMENT} (the locking
 * scheme lands with 01b/01j — 01a only sets up the optimistic-version column).
 *
 * <p>The {@code @EntityGraph(attributePaths = {"days", "days.slots",
 * "days.slots.scheduledRecipe"})} shortcut would trigger {@code MultipleBagFetchException} on
 * Hibernate 6 with three {@code @OneToMany List<>} chained collections. Per LLD + ticket gotchas
 * #1, the service touches children inside a {@code @Transactional(readOnly = true)} method to force
 * lazy loads while the session is open.
 */
@Entity
@Table(name = "planner_plans")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Plan {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "household_id", nullable = false)
  private UUID householdId;

  @Column(name = "week_start_date", nullable = false)
  private LocalDate weekStartDate;

  @Column(name = "generation", nullable = false)
  private int generation;

  @Column(name = "replaces_plan_id")
  private UUID replacesPlanId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private PlanStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_kind", nullable = false, length = 32)
  private TriggerKind triggerKind;

  @Column(name = "trigger_event_id")
  private UUID triggerEventId;

  @Column(name = "quality_warning", nullable = false)
  private boolean qualityWarning;

  @Column(name = "cold_start", nullable = false)
  private boolean coldStart;

  @Column(name = "ai_augmented", nullable = false)
  private boolean aiAugmented;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Column(name = "decision_id", nullable = false)
  private UUID decisionId;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "rejected_at")
  private Instant rejectedAt;

  @Column(name = "rejected_reason", length = 255)
  private String rejectedReason;

  @Column(name = "abandoned_at")
  private Instant abandonedAt;

  @Column(name = "abandoned_reason", length = 255)
  private String abandonedReason;

  @Type(JsonBinaryType.class)
  @Column(name = "score_breakdown", nullable = false, columnDefinition = "jsonb")
  private ScoreBreakdownDocument scoreBreakdown;

  @Type(JsonBinaryType.class)
  @Column(name = "rollup_summary", nullable = false, columnDefinition = "jsonb")
  private RollupSummaryDocument rollupSummary;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(
      mappedBy = "plan",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<Day> days = new ArrayList<>();
}
