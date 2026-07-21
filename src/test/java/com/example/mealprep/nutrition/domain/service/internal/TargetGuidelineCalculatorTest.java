package com.example.mealprep.nutrition.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.domain.entity.BaselineActivityLevel;
import com.example.mealprep.nutrition.domain.entity.BiologicalSex;
import com.example.mealprep.nutrition.domain.entity.Goal;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Unit tests for the guideline-default calculator — pure arithmetic, no Spring/DB. */
class TargetGuidelineCalculatorTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 6, 18);
  private final TargetGuidelineCalculator calc = new TargetGuidelineCalculator();

  @Test
  void mifflin_st_jeor_bmr_male_and_female() {
    // Male 80 kg, 180 cm, 30 yo: 10*80 + 6.25*180 - 5*30 + 5 = 1780.
    assertThat(calc.bmr(BiologicalSex.MALE, 30, bd(80), bd(180))).isEqualTo(1780);
    // Female same body: ...- 161 instead of +5 = 1614.
    assertThat(calc.bmr(BiologicalSex.FEMALE, 30, bd(80), bd(180))).isEqualTo(1614);
  }

  @Test
  void calorie_target_is_bmr_times_activity_times_goal() {
    // BMR 1780 × moderately active 1.55 = 2759 TDEE; MAINTAIN adds 0.
    assertThat(
            calc.targetCalories(
                BiologicalSex.MALE,
                30,
                bd(80),
                bd(180),
                BaselineActivityLevel.MODERATELY_ACTIVE,
                Goal.MAINTAIN))
        .isEqualTo(2759);
    // LOSE applies -500.
    assertThat(
            calc.targetCalories(
                BiologicalSex.MALE,
                30,
                bd(80),
                bd(180),
                BaselineActivityLevel.MODERATELY_ACTIVE,
                Goal.LOSE_WEIGHT))
        .isEqualTo(2259);
  }

  @Test
  void calorie_target_floors_at_safe_minimum() {
    // Small sedentary person cutting would compute < 1200 → floored.
    int kcal =
        calc.targetCalories(
            BiologicalSex.FEMALE,
            60,
            bd(45),
            bd(150),
            BaselineActivityLevel.SEDENTARY,
            Goal.LOSE_WEIGHT);
    assertThat(kcal).isEqualTo(1200);
  }

  @Test
  void protein_floor_is_one_point_eight_grams_per_kg() {
    assertThat(calc.proteinFloorG(bd(80))).isEqualByComparingTo("144.0");
    assertThat(calc.proteinFloorG(bd(62.5))).isEqualByComparingTo("112.5");
  }

  @Test
  void fibre_is_fourteen_grams_per_thousand_kcal() {
    assertThat(calc.fibreG(2000)).isEqualByComparingTo("28.0");
    assertThat(calc.fibreG(2759)).isEqualByComparingTo("38.6");
  }

  @Test
  void fat_is_thirty_percent_of_calories_and_carbs_fill_the_rest() {
    BigDecimal fat = calc.fatG(2759); // 0.30*2759/9
    assertThat(fat).isEqualByComparingTo("92.0");
    BigDecimal protein = calc.proteinFloorG(bd(80)); // 144.0
    // carbs = (2759 - 144*4 - 92*9) / 4 = 1355/4 = 338.75 -> 338.8
    assertThat(calc.carbsG(2759, protein, fat)).isEqualByComparingTo("338.8");
    // The macro grams reconstruct ~the calorie target (within rounding).
    double kcal =
        protein.doubleValue() * 4
            + calc.carbsG(2759, protein, fat).doubleValue() * 4
            + fat.doubleValue() * 9;
    assertThat(kcal).isCloseTo(2759, org.assertj.core.data.Offset.offset(4.0));
  }

  @Test
  void carbs_never_go_negative_when_protein_plus_fat_exceeds_calories() {
    // Very high protein vs a tiny calorie target -> carbs clamp to 0, not negative.
    assertThat(calc.carbsG(1000, bd(200), bd(50))).isEqualByComparingTo("0.0");
  }

  @Test
  void age_group_resolves_to_the_dri_bands() {
    assertThat(calc.ageGroup(LocalDate.of(2001, 1, 1), TODAY)).isEqualTo("19-30"); // 25
    assertThat(calc.ageGroup(LocalDate.of(1986, 1, 1), TODAY)).isEqualTo("31-50"); // 40
    assertThat(calc.ageGroup(LocalDate.of(1960, 1, 1), TODAY)).isEqualTo("51-70"); // 66
    // clamps: under-19 -> youngest band, over-70 -> oldest band.
    assertThat(calc.ageGroup(LocalDate.of(2015, 1, 1), TODAY)).isEqualTo("19-30"); // 11
    assertThat(calc.ageGroup(LocalDate.of(1940, 1, 1), TODAY)).isEqualTo("51-70"); // 86
  }

  private static BigDecimal bd(double v) {
    return BigDecimal.valueOf(v);
  }
}
