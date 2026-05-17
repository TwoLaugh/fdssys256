package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeTagsDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Variety sub-score. Algorithm LOCKED per LLD §VarietySubScore (2026-05-07):
 *
 * <pre>
 *   per_dimension(plan, dim, target) = min(1.0, count_distinct(non-null dim values) / target)
 *   VarietySubScore = mean(per_dimension(cuisine, 5), per_dimension(protein, 4),
 *                          per_dimension(cooking_method, 3))
 * </pre>
 *
 * <p>Dimension mapping: {@code cuisine = recipe.metadata().cuisine()}, {@code protein =
 * recipe.tags().protein()}, {@code cooking_method = recipe.tags().cookingMethod()}. Nulls are
 * excluded from the distinct count (a null neither contributes a distinct value nor the
 * denominator). Targets are tunable via {@code mealprep.planner.scoring.variety.*}.
 */
@Component
class VarietySubScore implements SubScoreCalculator {

  private final PlannerProperties properties;

  VarietySubScore(PlannerProperties properties) {
    this.properties = properties;
  }

  @Override
  public String name() {
    return "variety";
  }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    PlannerProperties.ScoringTuning.VarietyTargets targets = properties.scoring().variety();
    Map<UUID, RecipeDto> recipes = ScoringSupport.recipeIndex(ctx);

    Set<String> cuisines = new HashSet<>();
    Set<String> proteins = new HashSet<>();
    Set<String> methods = new HashSet<>();
    if (plan.assignments() != null) {
      for (SlotAssignment a : plan.assignments()) {
        RecipeDto recipe = ScoringSupport.findRecipe(recipes, a.recipeId()).orElse(null);
        if (recipe == null || recipe.currentVersionBody() == null) {
          continue;
        }
        RecipeVersionDto v = recipe.currentVersionBody();
        if (v.metadata() != null && v.metadata().cuisine() != null) {
          cuisines.add(v.metadata().cuisine());
        }
        RecipeTagsDto tags = v.tags();
        if (tags != null && tags.protein() != null) {
          proteins.add(tags.protein());
        }
        if (tags != null && tags.cookingMethod() != null) {
          methods.add(tags.cookingMethod());
        }
      }
    }

    BigDecimal cuisineScore = dimensionScore(cuisines.size(), targets.cuisine());
    BigDecimal proteinScore = dimensionScore(proteins.size(), targets.protein());
    BigDecimal methodScore = dimensionScore(methods.size(), targets.cookingMethod());
    return cuisineScore
        .add(proteinScore)
        .add(methodScore)
        .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal dimensionScore(int distinct, int target) {
    if (target <= 0) {
      return BigDecimal.ONE;
    }
    BigDecimal ratio =
        BigDecimal.valueOf(distinct).divide(BigDecimal.valueOf(target), 6, RoundingMode.HALF_UP);
    return ratio.min(BigDecimal.ONE);
  }
}
