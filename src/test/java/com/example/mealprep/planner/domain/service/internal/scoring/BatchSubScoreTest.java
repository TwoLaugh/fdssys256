package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link BatchSubScore}. On this branch no {@code batchCookSessionId} field exists,
 * so every slot falls into the single null/"no-batch" bucket → score is deterministically {@code 1
 * - 1/N} (null-as-single-bucket convention, ticket item 32).
 */
class BatchSubScoreTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final BatchSubScore calc = new BatchSubScore();

  @Test
  void name_is_batch() {
    assertThat(calc.name()).isEqualTo("batch");
  }

  @Test
  void empty_plan_is_vacuous_one() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, List.of()), ctx))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void all_null_session_collapses_to_single_bucket_score_one_minus_one_over_n() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    List<SlotAssignment> as = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      as.add(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, i, 2));
    }
    BigDecimal expected =
        BigDecimal.ONE.subtract(
            BigDecimal.ONE.divide(BigDecimal.valueOf(21), 6, RoundingMode.HALF_UP));
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, as), ctx))
        .isEqualByComparingTo(expected);
  }

  @Test
  void single_slot_scores_zero() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    List<SlotAssignment> as =
        List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2));
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, as), ctx))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }
}
