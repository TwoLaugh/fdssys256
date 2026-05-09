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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-activity-level calorie / carb adjustment row. {@code UNIQUE(targets_id, activity_level)}
 * prevents duplicate rules. The per-day {@code DailyActivityLog} (which row is in effect on a given
 * date) ships in 01b alongside intake.
 */
@Entity
@Table(name = "nutrition_activity_adjustment")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ActivityAdjustment {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "targets_id", nullable = false)
  private NutritionTargets target;

  @Enumerated(EnumType.STRING)
  @Column(name = "activity_level", nullable = false, length = 24)
  private ActivityLevel activityLevel;

  @Column(name = "calorie_modifier", nullable = false)
  private int calorieModifier;

  @Column(name = "carb_modifier_g", nullable = false)
  private int carbModifierG;
}
