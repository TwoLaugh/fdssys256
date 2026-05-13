package com.example.mealprep.planner.domain.entity;

import com.example.mealprep.core.types.SlotKind;
import io.hypersistence.utils.hibernate.type.array.ListArrayType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Per-meal slot inside a {@link Day}. Carries a denormalised {@code plan_id} so the {@code
 * idx_planner_meal_slots_plan_state} index hits without joining through days; 01j's re-opt scope
 * scan reads on that index.
 *
 * <p>{@code eaters} is {@code uuid[]} (Postgres array), mapped via hypersistence-utils' {@link
 * ListArrayType}. {@code text[]} variants have a known runtime trap in this stack (caught by
 * preference-01a); {@code uuid[]} is fine.
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

  @Column(name = "shared", nullable = false)
  private boolean shared;

  @Type(ListArrayType.class)
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
