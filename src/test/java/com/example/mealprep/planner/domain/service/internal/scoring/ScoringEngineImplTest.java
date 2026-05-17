package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit test for {@link ScoringEngineImpl}. Gates are mocked; the seven real calculators are wired
 * so structure / ordering / gate-collapse / range-validation are asserted (no magic-number
 * assertions on the LOCKED-but-data-dependent composite beyond the deterministic neutral parts).
 */
class ScoringEngineImplTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private final NutritionFloorGate floorGate = Mockito.mock(NutritionFloorGate.class);
  private final VarietyGate varietyGate = Mockito.mock(VarietyGate.class);

  private ScoringEngineImpl engine(List<SubScoreCalculator> calcs) {
    return new ScoringEngineImpl(calcs, floorGate, varietyGate, PlanTestData.scoringProperties());
  }

  private List<SubScoreCalculator> realCalculators() {
    var props = PlanTestData.scoringProperties();
    return List.of(
        new PreferenceSubScore(),
        new NutritionSubScore(),
        new CostSubScore(props),
        new VarietySubScore(props),
        new TimeSubScore(),
        new BatchSubScore(),
        new ProvisionsSubScore(props));
  }

  private CandidatePlan plan() {
    UUID id = UUID.randomUUID();
    return PlanTestData.candidatePlan(
        WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
  }

  private PlanCompositionContext ctx() {
    return PlanTestData.minimalContext(List.of(), List.of());
  }

  @Test
  void both_gates_pass_composite_is_positive_weighted_sum() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    when(varietyGate.passes(any(), any())).thenReturn(true);
    ScoreResult result = engine(realCalculators()).score(plan(), ctx());
    assertThat(result.composite()).isGreaterThan(BigDecimal.ZERO);
    assertThat(result.breakdown().weightSchemeVersion()).isEqualTo("v1-uniform");
    assertThat(result.breakdown().nutritionFloorGatePassed()).isTrue();
    assertThat(result.breakdown().varietyGatePassed()).isTrue();
  }

  @Test
  void nutrition_floor_gate_fail_collapses_composite_to_zero() {
    when(floorGate.passes(any(), any())).thenReturn(false);
    when(varietyGate.passes(any(), any())).thenReturn(true);
    ScoreResult result = engine(realCalculators()).score(plan(), ctx());
    assertThat(result.composite()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.breakdown().nutritionFloorGatePassed()).isFalse();
  }

  @Test
  void variety_gate_fail_collapses_composite_to_zero() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    when(varietyGate.passes(any(), any())).thenReturn(false);
    assertThat(engine(realCalculators()).score(plan(), ctx()).composite())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void both_gates_fail_composite_zero() {
    when(floorGate.passes(any(), any())).thenReturn(false);
    when(varietyGate.passes(any(), any())).thenReturn(false);
    assertThat(engine(realCalculators()).score(plan(), ctx()).composite())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void sub_score_above_one_throws_with_name() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    when(varietyGate.passes(any(), any())).thenReturn(true);
    SubScoreCalculator rogue = stub("preference", new BigDecimal("1.1"));
    assertThatThrownBy(() -> engine(withRogue(rogue)).score(plan(), ctx()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("preference")
        .hasMessageContaining("1.1");
  }

  @Test
  void sub_score_below_zero_throws() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    when(varietyGate.passes(any(), any())).thenReturn(true);
    SubScoreCalculator rogue = stub("cost", new BigDecimal("-0.1"));
    assertThatThrownBy(() -> engine(withRogue(rogue)).score(plan(), ctx()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cost");
  }

  @Test
  void boundary_values_zero_and_one_accepted() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    when(varietyGate.passes(any(), any())).thenReturn(true);
    List<SubScoreCalculator> calcs =
        List.of(
            stub("preference", BigDecimal.ZERO),
            stub("nutrition", BigDecimal.ONE),
            stub("cost", BigDecimal.ZERO),
            stub("variety", BigDecimal.ONE),
            stub("time", BigDecimal.ZERO),
            stub("batch", BigDecimal.ONE),
            stub("provisions", BigDecimal.ZERO));
    ScoreResult r = engine(calcs).score(plan(), ctx());
    // weighted sum = 0.143*(0+1+0+1+0+1+0) = 0.429
    assertThat(r.composite()).isEqualByComparingTo(new BigDecimal("0.429000"));
  }

  @Test
  void deterministic_same_input_same_output() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    when(varietyGate.passes(any(), any())).thenReturn(true);
    CandidatePlan p = plan();
    PlanCompositionContext c = ctx();
    ScoringEngineImpl e = engine(realCalculators());
    assertThat(e.score(p, c).composite()).isEqualByComparingTo(e.score(p, c).composite());
  }

  @Test
  void engine_is_order_independent_indexes_by_name() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    when(varietyGate.passes(any(), any())).thenReturn(true);
    List<SubScoreCalculator> calcs =
        List.of(
            stub("provisions", new BigDecimal("0.5")),
            stub("time", new BigDecimal("0.5")),
            stub("batch", new BigDecimal("0.5")),
            stub("variety", new BigDecimal("0.5")),
            stub("cost", new BigDecimal("0.5")),
            stub("nutrition", new BigDecimal("0.5")),
            stub("preference", new BigDecimal("0.5")));
    ScoreResult r = engine(calcs).score(plan(), ctx());
    // 7 × 0.143 × 0.5 = 0.5005
    assertThat(r.composite()).isEqualByComparingTo(new BigDecimal("0.500500"));
  }

  private List<SubScoreCalculator> withRogue(SubScoreCalculator rogue) {
    return List.of(
        rogue,
        stub("nutrition", new BigDecimal("0.5")),
        stub("cost", new BigDecimal("0.5")),
        stub("variety", new BigDecimal("0.5")),
        stub("time", new BigDecimal("0.5")),
        stub("batch", new BigDecimal("0.5")),
        stub("provisions", new BigDecimal("0.5")));
  }

  private static SubScoreCalculator stub(String name, BigDecimal value) {
    return new SubScoreCalculator() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
        return value;
      }
    };
  }
}
