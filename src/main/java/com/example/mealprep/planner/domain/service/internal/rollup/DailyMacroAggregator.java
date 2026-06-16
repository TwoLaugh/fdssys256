package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.nutrition.api.dto.PerMealDistributionDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.service.internal.PortionScaler;
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
    // Portion scaling: the primary eater's per-meal calorie targets, keyed by normalised slot kind.
    // Each slot's single serving is scaled toward its meal target so a ~440-kcal recipe can fill a
    // 1000-kcal lunch (×2.25), letting the plan reach the daily goal instead of capping at one
    // serving/slot. Empty when no targets → factor 1.0 (unchanged behaviour).
    Map<String, Integer> mealCalTargets = perMealCalorieTargets(ctx);

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
      // Portion factor for this slot: scale the single serving toward the slot's per-meal calorie
      // target (clamped 0.5–3.0). Scales macros AND micros (eating 2 servings doubles both), so it
      // lifts the calorie/protein magnitude and helps micro coverage at once. A slot whose kind has
      // no target (or a recipe with no calories) scales by 1.0 — see PortionScaler#factor.
      double portion =
          a.kind() == null
              ? 1.0
              : PortionScaler.factor(
                  n.calories(), mealCalTargets.get(PortionScaler.normaliseKind(a.kind().name())));
      BigDecimal pf = BigDecimal.valueOf(portion);

      b.addKcal((int) Math.round(n.calories() * portion));
      if (n.proteinG() != null) {
        b.addProtein(n.proteinG().multiply(pf));
      }
      if (n.carbsG() != null) {
        b.addCarbs(n.carbsG().multiply(pf));
      }
      if (n.fatG() != null) {
        b.addFat(n.fatG().multiply(pf));
      }
      if (n.fibreG() != null) {
        b.addFibre(n.fibreG().multiply(pf));
      }
      // NutritionPerServingDto carries no saturatedFat field → satFat stays 0 (its target is an
      // upper limit, so a 0 actual never penalises). Micros flow through (× portion) by source key.
      if (n.micros() != null) {
        Map<String, String> microSrc = n.microSources() == null ? Map.of() : n.microSources();
        for (Map.Entry<String, BigDecimal> micro : n.micros().entrySet()) {
          if (micro.getKey() != null && micro.getValue() != null) {
            b.addMicro(micro.getKey(), micro.getValue().multiply(pf));
            b.addMicroSource(micro.getKey(), microSrc.get(micro.getKey()));
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

  /**
   * Primary eater's per-meal calorie targets keyed by normalised slot kind ({@code SNACKS}→{@code
   * SNACK}). Empty if no nutrition targets / no eaters — callers then leave the portion factor at 1.
   */
  private Map<String, Integer> perMealCalorieTargets(PlanCompositionContext ctx) {
    Map<String, Integer> out = new LinkedHashMap<>();
    if (ctx == null || ctx.nutritionByUserId() == null || ctx.slotSkeletons() == null) {
      return out;
    }
    UUID primary =
        ctx.slotSkeletons().stream()
            .map(MealSlotSkeleton::eaters)
            .filter(e -> e != null && !e.isEmpty())
            .map(e -> e.get(0))
            .findFirst()
            .orElse(null);
    TargetsDto t = primary == null ? null : ctx.nutritionByUserId().get(primary);
    if (t == null || t.perMealDistribution() == null) {
      return out;
    }
    for (PerMealDistributionDto m : t.perMealDistribution()) {
      if (m != null && m.mealSlot() != null && m.calorieTarget() > 0) {
        out.put(PortionScaler.normaliseKind(m.mealSlot().name()), m.calorieTarget());
      }
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
