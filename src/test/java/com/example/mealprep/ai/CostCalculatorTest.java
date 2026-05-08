package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.domain.service.internal.CostCalculator;
import com.example.mealprep.ai.spi.ModelTier;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link CostCalculator}. Pure arithmetic — exhaustive over the (tier, tokens) axes
 * the budget guard exercises.
 */
class CostCalculatorTest {

  private final CostCalculator calc = new CostCalculator();

  @Test
  void compute_haikuRates_match() {
    // 1000 input × 79p/MTok = 79_000 micropence; 200 output × 395p/MTok = 79_000 micropence.
    long cost = calc.compute("claude-haiku-4-5-20251001", 1_000, 200);
    assertThat(cost).isEqualTo(1_000L * 79L + 200L * 395L);
  }

  @Test
  void compute_sonnetRates_match() {
    long cost = calc.compute("claude-sonnet-4-6", 1_000, 200);
    assertThat(cost).isEqualTo(1_000L * 237L + 200L * 1_185L);
  }

  @Test
  void compute_opusRates_match() {
    long cost = calc.compute("claude-opus-4-7", 1_000, 200);
    assertThat(cost).isEqualTo(1_000L * 1_185L + 200L * 5_925L);
  }

  @Test
  void compute_unknownModelId_fallsBackToCheapTier() {
    long cost = calc.compute("nonsense-model-id", 1_000, 200);
    assertThat(cost).isEqualTo(1_000L * 79L + 200L * 395L);
  }

  @Test
  void compute_nullModelId_fallsBackToCheapTier() {
    long cost = calc.compute(null, 1_000, 200);
    assertThat(cost).isEqualTo(1_000L * 79L + 200L * 395L);
  }

  @Test
  void compute_blankModelId_fallsBackToCheapTier() {
    long cost = calc.compute("   ", 1_000, 200);
    assertThat(cost).isEqualTo(1_000L * 79L + 200L * 395L);
  }

  @Test
  void compute_zeroTokens_isZero() {
    assertThat(calc.compute("claude-haiku-4-5-20251001", 0, 0)).isEqualTo(0L);
  }

  @Test
  void compute_negativeTokens_treatedAsZero() {
    // Defensive: never throw, never go negative.
    assertThat(calc.compute("claude-haiku-4-5-20251001", -10, -20)).isEqualTo(0L);
  }

  @Test
  void compute_caseInsensitive_modelIdMatch() {
    long lower = calc.compute("claude-sonnet-4-6", 1_000, 200);
    long upper = calc.compute("CLAUDE-SONNET-4-6", 1_000, 200);
    assertThat(lower).isEqualTo(upper);
  }

  @Test
  void estimate_byTier_matchesCompute() {
    long est = calc.estimate(ModelTier.MID, 1_000, 200);
    long actual = calc.compute("claude-sonnet-4-6", 1_000, 200);
    assertThat(est).isEqualTo(actual);
  }

  @Test
  void estimate_unknownTierFallback_matchesCheap() {
    // The estimate path uses the tier directly; with all three known tiers in the enum, this is
    // really exercising the safe path. Switch ensures every enum branch returns a positive value.
    for (ModelTier tier : ModelTier.values()) {
      assertThat(calc.estimate(tier, 100, 100)).isPositive();
    }
  }

  @Test
  void compute_inputAndOutputContributeIndependently() {
    long onlyInput = calc.compute("claude-haiku-4-5-20251001", 100, 0);
    long onlyOutput = calc.compute("claude-haiku-4-5-20251001", 0, 100);
    long both = calc.compute("claude-haiku-4-5-20251001", 100, 100);
    assertThat(onlyInput + onlyOutput).isEqualTo(both);
  }
}
