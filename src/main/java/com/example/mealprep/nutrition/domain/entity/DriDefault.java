package com.example.mealprep.nutrition.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read-only mapping over the {@code nutrition_dri_defaults} repeatable-seed table ({@code
 * R__nutrition_seed_dri_defaults.sql}): the per-{@code (age_group, sex)} DRI (Dietary Reference
 * Intake) micronutrient defaults loaded by {@link
 * com.example.mealprep.nutrition.domain.service.NutritionUpdateService#initialiseTargets} at
 * onboarding (nutrition-7).
 *
 * <p>Rows are never mutated by the application — the seed migration owns them — so this entity has
 * no {@code @Version} / timestamps and is only ever read.
 */
@Entity
@Table(name = "nutrition_dri_defaults")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class DriDefault {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "age_group", nullable = false, length = 16)
  private String ageGroup;

  @Column(name = "sex", nullable = false, length = 8)
  private String sex;

  @Column(name = "micro_name", nullable = false, length = 64)
  private String microName;

  @Column(name = "rda_value", nullable = false, precision = 10, scale = 3)
  private BigDecimal rdaValue;

  @Column(name = "unit", nullable = false, length = 16)
  private String unit;
}
