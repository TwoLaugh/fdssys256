package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import com.example.mealprep.feedback.domain.service.internal.ConfidenceGate;
import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfidenceGate}. Boundary inclusivity locked per LLD line 738 / HLD
 * §Confidence handling lines 202-205.
 */
class ConfidenceGateTest {

  private final ConfidenceGate gate = new ConfidenceGate();

  @Test
  void emptyClassifications_yieldsAllEmpty() {
    ClassificationResult result = new ClassificationResult(List.of(), new BigDecimal("0.50"), null);
    ConfidenceGate.GateResult g = gate.evaluate(result);
    assertThat(g.allEmpty()).isTrue();
    assertThat(g.anyBelowThreshold()).isFalse();
    assertThat(g.classifications()).isEmpty();
  }

  @Test
  void nullResult_throws() {
    assertThatThrownBy(() -> gate.evaluate(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void confidence_080_isAutoRouted_upperBoundaryInclusive() {
    ConfidenceGate.GateResult g = gateOf("0.800");
    assertThat(g.classifications().get(0).decision()).isEqualTo(RoutingDecision.AUTO_ROUTED);
    assertThat(g.anyBelowThreshold()).isFalse();
  }

  @Test
  void confidence_above080_isAutoRouted() {
    assertThat(gateOf("0.95").classifications().get(0).decision())
        .isEqualTo(RoutingDecision.AUTO_ROUTED);
    assertThat(gateOf("1.000").classifications().get(0).decision())
        .isEqualTo(RoutingDecision.AUTO_ROUTED);
  }

  @Test
  void confidence_just_below080_isRoutedWithFlag() {
    assertThat(gateOf("0.799").classifications().get(0).decision())
        .isEqualTo(RoutingDecision.ROUTED_WITH_FLAG);
  }

  @Test
  void confidence_050_isRoutedWithFlag_lowerBoundaryInclusive() {
    ConfidenceGate.GateResult g = gateOf("0.500");
    assertThat(g.classifications().get(0).decision()).isEqualTo(RoutingDecision.ROUTED_WITH_FLAG);
    assertThat(g.anyBelowThreshold()).isFalse();
  }

  @Test
  void confidence_just_below050_isClarificationQueued() {
    ConfidenceGate.GateResult g = gateOf("0.499");
    assertThat(g.classifications().get(0).decision())
        .isEqualTo(RoutingDecision.CLARIFICATION_QUEUED);
    assertThat(g.anyBelowThreshold()).isTrue();
  }

  @Test
  void confidence_000_isClarificationQueued() {
    assertThat(gateOf("0.000").classifications().get(0).decision())
        .isEqualTo(RoutingDecision.CLARIFICATION_QUEUED);
  }

  @Test
  void mixed_anyBelow050_marksAnyBelowThresholdTrue() {
    ClassificationResult result =
        new ClassificationResult(
            List.of(
                output(Destination.RECIPE, "0.95"),
                output(Destination.PREFERENCE, "0.65"),
                output(Destination.NUTRITION, "0.40")),
            new BigDecimal("0.70"),
            null);
    ConfidenceGate.GateResult g = gate.evaluate(result);
    assertThat(g.anyBelowThreshold()).isTrue();
    assertThat(g.allEmpty()).isFalse();
    assertThat(g.classifications().get(0).decision()).isEqualTo(RoutingDecision.AUTO_ROUTED);
    assertThat(g.classifications().get(1).decision()).isEqualTo(RoutingDecision.ROUTED_WITH_FLAG);
    assertThat(g.classifications().get(2).decision())
        .isEqualTo(RoutingDecision.CLARIFICATION_QUEUED);
  }

  @Test
  void mixed_allAbove050_anyBelowThresholdFalse() {
    ClassificationResult result =
        new ClassificationResult(
            List.of(output(Destination.RECIPE, "0.95"), output(Destination.PREFERENCE, "0.55")),
            new BigDecimal("0.75"),
            null);
    ConfidenceGate.GateResult g = gate.evaluate(result);
    assertThat(g.anyBelowThreshold()).isFalse();
    assertThat(g.allEmpty()).isFalse();
    assertThat(g.classifications()).hasSize(2);
  }

  @Test
  void pureFunction_sameInputTwice_sameOutput() {
    ClassificationResult result =
        new ClassificationResult(
            List.of(output(Destination.RECIPE, "0.50")), new BigDecimal("0.50"), null);
    ConfidenceGate.GateResult a = gate.evaluate(result);
    ConfidenceGate.GateResult b = gate.evaluate(result);
    assertThat(a.classifications().get(0).decision())
        .isEqualTo(b.classifications().get(0).decision());
    assertThat(a.anyBelowThreshold()).isEqualTo(b.anyBelowThreshold());
    assertThat(a.allEmpty()).isEqualTo(b.allEmpty());
  }

  // ---------------- helpers ----------------

  private ConfidenceGate.GateResult gateOf(String confidence) {
    return gate.evaluate(
        new ClassificationResult(
            List.of(output(Destination.RECIPE, confidence)), new BigDecimal(confidence), null));
  }

  private static ClassificationOutput output(Destination dest, String confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("note", dest.name());
    return new ClassificationOutput(
        dest, new BigDecimal(confidence), "snippet for " + dest, payload);
  }
}
