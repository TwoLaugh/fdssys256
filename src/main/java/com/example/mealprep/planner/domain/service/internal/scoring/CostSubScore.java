package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.provisions.api.dto.BudgetDto;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
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
 */
@Component
class CostSubScore implements SubScoreCalculator {

  private static final BigDecimal HALF = new BigDecimal("0.5");

  private final PlannerProperties properties;

  CostSubScore(PlannerProperties properties) {
    this.properties = properties;
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
      return HALF.setScale(6, RoundingMode.HALF_UP); // no budget → neutral
    }

    Map<String, SupplierProductDto> prices =
        provisions.supplierPricesByMappingKey() == null
            ? Map.of()
            : provisions.supplierPricesByMappingKey();
    Map<UUID, RecipeDto> recipes = ScoringSupport.recipeIndex(ctx);
    BigDecimal threshold = properties.scoring().cost().confidenceThreshold();

    BigDecimal estimatedCost = BigDecimal.ZERO;
    BigDecimal confSum = BigDecimal.ZERO;
    int ingredientCount = 0;

    if (plan.assignments() != null) {
      for (SlotAssignment a : plan.assignments()) {
        RecipeDto recipe = ScoringSupport.findRecipe(recipes, a.recipeId()).orElse(null);
        if (recipe == null
            || recipe.currentVersionBody() == null
            || recipe.currentVersionBody().ingredients() == null) {
          continue;
        }
        for (IngredientDto ing : recipe.currentVersionBody().ingredients()) {
          ingredientCount++;
          SupplierProductDto product =
              ing.ingredientMappingKey() == null ? null : prices.get(ing.ingredientMappingKey());
          BigDecimal confidence = product == null ? BigDecimal.ZERO : BigDecimal.ONE;
          if (confidence.compareTo(threshold) < 0) {
            confidence = BigDecimal.ZERO;
          }
          confSum = confSum.add(confidence);
          if (product != null && product.price() != null && ing.quantity() != null) {
            estimatedCost =
                estimatedCost.add(
                    product
                        .price()
                        .multiply(ing.quantity())
                        .multiply(BigDecimal.valueOf(a.servings())));
          }
        }
      }
    }

    BigDecimal meanConfidence =
        ingredientCount == 0
            ? BigDecimal.ZERO
            : confSum.divide(BigDecimal.valueOf(ingredientCount), 6, RoundingMode.HALF_UP);

    BigDecimal rawCostFit =
        BigDecimal.ONE
            .subtract(estimatedCost.divide(budget.weeklyTarget(), 6, RoundingMode.HALF_UP))
            .max(BigDecimal.ZERO)
            .min(BigDecimal.ONE);

    return HALF.add(rawCostFit.subtract(HALF).multiply(meanConfidence))
        .setScale(6, RoundingMode.HALF_UP);
  }
}
