package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit test for {@link VarietySubScore} — LOCKED target-based distinct-count formula. */
class VarietySubScoreTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final VarietySubScore calc = new VarietySubScore(PlanTestData.scoringProperties());

  @Test
  void name_is_variety() {
    assertThat(calc.name()).isEqualTo("variety");
  }

  @Test
  void full_target_diversity_scores_one() {
    // 5 cuisines, 4 proteins, 3 methods across 5 slots → each dimension >= target → 1.0
    String[][] specs = {
      {"Thai", "tofu", "stir-fry"},
      {"Italian", "beef", "bake"},
      {"Indian", "chicken", "fry"},
      {"Mexican", "pork", "stir-fry"},
      {"French", "tofu", "bake"}
    };
    List<RecipeDto> pool = new ArrayList<>();
    List<SlotAssignment> as = new ArrayList<>();
    for (int i = 0; i < specs.length; i++) {
      UUID id = UUID.randomUUID();
      pool.add(PlanTestData.scoredRecipe(id, 20, specs[i][0], specs[i][1], specs[i][2], List.of()));
      as.add(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, i, 2));
    }
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), pool);
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, as), ctx))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void boring_week_one_each_scores_mean_of_partials() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "stir-fry", List.of());
    List<SlotAssignment> as =
        List.of(
            PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 1, 2));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));
    // distinct: cuisine 1/5, protein 1/4, method 1/3 → mean
    BigDecimal expected =
        new BigDecimal("0.2")
            .add(new BigDecimal("0.25"))
            .add(BigDecimal.ONE.divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP))
            .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, as), ctx))
        .isEqualByComparingTo(expected);
  }

  @Test
  void all_null_cuisine_contributes_zero_distinct() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, null, null, null, List.of());
    List<SlotAssignment> as = List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));
    // all dimensions 0 distinct → mean(0,0,0) = 0
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, as), ctx))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void tunable_target_override_changes_score() {
    // cuisine target lowered to 2; a plan with 2 distinct cuisines now scores 1.0 on that axis
    PlannerProperties props =
        new PlannerProperties(
            java.time.DayOfWeek.MONDAY,
            20,
            5,
            3,
            50,
            new BigDecimal("1.5"),
            java.time.Duration.ofSeconds(30),
            PlanTestData.uniformWeights(),
            new PlannerProperties.ScoringTuning(
                new PlannerProperties.ScoringTuning.VarietyTargets(2, 2, 2, 2),
                PlanTestData.defaultTuning().provisions(),
                PlanTestData.defaultTuning().cost()),
            java.time.Duration.ofSeconds(20),
            3);
    VarietySubScore tuned = new VarietySubScore(props);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    RecipeDto ra = PlanTestData.scoredRecipe(a, 20, "Thai", "tofu", "fry", List.of());
    RecipeDto rb = PlanTestData.scoredRecipe(b, 20, "Italian", "beef", "bake", List.of());
    List<SlotAssignment> as =
        List.of(
            PlanTestData.assignment(UUID.randomUUID(), a, WEEK, 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), b, WEEK, 1, 2));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(ra, rb));
    assertThat(tuned.compute(PlanTestData.candidatePlan(WEEK, as), ctx))
        .isEqualByComparingTo(BigDecimal.ONE);
  }
}
