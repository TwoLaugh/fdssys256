package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import com.example.mealprep.feedback.domain.entity.RoutingDecision;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pure-function evaluator of the three-way confidence fork per design/feedback-system.md
 * §Confidence handling lines 199-207 and lld/feedback.md line 738.
 *
 * <p>Boundaries (locked 2026-05-07):
 *
 * <ul>
 *   <li>{@code confidence >= 0.8} → {@link RoutingDecision#AUTO_ROUTED} (upper boundary inclusive)
 *   <li>{@code 0.5 <= confidence < 0.8} → {@link RoutingDecision#ROUTED_WITH_FLAG} (lower boundary
 *       inclusive)
 *   <li>{@code confidence < 0.5} → {@link RoutingDecision#CLARIFICATION_QUEUED}
 * </ul>
 *
 * <p>Mutually exclusive any-&lt;0.5 rule: if ANY classification dips below 0.5, the entire entry
 * pauses for clarification (LLD line 743).
 */
@Component
public class ConfidenceGate {

  private static final BigDecimal AUTO_THRESHOLD = new BigDecimal("0.800");
  private static final BigDecimal FLAG_THRESHOLD = new BigDecimal("0.500");

  /** Result of one gate evaluation. {@code classifications} is empty when input was empty. */
  public record GateResult(
      List<ScoredClassification> classifications, boolean anyBelowThreshold, boolean allEmpty) {}

  /** One {@link ClassificationOutput} with the gate-derived {@link RoutingDecision}. */
  public record ScoredClassification(
      ClassificationOutput classification, RoutingDecision decision) {}

  public GateResult evaluate(ClassificationResult result) {
    if (result == null) {
      throw new IllegalArgumentException("result must not be null");
    }
    List<ClassificationOutput> raw = result.classifications();
    if (raw == null || raw.isEmpty()) {
      return new GateResult(List.of(), false, true);
    }
    List<ScoredClassification> scored =
        raw.stream().map(c -> new ScoredClassification(c, decide(c.confidence()))).toList();
    boolean anyBelow =
        scored.stream().anyMatch(s -> s.decision() == RoutingDecision.CLARIFICATION_QUEUED);
    return new GateResult(scored, anyBelow, false);
  }

  private RoutingDecision decide(BigDecimal confidence) {
    if (confidence.compareTo(AUTO_THRESHOLD) >= 0) {
      return RoutingDecision.AUTO_ROUTED;
    }
    if (confidence.compareTo(FLAG_THRESHOLD) >= 0) {
      return RoutingDecision.ROUTED_WITH_FLAG;
    }
    return RoutingDecision.CLARIFICATION_QUEUED;
  }
}
