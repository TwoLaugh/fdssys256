package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.recipe.api.dto.RecipeDto;
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
 * <p><b>01f codebase divergence — recipe nutrition not exposed</b>: {@code RecipeVersionDto} has no
 * {@code nutritionPerServing} JsonNode in this codebase. 01e established every macro total is
 * {@code 0}; 01f keeps that exactly (so the refactored gate is byte-identical against 01e's
 * fixtures). The per-slot multiply-by-{@code servings} seam lives here — when recipe-01h's
 * nutrition pipeline exposes per-serving macros, read them here and feed the {@link
 * DailyMacroTotals.Builder}. Every date with at least one assignment still gets a (zeroed) bucket
 * so the daily rollup lists the day.
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
      // Recipe nutrition is not exposed on RecipeVersionDto in this codebase → 0 macros.
      // The multiply-by-servings seam to plug real per-serving macros in is exactly here:
      //   int servings = a.servings();
      //   b.addKcal(perServingKcal * servings)
      //    .addProtein(perServingProtein.multiply(BigDecimal.valueOf(servings)))
      //    ... fat / carbs / fibre / saturatedFat / micros similarly.
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
