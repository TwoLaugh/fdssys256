package com.example.mealprep.planner.domain.entity;

import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument;
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
import org.hibernate.annotations.Type;

/**
 * Standalone aggregate root — a materialised mid-week re-opt proposal the user can accept or
 * reject. Created by {@code MidWeekReoptCoordinator} (planner-01i) after running Stage
 * A&rarr;B&rarr;C scoped to the non-pinned slots of an active plan; promoted onto the live plan by
 * 01j's accept endpoint; status-swept to {@link ReoptSuggestionStatus#EXPIRED} by the 01l /
 * follow-up sweep.
 *
 * <p>Distinct from {@link ReoptSuggestion} (01a — the listener-side dedupe row keyed on {@code
 * (household, week, triggerEventId)}). This aggregate carries the concrete proposed slot diff.
 * Idempotent per {@code (planId, triggerEventId)} so a redelivered upstream event coalesces onto
 * the existing row rather than re-running the pipeline (invariant #4).
 *
 * <p>{@code @Version} optimistic lock — 01j's accept/reject runs in a separate request from the
 * 01k-driven write path, so the coordinator persists this row via a plain {@code save} inside its
 * own {@code @Transactional} (no cross-thread async path mutates it before commit), and 01j's
 * status transition is the only concurrent writer.
 */
@Entity
@Table(name = "planner_plan_reopt_suggestions")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MealPrepPlanReoptSuggestion {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "plan_id", nullable = false)
  private UUID planId;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_kind", nullable = false, length = 32)
  private ReoptTriggerKind triggerKind;

  @Column(name = "trigger_event_id", nullable = false)
  private UUID triggerEventId;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Column(name = "decision_id")
  private UUID decisionId;

  @Column(name = "summary", nullable = false, length = 255)
  private String summary;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private ReoptSuggestionStatus status;

  @Type(JsonBinaryType.class)
  @Column(name = "proposed_assignments", nullable = false, columnDefinition = "jsonb")
  private ProposedReoptAssignmentsDocument proposedAssignments;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "swept", nullable = false)
  private boolean swept;

  @Version
  @Column(name = "version", nullable = false)
  private long version;
}
