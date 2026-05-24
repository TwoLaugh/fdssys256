package com.example.mealprep.planner.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Standalone aggregate root — a pending or resolved re-opt prompt. Idempotent on {@code
 * (householdId, weekStartDate, triggerEventId)} per LLD §V20260507120200; the listener
 * (planner-01k) dedupes via that tuple.
 */
@Entity
@Table(name = "planner_reopt_suggestions")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ReoptSuggestion {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "household_id", nullable = false)
  private UUID householdId;

  @Column(name = "week_start_date", nullable = false)
  private LocalDate weekStartDate;

  @Column(name = "plan_id", nullable = false)
  private UUID planId;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_kind", nullable = false, length = 32)
  private ReoptTriggerKind triggerKind;

  @Column(name = "trigger_event_id")
  private UUID triggerEventId;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "affected_slot_ids", nullable = false, columnDefinition = "uuid[]")
  @Builder.Default
  private List<UUID> affectedSlotIds = new ArrayList<>();

  @Column(name = "summary", nullable = false, length = 255)
  private String summary;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private ReoptStatus status;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;
}
