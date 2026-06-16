package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Single shared per-day macro aggregation walk over a candidate plan. Consumed by 01f's {@code
 * RollupBuilder} and (post-refactor) 01e's {@code NutritionFloorGate} so the two never drift
 * (ticket §"LLD divergence"). Pure function — no DB, no time, no randomness.
 *
 * <p>Keying is by {@link SlotAssignment#onDate()} (the date is carried directly on the assignment
 * in this codebase; there is no {@code slotId -> onDate} skeleton indirection the ticket's verbatim
 * snippet assumed — that would also break for unfilled / pinned slots whose skeleton may differ).
 *
 * <p><b>Recipe nutrition is now wired</b> (nutrition-driven planning): {@code
 * RecipeVersionDto.nutritionPerServing} surfaces the per-serving figures the nutrition module
 * persisted, so this walk reads {@code recipe.currentVersionBody().nutritionPerServing()} and sums
 * macros + micros per day. Each slot contributes exactly ONE serving — nutrition targets are
 * per-person (the primary eater's daily intake), so this deliberately does NOT scale by {@code
 * a.servings()} (the household head-count, which cost/provisions scale by). Recipes with no
 * computed nutrition ({@code nutritionPerServing == null}, status pending) contribute 0, but every
 * date with at least one assignment still gets a bucket so the daily rollup lists the day.
 */
@Component
public class DailyMacroAggregator {

  public DailyMacroAggregator() {}

  /**
   * Aggregate macros per calendar date. Days with assignments but no resolvable recipe (or no
   * exposed nutrition) appear with zeroed totals. Iteration order is date-ascending.
   */
  public Map<LocalDate, DailyMacroTotals> aggregateByDate(
      CandidatePlan plan, PlanCompositionContext ctx) {
    Map<LocalDate, DailyMacroTotals.Builder> builders = new TreeMap<>();
    if (plan == null || plan.assignments() == null) {
      return new LinkedHashMap<>();
    }

    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);

    for (SlotAssignment a : plan.assignments()) {
      LocalDate date = a.onDate();
      if (date == null) {
        continue;
      }
      // Every day with an assignment gets a bucket even if the recipe / nutrition is missing,
      // so the day still appears in the daily rollup list (ticket edge-case checklist).
      DailyMacroTotals.Builder b = builders.computeIfAbsent(date, DailyMacroTotals::builder);

      RecipeDto recipe = byRecipeId.get(a.recipeId());
      if (recipe == null) {
        continue; // unfilled / unresolvable slot → 0-macro contribution
      }
      RecipeVersionDto versionBody = recipe.currentVersionBody();
      NutritionPerServingDto n = versionBody == null ? null : versionBody.nutritionPerServing();
      if (n == null) {
        // Nutrition not yet computed for this recipe (status pending / nothing persisted) → 0
        // contribution. The day still has its bucket so it appears in the rollup list.
        continue;
      }
      // PER-PERSON nutrition: targets (e.g. 3600 kcal / 150 g protein) are the primary eater's
      // DAILY intake, so each scheduled slot contributes exactly ONE serving — deliberately NOT
      // a.servings() (= skel.eaters().size(), the household head-count that DailyCostAggregator and
      // ProvisionsSubScore scale by). Multiplying nutrition by the head-count would overstate a
      // multi-eater household's per-person intake by that factor and wreck target comparison.
      b.addKcal(n.calories());
      if (n.proteinG() != null) {
        b.addProtein(n.proteinG());
      }
      if (n.carbsG() != null) {
        b.addCarbs(n.carbsG());
      }
      if (n.fatG() != null) {
        b.addFat(n.fatG());
      }
      if (n.fibreG() != null) {
        b.addFibre(n.fibreG());
      }
      // NutritionPerServingDto carries no saturatedFat field → satFat stays 0 (its target is an
      // upper limit, so a 0 actual never penalises). Micros flow through verbatim by source key.
      if (n.micros() != null) {
        for (Map.Entry<String, BigDecimal> micro : n.micros().entrySet()) {
          if (micro.getKey() != null && micro.getValue() != null) {
            b.addMicro(micro.getKey(), micro.getValue());
          }
        }
      }
    }

    Map<LocalDate, DailyMacroTotals> out = new LinkedHashMap<>();
    for (Map.Entry<LocalDate, DailyMacroTotals.Builder> e : builders.entrySet()) {
      out.put(e.getKey(), e.getValue().build());
    }
    return out;
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
