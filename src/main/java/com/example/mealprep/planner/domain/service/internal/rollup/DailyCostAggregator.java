package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared per-day estimated-cost walk: {@code Σ (supplierPrice × ingredient.quantity × servings)}
 * over priced ingredients, keyed by {@link SlotAssignment#onDate()}. Extracted from 01e's {@code
 * CostSubScore} so the sub-score and 01f's {@code RollupBuilder} compute the identical figure
 * (ticket §"LLD divergence" — same drift argument as the macro aggregator). Pure function.
 *
 * <p>An ingredient with no supplier-price entry contributes {@code 0} (matches 01e: absence of a
 * price = no cost contribution). {@code totalCost} is the sum across all days — exactly what 01e's
 * {@code estimated_weekly_cost} was, so the sub-score is byte-identical post-refactor.
 */
@Component
public class DailyCostAggregator {

  public DailyCostAggregator() {}

  /** Per-date estimated cost in the budget currency (GBP). Date-ascending iteration order. */
  public Map<LocalDate, BigDecimal> aggregateByDate(
      CandidatePlan plan, PlanCompositionContext ctx) {
    Map<LocalDate, BigDecimal> byDate = new TreeMap<>();
    if (plan == null || plan.assignments() == null) {
      return new LinkedHashMap<>();
    }

    Map<String, SupplierProductDto> prices = supplierPrices(ctx);
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);

    for (SlotAssignment a : plan.assignments()) {
      LocalDate date = a.onDate();
      if (date == null) {
        continue;
      }
      byDate.putIfAbsent(date, BigDecimal.ZERO);
      RecipeDto recipe = byRecipeId.get(a.recipeId());
      if (recipe == null
          || recipe.currentVersionBody() == null
          || recipe.currentVersionBody().ingredients() == null) {
        continue;
      }
      BigDecimal dayTotal = byDate.get(date);
      for (IngredientDto ing : recipe.currentVersionBody().ingredients()) {
        SupplierProductDto product =
            ing.ingredientMappingKey() == null ? null : prices.get(ing.ingredientMappingKey());
        if (product != null && product.price() != null && ing.quantity() != null) {
          dayTotal =
              dayTotal.add(
                  product
                      .price()
                      .multiply(ing.quantity())
                      .multiply(BigDecimal.valueOf(a.servings())));
        }
      }
      byDate.put(date, dayTotal);
    }

    return new LinkedHashMap<>(byDate);
  }

  /** Convenience: the plan-wide estimated cost (sum across days). */
  public BigDecimal totalCost(CandidatePlan plan, PlanCompositionContext ctx) {
    return aggregateByDate(plan, ctx).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
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
