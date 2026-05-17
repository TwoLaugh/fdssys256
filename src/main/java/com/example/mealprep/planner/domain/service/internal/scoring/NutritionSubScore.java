package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Nutrition-fit sub-score. Algorithm LOCKED per LLD §NutritionSubScore (2026-05-07):
 *
 * <pre>
 *   direction_score(actual, target, direction):
 *     if target == null OR target == 0: 1.0
 *     deviation = (actual - target) / target
 *     UPPER_LIMIT:  penalty = max(0, deviation)
 *     LOWER_FLOOR:  penalty = max(0, -deviation)
 *     BOTH_BOUNDED: penalty = abs(deviation)
 *     return max(0, 1 - penalty)
 *   NutritionSubScore = mean(direction_score over each configured macro)
 * </pre>
 *
 * <p>Mean over zero configured macros (no targets row) is vacuously {@code 1.0} — documented
 * convention per ticket edge-case checklist.
 *
 * <p><b>Household-default aggregation (ticket item 17, worth user review)</b>: macros are summed
 * against the household's primary user only ({@link ScoringSupport#primaryUserId}); the per-eater
 * average is deferred to a calibration pass.
 *
 * <p><b>01e codebase divergence — recipe nutrition not exposed</b>: per ticket item 18, missing
 * recipe nutrition data is treated as {@code 0} for that macro (NOT {@code 0.5} neutral) so
 * cold-start nutrition surfaces as a lower score and nudges the planner toward recipes with
 * computed nutrition. {@code RecipeVersionDto} carries NO {@code nutritionPerServing} field on this
 * branch (the recipe-01h read DTO does not surface it), so weekly actuals are deterministically
 * {@code 0} until that contract ships; {@link WeeklyNutritionAccumulator} is the seam to plug it
 * in. Targets are read from the planner-01e {@code PlanCompositionContext.nutritionByUserId}
 * extension ({@code TargetsDto} — the canonical nutrition read shape; the ticket's {@code
 * NutritionForPlannerBundleDto} does not exist in this codebase).
 */
@Component
class NutritionSubScore implements SubScoreCalculator {

  @Override
  public String name() {
    return "nutrition";
  }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    UUID primary = ScoringSupport.primaryUserId(ctx);
    TargetsDto targets = primary == null ? null : ctx.nutritionByUserId().get(primary);
    if (targets == null) {
      return BigDecimal.ONE; // no targets configured → vacuous mean = 1.0
    }

    Map<UUID, RecipeDto> recipes = ScoringSupport.recipeIndex(ctx);
    WeeklyNutritionAccumulator weekly = new WeeklyNutritionAccumulator();
    if (plan.assignments() != null) {
      for (SlotAssignment a : plan.assignments()) {
        RecipeDto recipe = ScoringSupport.findRecipe(recipes, a.recipeId()).orElse(null);
        weekly.add(recipe, a.servings());
      }
    }

    List<BigDecimal> scores = new ArrayList<>();
    // calories: targets.calories() is a CalorieTargetDto (int dailyTarget + direction)
    if (targets.calories() != null && targets.calories().dailyTarget() > 0) {
      scores.add(
          directionScore(
              weekly.calories(),
              BigDecimal.valueOf(targets.calories().dailyTarget()),
              targets.calories().direction()));
    }
    addMacro(scores, weekly.protein(), targets.protein());
    addMacro(scores, weekly.carbs(), targets.carbs());
    addMacro(scores, weekly.fat(), targets.fat());
    addMacro(scores, weekly.fibre(), targets.fibre());
    addMacro(scores, weekly.saturatedFat(), targets.satFat());

    if (scores.isEmpty()) {
      return BigDecimal.ONE; // mean over zero configured macros is vacuously 1.0
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal s : scores) {
      sum = sum.add(s);
    }
    return sum.divide(BigDecimal.valueOf(scores.size()), 6, RoundingMode.HALF_UP);
  }

  private static void addMacro(List<BigDecimal> scores, BigDecimal actual, MacroTargetDto target) {
    if (target == null || target.targetG() == null) {
      return; // macro not configured → excluded from the mean (no contribution)
    }
    scores.add(directionScore(actual, target.targetG(), target.direction()));
  }

  /** LOCKED direction-aware deviation penalty mapped to a {@code [0,1]} fit score. */
  static BigDecimal directionScore(
      BigDecimal actual, BigDecimal target, EnforcementDirection direction) {
    if (target == null || target.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ONE;
    }
    BigDecimal deviation = actual.subtract(target).divide(target, 6, RoundingMode.HALF_UP);
    BigDecimal penalty;
    switch (direction) {
      case UPPER_LIMIT -> penalty = deviation.max(BigDecimal.ZERO);
      case LOWER_FLOOR -> penalty = deviation.negate().max(BigDecimal.ZERO);
      case BOTH_BOUNDED -> penalty = deviation.abs();
      default -> penalty = deviation.abs();
    }
    return BigDecimal.ONE.subtract(penalty).max(BigDecimal.ZERO);
  }

  /**
   * Walks {@code plan.assignments}, multiplying each recipe's per-serving macros by servings and
   * summing per macro. {@code RecipeVersionDto} exposes no {@code nutritionPerServing} on this
   * branch, so every contribution is {@code 0} (ticket item 18 — missing nutrition → 0). This is
   * the single seam to wire real recipe nutrition once the contract ships.
   */
  static final class WeeklyNutritionAccumulator {
    private BigDecimal calories = BigDecimal.ZERO;
    private BigDecimal protein = BigDecimal.ZERO;
    private BigDecimal carbs = BigDecimal.ZERO;
    private BigDecimal fat = BigDecimal.ZERO;
    private BigDecimal fibre = BigDecimal.ZERO;
    private BigDecimal saturatedFat = BigDecimal.ZERO;

    void add(RecipeDto recipe, int servings) {
      // No nutritionPerServing on RecipeVersionDto in this codebase — every macro stays 0 until
      // the recipe-nutrition read contract is surfaced. Kept as the wiring seam.
    }

    BigDecimal calories() {
      return calories;
    }

    BigDecimal protein() {
      return protein;
    }

    BigDecimal carbs() {
      return carbs;
    }

    BigDecimal fat() {
      return fat;
    }

    BigDecimal fibre() {
      return fibre;
    }

    BigDecimal saturatedFat() {
      return saturatedFat;
    }
  }
}
