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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  @Column(name = "protein_is_hard_floor", nullable = false)
  private boolean proteinHardFloor;

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

  @Column(name = "carbs_is_hard_floor", nullable = false)
  private boolean carbsHardFloor;

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

  @Column(name = "fat_is_hard_floor", nullable = false)
  private boolean fatHardFloor;

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

  @Column(name = "fibre_is_hard_floor", nullable = false)
  private boolean fibreHardFloor;

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

  // ---------------- Merge (update-leg) ----------------
  //
  // The {@code replaceX} methods above clear-and-readd: every child is orphan-removed and a fresh
  // child (new UUID) is re-inserted. That is correct for the create/initialise legs (a brand-new
  // aggregate with no DB rows yet — the flush is all INSERTs). It is NOT safe when an existing row
  // is PUT-updated: Hibernate orders the INSERTs of the new-UUID children BEFORE the DELETEs of the
  // orphaned old children within a single flush, so any child whose natural key is unchanged (or a
  // new child reusing a key being removed) collides with the not-yet-deleted old row on the child
  // table's UNIQUE(targets_id, <natural key>) — SQLState 23505. The {@code mergeX} methods below
  // reconcile by natural key instead: matched keys are UPDATEd in place (no delete/insert), removed
  // keys are DELETEd (orphanRemoval), and genuinely new keys are INSERTed. A surviving key is never
  // delete+inserted in the same flush, and INSERTed keys are disjoint from DELETEd keys, so no
  // unique collision is possible. Used by the PUT update-leg; create/initialise keep {@code
  // replaceX}.

  /** Reconcile the per-meal distribution against {@code desired} by {@code mealSlot}, in place. */
  public void mergePerMealDistribution(List<PerMealDistributionEntry> desired) {
    Map<MealSlot, PerMealDistributionEntry> existing = new HashMap<>();
    for (PerMealDistributionEntry e : this.perMealDistribution) {
      existing.put(e.getMealSlot(), e);
    }
    Set<MealSlot> desiredKeys = new HashSet<>();
    List<PerMealDistributionEntry> toAdd = new ArrayList<>();
    if (desired != null) {
      for (PerMealDistributionEntry d : desired) {
        desiredKeys.add(d.getMealSlot());
        PerMealDistributionEntry match = existing.get(d.getMealSlot());
        if (match != null) {
          match.setCalorieTarget(d.getCalorieTarget());
          match.setProteinTargetG(d.getProteinTargetG());
        } else {
          d.setTarget(this);
          toAdd.add(d);
        }
      }
    }
    this.perMealDistribution.removeIf(e -> !desiredKeys.contains(e.getMealSlot()));
    this.perMealDistribution.addAll(toAdd);
  }

  /** Reconcile the micro-targets against {@code desired} by {@code nutrientKey}, in place. */
  public void mergeMicroTargets(List<MicroTarget> desired) {
    Map<String, MicroTarget> existing = new HashMap<>();
    for (MicroTarget m : this.microTargets) {
      existing.put(m.getNutrientKey(), m);
    }
    Set<String> desiredKeys = new HashSet<>();
    List<MicroTarget> toAdd = new ArrayList<>();
    if (desired != null) {
      for (MicroTarget d : desired) {
        desiredKeys.add(d.getNutrientKey());
        MicroTarget match = existing.get(d.getNutrientKey());
        if (match != null) {
          match.setTargetValue(d.getTargetValue());
          match.setUpperLimit(d.getUpperLimit());
          match.setSourcePreference(d.getSourcePreference());
          match.setNotes(d.getNotes());
          match.setHardFloor(d.isHardFloor());
        } else {
          d.setTarget(this);
          toAdd.add(d);
        }
      }
    }
    this.microTargets.removeIf(m -> !desiredKeys.contains(m.getNutrientKey()));
    this.microTargets.addAll(toAdd);
  }

  /**
   * Reconcile the activity adjustments against {@code desired} by {@code activityLevel}, in place.
   */
  public void mergeActivityAdjustments(List<ActivityAdjustment> desired) {
    Map<ActivityLevel, ActivityAdjustment> existing = new HashMap<>();
    for (ActivityAdjustment a : this.activityAdjustments) {
      existing.put(a.getActivityLevel(), a);
    }
    Set<ActivityLevel> desiredKeys = new HashSet<>();
    List<ActivityAdjustment> toAdd = new ArrayList<>();
    if (desired != null) {
      for (ActivityAdjustment d : desired) {
        desiredKeys.add(d.getActivityLevel());
        ActivityAdjustment match = existing.get(d.getActivityLevel());
        if (match != null) {
          match.setCalorieModifier(d.getCalorieModifier());
          match.setCarbModifierG(d.getCarbModifierG());
        } else {
          d.setTarget(this);
          toAdd.add(d);
        }
      }
    }
    this.activityAdjustments.removeIf(a -> !desiredKeys.contains(a.getActivityLevel()));
    this.activityAdjustments.addAll(toAdd);
  }

  /**
   * Reconcile the {@code @OneToOne} eating window against {@code desired} in place: update the
   * existing row's columns when both are present (no delete/insert, so the {@code
   * UNIQUE(targets_id)} on the window table is never transiently violated), create when none
   * exists, and orphan-remove when {@code desired} is {@code null}.
   */
  public void mergeEatingWindow(EatingWindow desired) {
    if (desired == null) {
      replaceEatingWindow(null);
      return;
    }
    if (this.eatingWindow == null) {
      desired.setTarget(this);
      this.eatingWindow = desired;
      return;
    }
    this.eatingWindow.setEnabled(desired.isEnabled());
    this.eatingWindow.setWindowStart(desired.getWindowStart());
    this.eatingWindow.setWindowEnd(desired.getWindowEnd());
    this.eatingWindow.setNotes(desired.getNotes());
  }
}
