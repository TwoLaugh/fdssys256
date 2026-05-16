package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.example.mealprep.adaptation.domain.service.internal.ConfidenceFloorGate;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfidenceFloorGateTest {

  private final AdaptationConfig config =
      new AdaptationConfig(
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
  private final ConfidenceFloorGate gate = new ConfidenceFloorGate(config);

  @Test
  void passes_when_confidence_above_floor() {
    RecipeAdaptationResponse r = response(BigDecimal.valueOf(0.75));
    assertThat(gate.evaluate(r)).isEqualTo(ValidationResult.PASSED);
  }

  @Test
  void low_confidence_when_below_floor() {
    RecipeAdaptationResponse r = response(BigDecimal.valueOf(0.45));
    assertThat(gate.evaluate(r)).isEqualTo(ValidationResult.LOW_CONFIDENCE);
  }

  @Test
  void null_confidence_treated_as_low() {
    RecipeAdaptationResponse r = response(null);
    assertThat(gate.evaluate(r)).isEqualTo(ValidationResult.LOW_CONFIDENCE);
  }

  private static RecipeAdaptationResponse response(BigDecimal conf) {
    return new RecipeAdaptationResponse(
        0,
        AdaptationClassification.VERSION,
        "ok",
        "",
        conf,
        BigDecimal.valueOf(0.7),
        null,
        null,
        List.of());
  }
}
