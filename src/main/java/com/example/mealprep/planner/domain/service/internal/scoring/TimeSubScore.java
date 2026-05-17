package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Time-fit sub-score. Algorithm LOCKED per LLD §TimeSubScore (2026-05-07):
 *
 * <pre>
 *   slot_score(slot):
 *     if recipe.total_time_mins &lt;= slot.time_budget_min: 1.0
 *     overshoot = (total - budget) / budget
 *     return max(0, 1 - overshoot)
 *   TimeSubScore = mean(slot_score(slot))
 * </pre>
 *
 * <p>The {@code HardFilterRunner} (01d) already drops recipes with {@code totalTimeMins &gt;
 * timeBudgetMin × maxTimeOvershootRatio} (1.5×), so at scoring time {@code overshoot &le; 0.5} and
 * {@code slot_score ∈ [0.5, 1.0]}; the gradient applies within the surviving range. Zero slots, or
 * a slot whose recipe is absent from the pool, contribute {@code 1.0} (vacuous — empty slots are
 * tracked by the {@code qualityWarning} flag, not penalised here).
 */
@Component
class TimeSubScore implements SubScoreCalculator {

  @Override
  public String name() {
    return "time";
  }

  @Override
  public BigDecimal compute(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan.assignments() == null || plan.assignments().isEmpty()) {
      return BigDecimal.ONE;
    }
    Map<UUID, MealSlotSkeleton> bySlotId =
        ctx.slotSkeletons() == null
            ? Map.of()
            : ctx.slotSkeletons().stream()
                .collect(Collectors.toMap(MealSlotSkeleton::slotId, Function.identity()));
    Map<UUID, RecipeDto> recipes = ScoringSupport.recipeIndex(ctx);

    BigDecimal sum = BigDecimal.ZERO;
    int counted = 0;
    for (SlotAssignment a : plan.assignments()) {
      MealSlotSkeleton skel = bySlotId.get(a.slotId());
      RecipeDto recipe = ScoringSupport.findRecipe(recipes, a.recipeId()).orElse(null);
      if (skel == null || recipe == null || recipe.currentVersionBody() == null) {
        sum = sum.add(BigDecimal.ONE);
        counted++;
        continue;
      }
      int total = recipe.currentVersionBody().metadata().totalTimeMins();
      int budget = skel.timeBudgetMin();
      BigDecimal slotScore;
      if (budget <= 0 || total <= budget) {
        slotScore = BigDecimal.ONE;
      } else {
        BigDecimal overshoot =
            BigDecimal.valueOf((long) total - budget)
                .divide(BigDecimal.valueOf(budget), 6, RoundingMode.HALF_UP);
        slotScore = BigDecimal.ONE.subtract(overshoot).max(BigDecimal.ZERO);
      }
      sum = sum.add(slotScore);
      counted++;
    }
    return counted == 0
        ? BigDecimal.ONE
        : sum.divide(BigDecimal.valueOf(counted), 6, RoundingMode.HALF_UP);
  }
}
