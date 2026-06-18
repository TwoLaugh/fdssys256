package com.example.mealprep.nutrition.domain.entity;

/**
 * The person's <i>baseline</i> activity level — their general weekly activity — used as the
 * Mifflin-St Jeor PAL (physical activity level) multiplier that turns BMR into TDEE (total daily
 * energy expenditure) for the calorie target. These are the standard PAL factors.
 *
 * <p>Distinct from {@link ActivityLevel}, which models per-DAY variation (rest vs training day) for
 * day-to-day calorie/carb adjustments layered on top of this baseline.
 */
public enum BaselineActivityLevel {
  SEDENTARY(1.2), // little/no exercise, desk job
  LIGHTLY_ACTIVE(1.375), // light exercise 1-3 days/week
  MODERATELY_ACTIVE(1.55), // moderate exercise 3-5 days/week
  VERY_ACTIVE(1.725), // hard exercise 6-7 days/week
  EXTRA_ACTIVE(1.9); // hard daily exercise + physical job

  private final double palMultiplier;

  BaselineActivityLevel(double palMultiplier) {
    this.palMultiplier = palMultiplier;
  }

  public double palMultiplier() {
    return palMultiplier;
  }
}
