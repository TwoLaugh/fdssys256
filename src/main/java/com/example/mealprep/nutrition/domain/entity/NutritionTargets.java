package com.example.mealprep.nutrition.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Aggregate root for a user's nutrition targets — one row per user (UNIQUE on {@code user_id}).
 *
 * <p>Owns three list children ({@link PerMealDistributionEntry}, {@link MicroTarget}, {@link
 * ActivityAdjustment}) plus the {@link EatingWindow} {@code @OneToOne}. The aggregate's
 * {@code @Version} covers concurrency for the whole graph; child entities have no version of their
 * own.
 *
 * <p>Three list children means the repository CANNOT use a multi-attribute {@code @EntityGraph} —
 * Hibernate throws {@code MultipleBagFetchException}. The service touches each list inside a
 * read-only transaction to force lazy load (4 SELECTs per read; the {@code @OneToOne} window joins
 * with the root SELECT). See {@link com.example.mealprep.nutrition.domain.repository}.
 *
 * <p>{@code userOverriddenDirections} is persisted as JSONB list-of-strings (not {@code text[]}) —
 * Hibernate's text[] mapping is brittle on Spring Boot 3.2.5 / hypersistence-utils-63 (same
 * workaround as {@code preference.HardConstraints.allergies}).
 */
@Entity
@Table(name = "nutrition_targets")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class NutritionTargets {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, unique = true, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "goal", nullable = false, length = 24)
  private Goal goal;

  // ---------------- Calories ----------------
  @Column(name = "daily_calorie_target", nullable = false)
  private int dailyCalorieTarget;

  @Column(name = "calorie_tolerance_under", nullable = false)
  private int calorieToleranceUnder;

  @Column(name = "calorie_tolerance_over", nullable = false)
  private int calorieToleranceOver;

  @Column(name = "calorie_enforcement", nullable = false, length = 24)
  private String calorieEnforcement;

  @Enumerated(EnumType.STRING)
  @Column(name = "calorie_direction", nullable = false, length = 24)
  private EnforcementDirection calorieDirection;

  // ---------------- Protein ----------------
  @Column(name = "protein_target_g", nullable = false, precision = 6, scale = 1)
  private BigDecimal proteinTargetG;

  @Column(name = "protein_floor_g", precision = 6, scale = 1)
  private BigDecimal proteinFloorG;

  @Column(name = "protein_enforcement", nullable = false, length = 24)
  private String proteinEnforcement;

  @Enumerated(EnumType.STRING)
  @Column(name = "protein_direction", nullable = false, length = 24)
  private EnforcementDirection proteinDirection;

  // ---------------- Carbs ----------------
  @Column(name = "carbs_target_g", nullable = false, precision = 6, scale = 1)
  private BigDecimal carbsTargetG;

  @Column(name = "carbs_floor_g", precision = 6, scale = 1)
  private BigDecimal carbsFloorG;

  @Column(name = "carbs_enforcement", nullable = false, length = 24)
  private String carbsEnforcement;

  @Enumerated(EnumType.STRING)
  @Column(name = "carbs_direction", nullable = false, length = 24)
  private EnforcementDirection carbsDirection;

  // ---------------- Fat ----------------
  @Column(name = "fat_target_g", nullable = false, precision = 6, scale = 1)
  private BigDecimal fatTargetG;

  @Column(name = "fat_floor_g", precision = 6, scale = 1)
  private BigDecimal fatFloorG;

  @Column(name = "fat_enforcement", nullable = false, length = 24)
  private String fatEnforcement;

  @Enumerated(EnumType.STRING)
  @Column(name = "fat_direction", nullable = false, length = 24)
  private EnforcementDirection fatDirection;

  // ---------------- Fibre ----------------
  @Column(name = "fibre_target_g", nullable = false, precision = 6, scale = 1)
  private BigDecimal fibreTargetG;

  @Column(name = "fibre_floor_g", precision = 6, scale = 1)
  private BigDecimal fibreFloorG;

  @Column(name = "fibre_enforcement", nullable = false, length = 24)
  private String fibreEnforcement;

  @Enumerated(EnumType.STRING)
  @Column(name = "fibre_direction", nullable = false, length = 24)
  private EnforcementDirection fibreDirection;

  // ---------------- Saturated fat ----------------
  @Column(name = "sat_fat_target_g", precision = 6, scale = 1)
  private BigDecimal satFatTargetG;

  @Enumerated(EnumType.STRING)
  @Column(name = "sat_fat_direction", nullable = false, length = 24)
  private EnforcementDirection satFatDirection;

  // ---------------- Notes + overrides ----------------
  @Column(name = "notes", length = 512)
  private String notes;

  @Type(JsonBinaryType.class)
  @Column(name = "user_overridden_directions", nullable = false, columnDefinition = "jsonb")
  private List<String> userOverriddenDirections;

  // ---------------- Children ----------------
  @OneToMany(
      mappedBy = "target",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<PerMealDistributionEntry> perMealDistribution = new ArrayList<>();

  @OneToMany(
      mappedBy = "target",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<MicroTarget> microTargets = new ArrayList<>();

  @OneToOne(
      mappedBy = "target",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  private EatingWindow eatingWindow;

  @OneToMany(
      mappedBy = "target",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private List<ActivityAdjustment> activityAdjustments = new ArrayList<>();

  // ---------------- Versioning + timestamps ----------------
  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Replace the per-meal distribution in place; preserves the parent's collection identity for
   * Hibernate. Cascade + orphanRemoval handle delete + insert.
   */
  public void replacePerMealDistribution(List<PerMealDistributionEntry> replacements) {
    this.perMealDistribution.clear();
    if (replacements != null) {
      for (PerMealDistributionEntry child : replacements) {
        child.setTarget(this);
        this.perMealDistribution.add(child);
      }
    }
  }

  /** Replace the micro-targets in place; preserves parent's collection identity for Hibernate. */
  public void replaceMicroTargets(List<MicroTarget> replacements) {
    this.microTargets.clear();
    if (replacements != null) {
      for (MicroTarget child : replacements) {
        child.setTarget(this);
        this.microTargets.add(child);
      }
    }
  }

  /**
   * Replace the activity adjustments in place; preserves parent's collection identity for
   * Hibernate.
   */
  public void replaceActivityAdjustments(List<ActivityAdjustment> replacements) {
    this.activityAdjustments.clear();
    if (replacements != null) {
      for (ActivityAdjustment child : replacements) {
        child.setTarget(this);
        this.activityAdjustments.add(child);
      }
    }
  }

  /**
   * Replace the {@code @OneToOne} eating window. Setting to {@code null} triggers orphanRemoval to
   * delete the row.
   */
  public void replaceEatingWindow(EatingWindow replacement) {
    if (this.eatingWindow != null) {
      this.eatingWindow.setTarget(null);
    }
    if (replacement != null) {
      replacement.setTarget(this);
    }
    this.eatingWindow = replacement;
  }
}
