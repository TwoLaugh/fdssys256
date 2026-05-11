package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.domain.service.internal.DivergenceScoreCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage of the provisional jaccard-mean divergence formula. The canonical formula is
 * a pipeline-LLD concern; this test pins the 01d implementation so refactors stay observable.
 */
class DivergenceScoreCalculatorTest {

  private final DivergenceScoreCalculator calculator = new DivergenceScoreCalculator();

  @Test
  void bothNull_returnsZero() {
    assertThat(calculator.compute(null, null))
        .isEqualByComparingTo(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
  }

  @Test
  void oneNull_returnsOne() {
    CharacterFingerprintDto fp = fingerprint("Italian");
    assertThat(calculator.compute(fp, null)).isEqualByComparingTo(new BigDecimal("1.000"));
    assertThat(calculator.compute(null, fp)).isEqualByComparingTo(new BigDecimal("1.000"));
  }

  @Test
  void identicalFingerprints_returnsZero() {
    CharacterFingerprintDto fp = fingerprint("Italian");
    assertThat(calculator.compute(fp, fp)).isEqualByComparingTo(new BigDecimal("0.000"));
  }

  @Test
  void allBlankFingerprints_returnsZero() {
    CharacterFingerprintDto blank =
        new CharacterFingerprintDto(
            List.of(), List.of(), List.of(), List.of(), Complexity.MODERATE, null);
    assertThat(calculator.compute(blank, blank)).isEqualByComparingTo(new BigDecimal("0.000"));
  }

  @Test
  void completelyDisjoint_returnsOne() {
    CharacterFingerprintDto a =
        new CharacterFingerprintDto(
            List.of("egg"),
            List.of("fry"),
            List.of("crispy"),
            List.of("savoury"),
            Complexity.MINIMAL,
            "Italian");
    CharacterFingerprintDto b =
        new CharacterFingerprintDto(
            List.of("rice"),
            List.of("steam"),
            List.of("soft"),
            List.of("sweet"),
            Complexity.INVOLVED,
            "Thai");
    assertThat(calculator.compute(a, b)).isEqualByComparingTo(new BigDecimal("1.000"));
  }

  @Test
  void singleFieldDifference_returnsOneSixth() {
    CharacterFingerprintDto a =
        new CharacterFingerprintDto(
            List.of("pasta"), List.of(), List.of(), List.of(), Complexity.MODERATE, "Italian");
    CharacterFingerprintDto b =
        new CharacterFingerprintDto(
            List.of("pasta"), List.of(), List.of(), List.of(), Complexity.MODERATE, "Thai");
    // Only cuisineAnchor differs out of 6 components → 1/6 ≈ 0.167.
    assertThat(calculator.compute(a, b)).isEqualByComparingTo(new BigDecimal("0.167"));
  }

  @Test
  void partialOverlapOnDefiningIngredients_yieldsMiddleValue() {
    CharacterFingerprintDto a =
        new CharacterFingerprintDto(
            List.of("egg", "milk"),
            List.of(),
            List.of(),
            List.of(),
            Complexity.MODERATE,
            "Italian");
    CharacterFingerprintDto b =
        new CharacterFingerprintDto(
            List.of("egg", "flour"),
            List.of(),
            List.of(),
            List.of(),
            Complexity.MODERATE,
            "Italian");
    // {egg,milk} vs {egg,flour}: intersection {egg}=1, union={egg,milk,flour}=3 → jaccard distance
    // = 1 - 1/3 ≈ 0.667. Mean over 6 components: 0.667 / 6 ≈ 0.111.
    assertThat(calculator.compute(a, b)).isEqualByComparingTo(new BigDecimal("0.111"));
  }

  @Test
  void oneEmpty_oneNonEmpty_listIsMaxDistance() {
    CharacterFingerprintDto a =
        new CharacterFingerprintDto(
            List.of("egg"), List.of(), List.of(), List.of(), Complexity.MODERATE, null);
    CharacterFingerprintDto b =
        new CharacterFingerprintDto(
            List.of(), List.of(), List.of(), List.of(), Complexity.MODERATE, null);
    // Only definingIngredients differ (1 vs 0 → jaccard 1.0); 1/6 = 0.167.
    assertThat(calculator.compute(a, b)).isEqualByComparingTo(new BigDecimal("0.167"));
  }

  @Test
  void roundsToThreeDecimalPlaces() {
    CharacterFingerprintDto a =
        new CharacterFingerprintDto(
            List.of("a", "b", "c"),
            List.of(),
            List.of(),
            List.of(),
            Complexity.MODERATE,
            "Italian");
    CharacterFingerprintDto b =
        new CharacterFingerprintDto(
            List.of("d", "e", "f"),
            List.of(),
            List.of(),
            List.of(),
            Complexity.MODERATE,
            "Italian");
    // First list fully disjoint → 1.0; nothing else differs → mean = 1/6 = 0.1666... → 0.167.
    BigDecimal score = calculator.compute(a, b);
    assertThat(score.scale()).isEqualTo(3);
    assertThat(score).isEqualByComparingTo(new BigDecimal("0.167"));
  }

  private static CharacterFingerprintDto fingerprint(String cuisine) {
    return new CharacterFingerprintDto(
        List.of("egg", "flour"),
        List.of(),
        List.of(),
        List.of("savoury"),
        Complexity.MODERATE,
        cuisine);
  }
}
