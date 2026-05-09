package com.example.mealprep.nutrition.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-meal slice of a user's daily calorie / protein target. Child of {@link NutritionTargets} via
 * {@code @ManyToOne}. {@code UNIQUE(targets_id, meal_slot)} at the DB level prevents duplicate rows
 * for the same slot.
 */
@Entity
@Table(name = "nutrition_per_meal_distribution")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PerMealDistributionEntry {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "targets_id", nullable = false)
  private NutritionTargets target;

  @Enumerated(EnumType.STRING)
  @Column(name = "meal_slot", nullable = false, length = 16)
  private MealSlot mealSlot;

  @Column(name = "calorie_target", nullable = false)
  private int calorieTarget;

  @Column(name = "protein_target_g", nullable = false, precision = 6, scale = 1)
  private BigDecimal proteinTargetG;
}
