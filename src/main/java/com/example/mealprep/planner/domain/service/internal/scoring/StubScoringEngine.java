package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-only {@link ScoringEngine} that returns deterministic scores keyed by the sorted list of
 * assigned {@code recipeId}s on the {@link CandidatePlan}. Same plan → byte-identical {@link
 * ScoreResult}; different recipe sets → different composites. Used by 01d's beam-search tests to
 * verify ordering / top-N / pruning invariants without depending on the real scoring math (which
 * lands in planner-01e).
 *
 * <p><b>planner-01e adjustment</b>: the gate moved from {@code @Profile("test")} to
 * {@code @Profile("scoring-stub")}. 01e's real {@link ScoringEngineImpl} is {@code @Component} with
 * no profile, so it wins in the default AND {@code test} profiles (the latter is what
 * {@code @SpringBootTest} activates by default). Beam-search / scoring tests that want this
 * deterministic stub explicitly add the {@code scoring-stub} profile. Because the real impl is also
 * profile-less (always registered), under {@code scoring-stub} BOTH beans match {@code
 * ScoringEngine}; {@code @Primary} here disambiguates so the stub wins exactly when its profile is
 * active. Outside {@code scoring-stub} only the real impl exists, so {@code @Primary} is inert
 * there.
 */
@Component
@Profile("scoring-stub")
@Primary
class StubScoringEngine implements ScoringEngine {

  private static final BigDecimal SCALE_DOWN =
      BigDecimal.ONE.scaleByPowerOfTen(-9); // composite stays in [0, ~1] for any reasonable plan

  @Override
  public ScoreResult score(CandidatePlan plan, PlanCompositionContext context) {
    long hash = deterministicHash(plan.assignments());
    BigDecimal composite =
        BigDecimal.valueOf(Math.abs(hash))
            .multiply(SCALE_DOWN)
            .round(new MathContext(9))
            .setScale(9, RoundingMode.HALF_UP);
    ScoreBreakdownDocument breakdown =
        new ScoreBreakdownDocument(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            composite,
            true,
            true,
            "v1-uniform-stub");
    return new ScoreResult(composite, breakdown);
  }

  /**
   * Stable, order-independent hash over the assigned recipe ids — sort by recipe id first so
   * different orderings of the same set produce identical scores. Deterministic across JVM runs (no
   * {@code System.identityHashCode} / no time).
   */
  private static long deterministicHash(List<SlotAssignment> assignments) {
    long acc = 1469598103934665603L; // FNV-1a 64-bit offset basis
    long prime = 1099511628211L;
    List<UUID> ids =
        assignments.stream()
            .map(SlotAssignment::recipeId)
            .sorted(Comparator.naturalOrder())
            .toList();
    for (UUID id : ids) {
      long msb = id.getMostSignificantBits();
      long lsb = id.getLeastSignificantBits();
      for (int i = 0; i < 8; i++) {
        acc = (acc ^ ((msb >>> (i * 8)) & 0xFF)) * prime;
      }
      for (int i = 0; i < 8; i++) {
        acc = (acc ^ ((lsb >>> (i * 8)) & 0xFF)) * prime;
      }
    }
    return acc;
  }
}
