package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared confidence-weighted mean of per-ingredient supplier-price confidences. Extracted verbatim
 * from 01e's {@code CostSubScore.mean_confidence} so the sub-score and 01f's weekly rollup report
 * the identical {@code costConfidence} (ticket §"LLD divergence").
 *
 * <p><b>01e codebase divergence — confidence proxy</b> (carried forward unchanged): {@code
 * SupplierProductDto} has no scalar {@code confidence}; presence of a supplier-price entry for an
 * ingredient mapping key is {@code 1.0}, absence is {@code 0.0}. Confidences below {@code
 * mealprep.planner.scoring.cost.confidence-threshold} (default 0.1) clamp to 0. Zero ingredients →
 * {@code 0}. Result scale is 6 with {@code HALF_UP}, exactly as 01e computed it.
 */
@Component
public class WeeklyCostConfidence {

  private final PlannerProperties properties;

  public WeeklyCostConfidence(PlannerProperties properties) {
    this.properties = properties;
  }

  /** Confidence-weighted mean across every ingredient line in the plan. */
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    Map<String, SupplierProductDto> prices = supplierPrices(ctx);
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);
    BigDecimal threshold = properties.scoring().cost().confidenceThreshold();

    BigDecimal confSum = BigDecimal.ZERO;
    int ingredientCount = 0;

    if (plan != null && plan.assignments() != null) {
      for (SlotAssignment a : plan.assignments()) {
        RecipeDto recipe = byRecipeId.get(a.recipeId());
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
        }
      }
    }

    return ingredientCount == 0
        ? BigDecimal.ZERO
        : confSum.divide(BigDecimal.valueOf(ingredientCount), 6, RoundingMode.HALF_UP);
  }

  private Map<String, SupplierProductDto> supplierPrices(PlanCompositionContext ctx) {
    ProvisionForPlannerBundleDto provisions = ctx == null ? null : ctx.provisions();
    if (provisions == null || provisions.supplierPricesByMappingKey() == null) {
      return Map.of();
    }
    return provisions.supplierPricesByMappingKey();
  }

  private Map<UUID, RecipeDto> indexRecipes(PlanCompositionContext ctx) {
    Map<UUID, RecipeDto> index = new LinkedHashMap<>();
    if (ctx == null || ctx.recipePool() == null || ctx.recipePool().recipes() == null) {
      return index;
    }
    for (RecipeDto r : ctx.recipePool().recipes()) {
      if (r != null && r.id() != null) {
        index.putIfAbsent(r.id(), r);
      }
    }
    return index;
  }
}
