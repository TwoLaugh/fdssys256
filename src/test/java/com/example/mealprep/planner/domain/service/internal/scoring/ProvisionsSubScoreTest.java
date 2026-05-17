package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ProvisionsSubScore} — LOCKED covered/max waste-value ratio. Inventory expiry
 * is anchored to {@code LocalDate.now()}-relative (no hardcoded date → no time-bomb).
 */
class ProvisionsSubScoreTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final ProvisionsSubScore calc = new ProvisionsSubScore(PlanTestData.scoringProperties());

  @Test
  void name_is_provisions() {
    assertThat(calc.name()).isEqualTo("provisions");
  }

  @Test
  void no_inventory_returns_neutral_disabled_proxy() {
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(), bundle, Map.of(), Map.of());
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, List.of()), ctx))
        .isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  void fully_covered_far_expiry_scores_one_third_of_max_tier() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    // demand = 1 * 2 servings = 2; inventory 10 covers it, expiry far (>7d) → waste_value 1.0
    var inv = PlanTestData.inventoryItem("rice", new BigDecimal("10"), 30);
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    // covered = 2 * 1.0 = 2; max = 2 * 3.0 = 6 → 2/6 ≈ 0.333333
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.333333"));
  }

  @Test
  void near_expiry_item_weighted_three_times_scores_one() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("milk"));
    var inv = PlanTestData.inventoryItem("milk", new BigDecimal("10"), 1); // ≤1d → waste_value 3.0
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    // covered = 2 * 3.0 = 6; max = 2 * 3.0 = 6 → 1.0
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void ingredient_not_in_inventory_contributes_to_max_only() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe =
        PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice", "saffron"));
    var inv = PlanTestData.inventoryItem("rice", new BigDecimal("10"), 30);
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of(inv));
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    // rice covered 2*1=2; saffron only in max. max = (2+2)*3 = 12 → 2/12 ≈ 0.166667
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.166667"));
  }
}
