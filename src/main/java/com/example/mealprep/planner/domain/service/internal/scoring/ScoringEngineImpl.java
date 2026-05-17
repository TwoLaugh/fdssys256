package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.config.PlannerProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Real composite {@link ScoringEngine} (planner-01e) — supersedes 01d's {@code StubScoringEngine}.
 * Composes the seven {@code SubScoreCalculator}s and the two multiplicative gates per LLD
 * §ScoringEngine:
 *
 * <pre>
 *   composite = (Σ weight_i × sub_score_i) × Π gate_i      gate_i ∈ {0, 1}
 * </pre>
 *
 * <p>If either gate returns {@code false} the composite is {@code 0} regardless of the weighted
 * sum. Each sub-score is validated to be in {@code [0, 1]} (boundary inclusive); an out-of-range
 * return is a calculator bug, not a recoverable condition, so it throws {@link
 * IllegalStateException} (worth user review — the rejected alternative was silent clamping).
 *
 * <p>No {@code @Profile}: this is the default bean. 01d's {@code StubScoringEngine} is moved to
 * {@code @Profile("scoring-stub")} so the real impl wins in the default AND {@code test} profiles;
 * tests that want the deterministic stub explicitly activate the {@code scoring-stub} profile.
 *
 * <p>Calculators are injected as a {@code List<SubScoreCalculator>} and indexed by {@code .name()},
 * so injection order is irrelevant. Pure function of {@code (plan, ctx)} — no DB, no time, no
 * randomness → byte-identical {@link ScoreResult} for identical input.
 */
@Component
class ScoringEngineImpl implements ScoringEngine {

  private final List<SubScoreCalculator> calculators;
  private final NutritionFloorGate nutritionFloorGate;
  private final VarietyGate varietyGate;
  private final PlannerProperties properties;

  ScoringEngineImpl(
      List<SubScoreCalculator> calculators,
      NutritionFloorGate nutritionFloorGate,
      VarietyGate varietyGate,
      PlannerProperties properties) {
    this.calculators = calculators;
    this.nutritionFloorGate = nutritionFloorGate;
    this.varietyGate = varietyGate;
    this.properties = properties;
  }

  @Override
  public ScoreResult score(CandidatePlan plan, PlanCompositionContext ctx) {
    Map<String, BigDecimal> raw = new HashMap<>();
    for (SubScoreCalculator c : calculators) {
      BigDecimal s = c.compute(plan, ctx);
      assertInRange(c.name(), s);
      raw.put(c.name(), s);
    }

    BigDecimal unweighted = weightedSum(raw);
    boolean floorPassed = nutritionFloorGate.passes(plan, ctx);
    boolean varietyPassed = varietyGate.passes(plan, ctx);
    BigDecimal gateFactor = (floorPassed && varietyPassed) ? BigDecimal.ONE : BigDecimal.ZERO;
    BigDecimal composite = unweighted.multiply(gateFactor).setScale(6, RoundingMode.HALF_UP);

    ScoreBreakdownDocument breakdown =
        new ScoreBreakdownDocument(
            raw.get("preference"),
            raw.get("nutrition"),
            raw.get("cost"),
            raw.get("variety"),
            raw.get("time"),
            raw.get("batch"),
            raw.get("provisions"),
            composite,
            floorPassed,
            varietyPassed,
            "v1-uniform");

    return new ScoreResult(composite, breakdown);
  }

  private BigDecimal weightedSum(Map<String, BigDecimal> raw) {
    PlannerProperties.ScoringWeights w = properties.weights();
    return safe(raw.get("preference"))
        .multiply(w.preference())
        .add(safe(raw.get("nutrition")).multiply(w.nutrition()))
        .add(safe(raw.get("cost")).multiply(w.cost()))
        .add(safe(raw.get("variety")).multiply(w.variety()))
        .add(safe(raw.get("time")).multiply(w.time()))
        .add(safe(raw.get("batch")).multiply(w.batch()))
        .add(safe(raw.get("provisions")).multiply(w.provisions()));
  }

  private static BigDecimal safe(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static void assertInRange(String name, BigDecimal value) {
    if (value == null
        || value.compareTo(BigDecimal.ZERO) < 0
        || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalStateException(
          "sub-score " + name + " returned " + value + ", expected [0, 1]");
    }
  }
}
