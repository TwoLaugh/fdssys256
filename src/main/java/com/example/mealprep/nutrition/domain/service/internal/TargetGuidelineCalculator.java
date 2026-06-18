package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.domain.entity.BaselineActivityLevel;
import com.example.mealprep.nutrition.domain.entity.BiologicalSex;
import com.example.mealprep.nutrition.domain.entity.Goal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import org.springframework.stereotype.Component;

/**
 * Computes <b>guideline-default</b> nutrition targets from a person's demographics (biological sex,
 * age, weight, height, baseline activity) + goal. These are sensible starting values the user can
 * then override in the targets editor — "default to the guideline, but controllable".
 *
 * <p>What is (and isn't) weight-driven, deliberately:
 *
 * <ul>
 *   <li><b>Calories</b> — Mifflin-St Jeor BMR (weight/height/age/sex) × PAL (activity) × goal delta.
 *       This is where weight legitimately drives the number.
 *   <li><b>Protein</b> — {@value #PROTEIN_G_PER_KG_STR} g per kg body weight (an athletic/high-protein
 *       default; the user picked 1.8). Weight-driven, as a floor.
 *   <li><b>Fibre</b> — 14 g per 1000 kcal (IOM Adequate Intake), so it scales with the calorie need.
 *   <li><b>Fat / carbs</b> — fat at 30% of kcal; carbs fill the remaining energy. These keep the
 *       macro totals internally consistent with the calorie target (the user can ignore/loosen them).
 *   <li><b>Micronutrients</b> — NOT computed here. Micro DRIs are age/sex/life-stage <i>lookups</i>
 *       (not weight formulas); the service seeds them from the {@code nutrition_dri_defaults} table
 *       using {@link #ageGroup} + sex. This calculator only resolves the age band.
 * </ul>
 *
 * <p>Pure + deterministic (no DB, no clock of its own — the caller passes {@code today}), so it is
 * unit-testable in isolation.
 */
@Component
public class TargetGuidelineCalculator {

  static final String PROTEIN_G_PER_KG_STR = "1.8";
  private static final BigDecimal PROTEIN_G_PER_KG = new BigDecimal(PROTEIN_G_PER_KG_STR);
  private static final int FIBRE_G_PER_1000_KCAL = 14;
  private static final BigDecimal FAT_FRACTION_OF_KCAL = new BigDecimal("0.30");
  private static final int KCAL_PER_G_PROTEIN = 4;
  private static final int KCAL_PER_G_CARB = 4;
  private static final int KCAL_PER_G_FAT = 9;

  // Goal calorie deltas applied to TDEE — a moderate, safe cut/surplus rather than aggressive.
  private static final int GOAL_LOSE_DELTA = -500;
  private static final int GOAL_GAIN_DELTA = 350;
  // Never default below this (a protective floor; the user can still go lower deliberately).
  private static final int MIN_CALORIES = 1200;

  /** Whole years between {@code dob} and {@code today}. */
  public int ageYears(LocalDate dob, LocalDate today) {
    return Period.between(dob, today).getYears();
  }

  /**
   * The DRI {@code age_group} band for the person's age. The seed currently covers adult bands only,
   * so under-19 clamps to {@code 19-30} and over-70 clamps to {@code 51-70} (a safe approximation
   * until child / 71+ bands are seeded).
   */
  public String ageGroup(LocalDate dob, LocalDate today) {
    int age = ageYears(dob, today);
    if (age <= 30) {
      return "19-30";
    }
    if (age <= 50) {
      return "31-50";
    }
    return "51-70";
  }

  /** Mifflin-St Jeor basal metabolic rate (kcal/day): {@code 10·kg + 6.25·cm − 5·age + s}. */
  public int bmr(BiologicalSex sex, int ageYears, BigDecimal weightKg, BigDecimal heightCm) {
    double s = sex == BiologicalSex.MALE ? 5.0 : -161.0;
    double bmr =
        10.0 * weightKg.doubleValue() + 6.25 * heightCm.doubleValue() - 5.0 * ageYears + s;
    return (int) Math.round(bmr);
  }

  /** Daily calorie target = BMR × activity PAL × goal delta, floored at {@value #MIN_CALORIES}. */
  public int targetCalories(
      BiologicalSex sex,
      int ageYears,
      BigDecimal weightKg,
      BigDecimal heightCm,
      BaselineActivityLevel activity,
      Goal goal) {
    int tdee = (int) Math.round(bmr(sex, ageYears, weightKg, heightCm) * activity.palMultiplier());
    int delta =
        switch (goal) {
          case LOSE_WEIGHT -> GOAL_LOSE_DELTA;
          case GAIN_WEIGHT -> GOAL_GAIN_DELTA;
          default -> 0;
        };
    return Math.max(MIN_CALORIES, tdee + delta);
  }

  /** Protein floor in grams = {@value #PROTEIN_G_PER_KG_STR} g/kg × body weight. */
  public BigDecimal proteinFloorG(BigDecimal weightKg) {
    return PROTEIN_G_PER_KG.multiply(weightKg).setScale(1, RoundingMode.HALF_UP);
  }

  /** Fibre target in grams = 14 g per 1000 kcal (IOM Adequate Intake). */
  public BigDecimal fibreG(int calories) {
    return BigDecimal.valueOf((long) FIBRE_G_PER_1000_KCAL * calories)
        .divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP);
  }

  /** Fat target in grams = 30% of calories ÷ 9 kcal/g. */
  public BigDecimal fatG(int calories) {
    return FAT_FRACTION_OF_KCAL
        .multiply(BigDecimal.valueOf(calories))
        .divide(BigDecimal.valueOf(KCAL_PER_G_FAT), 1, RoundingMode.HALF_UP);
  }

  /**
   * Carbs target in grams = the energy left after protein + fat, ÷ 4 kcal/g (never negative). Keeps
   * the macro grams summing to the calorie target.
   */
  public BigDecimal carbsG(int calories, BigDecimal proteinG, BigDecimal fatG) {
    double remainingKcal =
        calories
            - proteinG.doubleValue() * KCAL_PER_G_PROTEIN
            - fatG.doubleValue() * KCAL_PER_G_FAT;
    double carbs = Math.max(0.0, remainingKcal) / KCAL_PER_G_CARB;
    return BigDecimal.valueOf(carbs).setScale(1, RoundingMode.HALF_UP);
  }
}
