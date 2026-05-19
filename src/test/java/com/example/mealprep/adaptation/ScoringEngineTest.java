package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.AdaptationRollupDto;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.service.internal.ScoringEngine;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ScoringEngine}. */
class ScoringEngineTest {

  private final AdaptationConfig config = configWithDefaults();
  private final ScoringEngine engine = new ScoringEngine(config);

  @Test
  void top_n_sorts_by_taste_then_abs_macro_delta_kcal() {
    AdaptationCandidateDto a = candidate(0, BigDecimal.valueOf(0.4), BigDecimal.valueOf(100));
    AdaptationCandidateDto b = candidate(1, BigDecimal.valueOf(0.9), BigDecimal.valueOf(50));
    AdaptationCandidateDto c = candidate(2, BigDecimal.valueOf(0.9), BigDecimal.valueOf(10));
    List<AdaptationCandidateDto> out = engine.selectTopN(List.of(a, b, c));
    assertThat(out).hasSize(3);
    // c first (same taste as b, smaller |kcal|), then b, then a.
    assertThat(out.get(0).rollup().macroDeltaKcal()).isEqualByComparingTo("10");
    assertThat(out.get(1).rollup().macroDeltaKcal()).isEqualByComparingTo("50");
    assertThat(out.get(2).rollup().macroDeltaKcal()).isEqualByComparingTo("100");
    // Indices are renumbered.
    assertThat(out.get(0).index()).isZero();
    assertThat(out.get(2).index()).isEqualTo(2);
  }

  @Test
  void empty_input_returns_empty_list() {
    assertThat(engine.selectTopN(List.of())).isEmpty();
  }

  @Test
  void auto_skip_when_top_score_more_than_2x_runner_up() {
    AdaptationCandidateDto top = candidate(0, BigDecimal.valueOf(0.9), BigDecimal.ZERO);
    AdaptationCandidateDto runner = candidate(1, BigDecimal.valueOf(0.4), BigDecimal.ZERO);
    boolean skip = engine.shouldAutoSkipStageC(List.of(top, runner));
    assertThat(skip).isTrue();
  }

  @Test
  void auto_skip_false_when_runner_up_is_close() {
    AdaptationCandidateDto top = candidate(0, BigDecimal.valueOf(0.9), BigDecimal.ZERO);
    AdaptationCandidateDto runner = candidate(1, BigDecimal.valueOf(0.8), BigDecimal.ZERO);
    assertThat(engine.shouldAutoSkipStageC(List.of(top, runner))).isFalse();
  }

  @Test
  void single_candidate_triggers_auto_skip() {
    AdaptationCandidateDto only = candidate(0, BigDecimal.valueOf(0.5), BigDecimal.ZERO);
    assertThat(engine.shouldAutoSkipStageC(List.of(only))).isTrue();
  }

  @Test
  void auto_skip_false_for_empty_or_null_topN() {
    assertThat(engine.shouldAutoSkipStageC(List.of())).isFalse();
    assertThat(engine.shouldAutoSkipStageC(null)).isFalse();
  }

  @Test
  void auto_skip_true_when_runner_up_score_is_exactly_zero() {
    // runner.signum() <= 0 boundary: signum == 0 must take the trivial-win branch.
    // A `< 0` mutant would fall through to the ratio path and divide-by-zero / mis-decide.
    AdaptationCandidateDto top = candidate(0, BigDecimal.valueOf(0.9), BigDecimal.ZERO);
    AdaptationCandidateDto runner = candidate(1, BigDecimal.ZERO, BigDecimal.ZERO);
    assertThat(engine.shouldAutoSkipStageC(List.of(top, runner))).isTrue();
  }

  @Test
  void auto_skip_true_when_runner_up_score_is_negative() {
    AdaptationCandidateDto top = candidate(0, BigDecimal.valueOf(0.9), BigDecimal.ZERO);
    AdaptationCandidateDto runner = candidate(1, BigDecimal.valueOf(-0.2), BigDecimal.ZERO);
    assertThat(engine.shouldAutoSkipStageC(List.of(top, runner))).isTrue();
  }

  @Test
  void auto_skip_false_when_ratio_exactly_equals_threshold() {
    // autoSkipTopRatio == 2.00. top/runner == 0.8/0.4 == 2.00 exactly.
    // Rule is STRICT `> ratio`, so equality must NOT skip. A `>=` boundary mutant flips this.
    AdaptationCandidateDto top = candidate(0, new BigDecimal("0.80"), BigDecimal.ZERO);
    AdaptationCandidateDto runner = candidate(1, new BigDecimal("0.40"), BigDecimal.ZERO);
    assertThat(engine.shouldAutoSkipStageC(List.of(top, runner))).isFalse();
  }

  @Test
  void auto_skip_true_when_ratio_just_over_threshold() {
    AdaptationCandidateDto top = candidate(0, new BigDecimal("0.81"), BigDecimal.ZERO);
    AdaptationCandidateDto runner = candidate(1, new BigDecimal("0.40"), BigDecimal.ZERO);
    assertThat(engine.shouldAutoSkipStageC(List.of(top, runner))).isTrue();
  }

  @Test
  void selectTopN_caps_at_configured_top_n() {
    // config.candidateTopN() == 5; supply 7 -> exactly 5 returned, indices 0..4.
    List<AdaptationCandidateDto> many =
        java.util.stream.IntStream.range(0, 7)
            .mapToObj(i -> candidate(i, BigDecimal.valueOf(0.9 - i * 0.05), BigDecimal.valueOf(i)))
            .toList();
    List<AdaptationCandidateDto> out = engine.selectTopN(many);
    assertThat(out).hasSize(5);
    assertThat(out).extracting(AdaptationCandidateDto::index).containsExactly(0, 1, 2, 3, 4);
  }

  @Test
  void selectTopN_null_input_returns_empty() {
    assertThat(engine.selectTopN(null)).isEmpty();
  }

  private static AdaptationCandidateDto candidate(
      int index, BigDecimal taste, BigDecimal kcalDelta) {
    AdaptationRollupDto rollup =
        new AdaptationRollupDto(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            kcalDelta,
            Map.of(),
            BigDecimal.ZERO,
            0,
            0,
            taste,
            Set.of(),
            List.of());
    return new AdaptationCandidateDto(
        index,
        AdaptationClassification.VERSION,
        JsonNodeFactory.instance.objectNode(),
        rollup,
        "test",
        "",
        BigDecimal.valueOf(0.8),
        BigDecimal.valueOf(0.8),
        List.of());
  }

  private static AdaptationConfig configWithDefaults() {
    return new AdaptationConfig(
        5,
        10_000,
        8_000,
        12_000,
        3,
        3,
        14,
        new BigDecimal("0.50"),
        new BigDecimal("2.00"),
        null,
        30,
        "0 0 4 * * *",
        "0 30 4 * * *");
  }
}
