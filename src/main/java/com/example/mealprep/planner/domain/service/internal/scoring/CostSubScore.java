package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyCostAggregator;
import com.example.mealprep.planner.domain.service.internal.rollup.WeeklyCostConfidence;
import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cost-fit sub-score. Algorithm LOCKED per LLD §CostSubScore (2026-05-07):
 *
 * <pre>
 *   raw_cost_fit  = clamp(1 - (estimated_weekly_cost / weekly_budget), 0, 1)
 *   mean_conf     = confidence_weighted_mean(supplierProduct.confidence over plan ingredients)
 *   CostSubScore  = 0.5 + (raw_cost_fit - 0.5) × mean_conf
 * </pre>
 *
 * <p>Null budget → {@code 0.5} neutral (no budget gate; ticket item 21). When most ingredients lack
 * a supplier price {@code mean_conf → 0} and the score collapses to {@code 0.5} by design (ticket
 * item 24). Confidences below {@code mealprep.planner.scoring.cost.confidence-threshold} (default
 * 0.1) are clamped to 0 to stop tiny-confidence noise pulling the score off neutral.
 *
 * <p><b>01e codebase divergence — confidence proxy</b>: {@code SupplierProductDto} carries no
 * scalar {@code confidence} field; 01e treats the presence of a supplier-price entry for an
 * ingredient mapping key as {@code confidence = 1.0} and its absence as {@code 0.0} (low-data →
 * regression to neutral, exactly the LOCKED formula's intent). Estimated weekly cost = Σ
 * (supplierPrice × ingredient.quantity × servings) over priced ingredients. Currency assumed GBP
 * matching {@code BudgetDto.weeklyTarget}.
 *
 * <p><b>planner-01f refactor</b>: the estimated-weekly-cost walk and the confidence-weighted mean
 * are delegated to the shared {@link DailyCostAggregator} / {@link WeeklyCostConfidence} helpers
 * (also used by 01f's {@code RollupBuilder}) so the cost sub-score and the weekly rollup never
 * drift. The LOCKED formula and its output are byte-identical to 01e — only the per-ingredient walk
 * moved behind the helpers.
 */
@Component
class CostSubScore implements SubScoreCalculator {

  private static final BigDecimal HALF = new BigDecimal("0.5");

  private final PlannerProperties properties;
  private final DailyCostAggregator costAggregator;
  private final WeeklyCostConfidence costConfidence;

  CostSubScore(
      PlannerProperties properties,
      DailyCostAggregator costAggregator,
      WeeklyCostConfidence costConfidence) {
    this.properties = properties;
    this.costAggregator = costAggregator;
    this.costConfidence = costConfidence;
  }

  @Override
  public String name() {
    return "cost";
  }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    ProvisionForPlannerBundleDto provisions = ctx.provisions();
    BudgetDto budget = provisions == null ? null : provisions.budget();
    if (budget == null
        || budget.weeklyTarget() == null
        || budget.weeklyTarget().compareTo(BigDecimal.ZERO) <= 0) {
      // No budget/prices → instead of a flat neutral, reward shopping economy via INGREDIENT REUSE:
      // plans whose distinct recipes share ingredient keys need a smaller, less wasteful shopping
      // list. Falls back to neutral when the pool carries no ingredient data.
      return reuseScore(plan, ctx);
    }

    BigDecimal estimatedCost = costAggregator.totalCost(plan, ctx);
    BigDecimal meanConfidence = costConfidence.compute(plan, ctx);

    BigDecimal rawCostFit =
        BigDecimal.ONE
            .subtract(estimatedCost.divide(budget.weeklyTarget(), 6, RoundingMode.HALF_UP))
            .max(BigDecimal.ZERO)
            .min(BigDecimal.ONE);

    return HALF.add(rawCostFit.subtract(HALF).multiply(meanConfidence))
        .setScale(6, RoundingMode.HALF_UP);
  }

  /**
   * Ingredient-reuse reward (the no-budget shopping-economy proxy): {@code 1 − distinctKeys /
   * totalKeys} over the plan's <b>distinct</b> recipes (repeats add no new shopping items). Higher
   * when the chosen recipes share canonical ingredient keys, so the planner favours recipe sets
   * that collapse into a smaller, less wasteful shopping list. Neutral ({@code 0.5}) for an empty
   * plan or a pool with no ingredient data — preserving the prior no-budget behaviour exactly in
   * that case. Whole-plan (like the cost path) so the incremental and exact composites stay
   * byte-identical.
   */
  private BigDecimal reuseScore(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan.assignments() == null || plan.assignments().isEmpty()) {
      return HALF.setScale(6, RoundingMode.HALF_UP);
    }
    Map<UUID, RecipeDto> recipes = ScoringSupport.recipeIndex(ctx);
    Set<UUID> seenRecipes = new HashSet<>();
    Set<String> distinctKeys = new HashSet<>();
    int totalKeys = 0;
    for (SlotAssignment a : plan.assignments()) {
      UUID recipeId = a.recipeId();
      if (recipeId == null || !seenRecipes.add(recipeId)) {
        continue; // dedupe: each distinct recipe's ingredients count once toward the shopping list
      }
      RecipeDto recipe = recipes.get(recipeId);
      if (recipe == null
          || recipe.currentVersionBody() == null
          || recipe.currentVersionBody().ingredients() == null) {
        continue;
      }
      for (IngredientDto ingredient : recipe.currentVersionBody().ingredients()) {
        String key = ingredient.ingredientMappingKey();
        if (key != null && !key.isBlank()) {
          totalKeys++;
          distinctKeys.add(key);
        }
      }
    }
    if (totalKeys == 0) {
      return HALF.setScale(6, RoundingMode.HALF_UP); // no ingredient data → neutral
    }
    BigDecimal fraction =
        BigDecimal.valueOf(distinctKeys.size())
            .divide(BigDecimal.valueOf(totalKeys), 6, RoundingMode.HALF_UP);
    return BigDecimal.ONE.subtract(fraction).setScale(6, RoundingMode.HALF_UP);
  }
}
