package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link PreferenceSubScore}. The embedding / taste-vector contracts are not yet
 * surfaced on this branch, so per ticket items 11 / 59 the calculator returns {@code 0.5} neutral
 * universally. Structure-only assertions (no magic numbers beyond the LOCKED neutral fallback).
 */
class PreferenceSubScoreTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final PreferenceSubScore calc = new PreferenceSubScore();

  @Test
  void name_is_preference() {
    assertThat(calc.name()).isEqualTo("preference");
  }

  @Test
  void returns_neutral_for_empty_plan() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, List.of()), ctx))
        .isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  void returns_neutral_for_populated_plan_until_embeddings_ship() {
    UUID id = UUID.randomUUID();
    var recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of());
    var plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.5"));
  }
}
