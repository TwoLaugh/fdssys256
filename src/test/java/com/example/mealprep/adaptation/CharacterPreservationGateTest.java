package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.service.internal.CharacterPreservationGate;
import com.example.mealprep.adaptation.exception.AdaptationCharacterBreakException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterPreservationGateTest {

  private final CharacterPreservationGate gate = new CharacterPreservationGate();

  @Test
  void passes_above_threshold() {
    RecipeAdaptationResponse r =
        response(
            BigDecimal.valueOf(0.7), AdaptationClassification.VERSION, BigDecimal.valueOf(0.9));
    assertThat(gate.evaluateAndForceBranch(r)).isFalse();
  }

  @Test
  void rejects_below_threshold_when_not_branch() {
    RecipeAdaptationResponse r =
        response(
            BigDecimal.valueOf(0.55), AdaptationClassification.VERSION, BigDecimal.valueOf(0.9));
    assertThatThrownBy(() -> gate.evaluateAndForceBranch(r))
        .isInstanceOf(AdaptationCharacterBreakException.class);
  }

  @Test
  void high_coherence_branch_continues_below_threshold() {
    RecipeAdaptationResponse r =
        response(
            BigDecimal.valueOf(0.55), AdaptationClassification.BRANCH, BigDecimal.valueOf(0.8));
    assertThat(gate.evaluateAndForceBranch(r)).isTrue();
  }

  @Test
  void low_coherence_branch_below_threshold_still_rejects() {
    RecipeAdaptationResponse r =
        response(
            BigDecimal.valueOf(0.55), AdaptationClassification.BRANCH, BigDecimal.valueOf(0.6));
    assertThatThrownBy(() -> gate.evaluateAndForceBranch(r))
        .isInstanceOf(AdaptationCharacterBreakException.class);
  }

  @Test
  void score_exactly_at_threshold_passes_cleanly() {
    // score.compareTo(0.6) >= 0 boundary: 0.60 is NOT a break (>= is inclusive).
    // A `> 0` mutant would reject the exactly-0.6 case.
    RecipeAdaptationResponse r =
        response(new BigDecimal("0.60"), AdaptationClassification.VERSION, BigDecimal.valueOf(0.9));
    assertThat(gate.evaluateAndForceBranch(r)).isFalse();
  }

  @Test
  void branch_with_coherence_exactly_at_min_continues() {
    // confidence.compareTo(0.7) >= 0 boundary, below-character BRANCH path: exactly 0.7
    // must take the escape hatch. A `> 0` mutant would reject here.
    RecipeAdaptationResponse r =
        response(new BigDecimal("0.55"), AdaptationClassification.BRANCH, new BigDecimal("0.70"));
    assertThat(gate.evaluateAndForceBranch(r)).isTrue();
  }

  @Test
  void null_response_throws_character_break() {
    assertThatThrownBy(() -> gate.evaluateAndForceBranch(null))
        .isInstanceOf(AdaptationCharacterBreakException.class);
  }

  @Test
  void null_character_score_throws_character_break() {
    RecipeAdaptationResponse r =
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.VERSION,
            "ok",
            "",
            BigDecimal.valueOf(0.9),
            null,
            null,
            null,
            List.of());
    assertThatThrownBy(() -> gate.evaluateAndForceBranch(r))
        .isInstanceOf(AdaptationCharacterBreakException.class);
  }

  @Test
  void below_threshold_branch_with_null_confidence_rejects() {
    RecipeAdaptationResponse r =
        response(new BigDecimal("0.55"), AdaptationClassification.BRANCH, null);
    assertThatThrownBy(() -> gate.evaluateAndForceBranch(r))
        .isInstanceOf(AdaptationCharacterBreakException.class);
  }

  private static RecipeAdaptationResponse response(
      BigDecimal characterScore, AdaptationClassification cls, BigDecimal confidence) {
    return new RecipeAdaptationResponse(
        0, cls, "ok", "", confidence, characterScore, null, null, List.of());
  }
}
