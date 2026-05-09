package com.example.mealprep.nutrition.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Micronutrient target. Identifier is {@code nutrient_key} (e.g. {@code "iron_mg"}, {@code
 * "vitamin_d_iu"}); the per-key DRI defaults seed ships with 01c. {@code UNIQUE(targets_id,
 * nutrient_key)} at the DB level prevents duplicate rows.
 */
@Entity
@Table(name = "nutrition_micro_target")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MicroTarget {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "targets_id", nullable = false)
  private NutritionTargets target;

  @Column(name = "nutrient_key", nullable = false, length = 48)
  private String nutrientKey;

  @Column(name = "target_value", precision = 10, scale = 3)
  private BigDecimal targetValue;

  @Column(name = "upper_limit", precision = 10, scale = 3)
  private BigDecimal upperLimit;

  @Column(name = "source_preference", length = 24)
  private String sourcePreference;

  @Column(name = "notes", length = 255)
  private String notes;
}
