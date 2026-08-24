package com.example.mealprep.planner.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link PortionScaler} — the pure portion-scaling arithmetic. Exercised directly
 * (rather than only through {@code DailyMacroAggregator}) because the clamp / step-rounding / enum
 * normalisation is the bug-prone logic and is fixture-free here; the aggregator's "multiply each
 * nutrient by the factor" wiring is covered end-to-end by the live generation verify. All returned
 * factors are multiples of 0.25 (exactly representable in {@code double}), so exact equality holds.
 */
class PortionScalerTest {

  @Test
  void exact_multiple_scales_cleanly() {
    // 900 / 450 = 2.0 exactly → no rounding.
    assertThat(PortionScaler.factor(450, 900)).isEqualTo(2.0);
  }

  @Test
  void non_multiple_rounds_to_quarter_step() {
    // 1000 / 440 = 2.2727… → nearest 0.25 is 2.25.
    assertThat(PortionScaler.factor(440, 1000)).isEqualTo(2.25);
    // 1000 / 480 = 2.0833… → rounds down to 2.0.
    assertThat(PortionScaler.factor(480, 1000)).isEqualTo(2.0);
    // a clean half-step: 1000 / 400 = 2.5 → stays 2.5.
    assertThat(PortionScaler.factor(400, 1000)).isEqualTo(2.5);
  }

  @Test
  void clamps_to_max_when_recipe_far_below_target() {
    // 1100 / 200 = 5.5 → capped at 3.0 (leave the meal honestly short rather than fabricate food).
    assertThat(PortionScaler.factor(200, 1100)).isEqualTo(PortionScaler.MAX_FACTOR);
    assertThat(PortionScaler.MAX_FACTOR).isEqualTo(3.0);
  }

  @Test
  void clamps_to_min_when_recipe_far_above_target() {
    // 1000 / 5000 = 0.2 → floored at 0.5 (a slot is at least half a serving).
    assertThat(PortionScaler.factor(5000, 1000)).isEqualTo(PortionScaler.MIN_FACTOR);
    assertThat(PortionScaler.MIN_FACTOR).isEqualTo(0.5);
  }

  @Test
  void falls_back_to_one_when_unsizable() {
    assertThat(PortionScaler.factor(0, 1000)).isCloseTo(1.0, within(1e-9)); // no calories
    assertThat(PortionScaler.factor(-50, 1000)).isCloseTo(1.0, within(1e-9)); // negative kcal
    assertThat(PortionScaler.factor(450, null)).isCloseTo(1.0, within(1e-9)); // no target for kind
    assertThat(PortionScaler.factor(450, 0)).isCloseTo(1.0, within(1e-9)); // zero target
    assertThat(PortionScaler.factor(450, -100)).isCloseTo(1.0, within(1e-9)); // negative target
  }

  @Test
  void normalise_maps_snack_kind_to_snacks_meal_slot() {
    // SlotKind.SNACK vs MealSlot.SNACKS must resolve to the same key.
    assertThat(PortionScaler.normaliseKind("SNACK")).isEqualTo("SNACK");
    assertThat(PortionScaler.normaliseKind("SNACKS")).isEqualTo("SNACK");
    assertThat(PortionScaler.normaliseKind("snacks")).isEqualTo("SNACK");
  }

  @Test
  void normalise_leaves_other_kinds_untouched() {
    assertThat(PortionScaler.normaliseKind("BREAKFAST")).isEqualTo("BREAKFAST");
    assertThat(PortionScaler.normaliseKind("lunch")).isEqualTo("LUNCH");
    assertThat(PortionScaler.normaliseKind("Dinner")).isEqualTo("DINNER");
    assertThat(PortionScaler.normaliseKind(null)).isEqualTo("");
  }
}
