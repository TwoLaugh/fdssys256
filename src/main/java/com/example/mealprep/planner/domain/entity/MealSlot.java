package com.example.mealprep.planner.domain.entity;

import com.example.mealprep.core.types.SlotKind;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-meal slot inside a {@link Day}. Carries a denormalised {@code plan_id} so the {@code
 * idx_planner_meal_slots_plan_state} index hits without joining through days; 01j's re-opt scope
 * scan reads on that index.
 *
 * <p>{@code eaters} is {@code uuid[]} (Postgres array), mapped via Hibernate 6's native array
 * support ({@code @JdbcTypeCode(SqlTypes.ARRAY)}). Hypersistence-utils' {@code ListArrayType}
 * reports JDBC type {@code OTHER}, which the schema validator rejects against Postgres' reported
 * {@code _uuid (Types#ARRAY)} under {@code ddl-auto=validate}; native ARRAY mapping reports {@code
 * Types#ARRAY}, so {@code validate} passes (see {@code SchemaValidationIT}) while array read/write
 * is unchanged.
 */
@Entity
@Table(
    name = "planner_meal_slots",
    uniqueConstraints = @UniqueConstraint(columnNames = {"day_id", "slot_index"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MealSlot {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "day_id", nullable = false)
  private Day day;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plan_id", nullable = false)
  private Plan plan;

  @Column(name = "slot_index", nullable = false)
  private int slotIndex;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private SlotKind kind;

  @Column(name = "label", nullable = false, length = 64)
  private String label;

  @Column(name = "time_budget_min", nullable = false)
  private int timeBudgetMin;

  /**
   * Optional per-slot wall-clock meal-time override (planner-01m). {@code null} = no override; the
   * {@code getUpcomingSlots} projection resolves the effective time from the household owner's
   * lifestyle-config {@code meal_timing}, falling back to the slot-kind default. Left {@code null}
   * at composition in 01m; a future feature may populate it.
   */
  @Column(name = "meal_time")
  private LocalTime mealTime;

  /**
   * Reserved for the future pre-cook-actions feature (planner-01m). Unused (always {@code null}) as
   * of 01m; ships nullable so the column exists when the feature lands.
   */
  @Column(name = "prep_step_at_time")
  private LocalTime prepStepAtTime;

  @Column(name = "shared", nullable = false)
  private boolean shared;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "eaters", nullable = false, columnDefinition = "uuid[]")
  @Builder.Default
  private List<UUID> eaters = new ArrayList<>();

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private SlotState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "pinned_reason", length = 32)
  private PinnedReason pinnedReason;

  @OneToOne(
      mappedBy = "slot",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY,
      optional = true)
  private ScheduledRecipe scheduledRecipe;
}
