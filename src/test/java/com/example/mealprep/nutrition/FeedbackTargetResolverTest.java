package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.service.internal.FeedbackTargetResolver;
import com.example.mealprep.nutrition.domain.service.internal.FeedbackTargetResolver.ResolvedTarget;
import com.example.mealprep.nutrition.exception.InvalidFeedbackAdjustmentException;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FeedbackTargetResolver} — the {@code target} string → field-handle
 * allow-set (nutrition-01i). Covers calorie / macro / micro / per-meal resolution, the
 * micro-not-opted-in empty case, and unknown-target rejection.
 */
class FeedbackTargetResolverTest {

  private final FeedbackTargetResolver resolver = new FeedbackTargetResolver();

  @Test
  void resolve_calorieTarget_readsAndWritesDailyCalorieTargetAsInt() {
    NutritionTargets aggregate = NutritionTestData.targets().build(); // 2000 kcal default

    ResolvedTarget resolved = resolver.resolve(aggregate, "calorie_target").orElseThrow();

    assertThat(resolved.auditFieldPath()).isEqualTo("calorie_target");
    assertThat(resolved.integerValued()).isTrue();
    assertThat(resolved.currentValue()).isEqualByComparingTo("2000");
    resolved.apply(new BigDecimal("1900"));
    assertThat(aggregate.getDailyCalorieTarget()).isEqualTo(1900);
  }

  @Test
  void resolve_proteinTargetG_readsAndWritesMacro() {
    NutritionTargets aggregate = NutritionTestData.targets().build(); // protein 120.0 default

    ResolvedTarget resolved = resolver.resolve(aggregate, "protein_target_g").orElseThrow();

    assertThat(resolved.auditFieldPath()).isEqualTo("protein_target_g");
    assertThat(resolved.integerValued()).isFalse();
    assertThat(resolved.currentValue()).isEqualByComparingTo("120.0");
    resolved.apply(new BigDecimal("132.0"));
    assertThat(aggregate.getProteinTargetG()).isEqualByComparingTo("132.0");
  }

  @Test
  void resolve_allMacroKeys_areInTheAllowSet() {
    NutritionTargets aggregate = NutritionTestData.targets().build();
    for (String key :
        new String[] {"carbs_target_g", "fat_target_g", "fibre_target_g", "sat_fat_target_g"}) {
      assertThat(resolver.resolve(aggregate, key)).as(key).isPresent();
    }
  }

  @Test
  void resolve_existingMicro_resolvesToTargetValueWithDottedPath() {
    NutritionTargets aggregate =
        NutritionTestData.targets().withMicro("sodium_mg", new BigDecimal("2300")).build();

    ResolvedTarget resolved = resolver.resolve(aggregate, "micro.sodium_mg").orElseThrow();

    assertThat(resolved.auditFieldPath()).isEqualTo("micro.sodium_mg.target");
    assertThat(resolved.currentValue()).isEqualByComparingTo("2300");
    resolved.apply(new BigDecimal("2070"));
    assertThat(aggregate.getMicroTargets().get(0).getTargetValue()).isEqualByComparingTo("2070");
  }

  @Test
  void resolve_micronutrientNotOptedIn_returnsEmpty() {
    NutritionTargets aggregate = NutritionTestData.targets().build(); // no micro rows

    Optional<ResolvedTarget> resolved = resolver.resolve(aggregate, "micro.iron_mg");

    assertThat(resolved).isEmpty();
    assertThat(aggregate.getMicroTargets()).isEmpty(); // not created from feedback
  }

  @Test
  void resolve_perMealCalorieTarget_resolvesToSlotEntry() {
    NutritionTargets aggregate =
        NutritionTestData.targets()
            .withPerMeal(MealSlot.LUNCH, 600, new BigDecimal("40.0"))
            .build();

    ResolvedTarget resolved =
        resolver.resolve(aggregate, "per_meal.lunch.calorie_target").orElseThrow();

    assertThat(resolved.auditFieldPath()).isEqualTo("per_meal.lunch.calorie_target");
    assertThat(resolved.integerValued()).isTrue();
    assertThat(resolved.currentValue()).isEqualByComparingTo("600");
    resolved.apply(new BigDecimal("660"));
    assertThat(aggregate.getPerMealDistribution().get(0).getCalorieTarget()).isEqualTo(660);
  }

  @Test
  void resolve_perMealProteinTarget_resolvesToSlotEntry() {
    NutritionTargets aggregate =
        NutritionTestData.targets()
            .withPerMeal(MealSlot.DINNER, 700, new BigDecimal("40.0"))
            .build();

    ResolvedTarget resolved =
        resolver.resolve(aggregate, "per_meal.dinner.protein_target_g").orElseThrow();

    assertThat(resolved.auditFieldPath()).isEqualTo("per_meal.dinner.protein_target_g");
    assertThat(resolved.integerValued()).isFalse();
    assertThat(resolved.currentValue()).isEqualByComparingTo("40.0");
  }

  @Test
  void resolve_perMealForUnconfiguredSlot_throws() {
    NutritionTargets aggregate = NutritionTestData.targets().build(); // no per-meal rows

    assertThatThrownBy(() -> resolver.resolve(aggregate, "per_meal.lunch.calorie_target"))
        .isInstanceOf(InvalidFeedbackAdjustmentException.class);
  }

  @Test
  void resolve_unknownTarget_throws() {
    NutritionTargets aggregate = NutritionTestData.targets().build();

    assertThatThrownBy(() -> resolver.resolve(aggregate, "vibes"))
        .isInstanceOf(InvalidFeedbackAdjustmentException.class)
        .extracting(ex -> ((InvalidFeedbackAdjustmentException) ex).target())
        .isEqualTo("vibes");
  }

  @Test
  void resolve_perMealUnknownField_throws() {
    NutritionTargets aggregate =
        NutritionTestData.targets()
            .withPerMeal(MealSlot.LUNCH, 600, new BigDecimal("40.0"))
            .build();

    assertThatThrownBy(() -> resolver.resolve(aggregate, "per_meal.lunch.calories"))
        .isInstanceOf(InvalidFeedbackAdjustmentException.class);
  }

  @Test
  void resolve_perMealUnknownSlot_throws() {
    NutritionTargets aggregate = NutritionTestData.targets().build();

    assertThatThrownBy(() -> resolver.resolve(aggregate, "per_meal.brunch.calorie_target"))
        .isInstanceOf(InvalidFeedbackAdjustmentException.class);
  }

  @Test
  void resolve_blankTarget_throws() {
    NutritionTargets aggregate = NutritionTestData.targets().build();

    assertThatThrownBy(() -> resolver.resolve(aggregate, "   "))
        .isInstanceOf(InvalidFeedbackAdjustmentException.class);
  }
}
