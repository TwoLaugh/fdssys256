package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.service.internal.PerMealCalorieTargets;
import com.example.mealprep.planner.domain.service.internal.PortionScaler;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
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
   * Single-entry memo so the two scoring callers that aggregate the SAME candidate within one
   * {@code ScoringEngine.score()} pass — {@code NutritionSubScore} and {@code NutritionFloorGate} —
   * share one walk instead of each re-summing every slot (the beam scores tens of thousands of
   * candidates, so that doubled walk was pure waste). Keyed by candidate + context IDENTITY (both
   * immutable for the call); a different candidate/context replaces the entry and recomputes. The
   * result map is treated read-only by all callers, so sharing the reference is safe.
   */
  private record AggMemo(
      CandidatePlan plan, PlanCompositionContext ctx, Map<LocalDate, DailyMacroTotals> result) {}

  private volatile AggMemo aggMemo;

  /**
   * Aggregate macros per calendar date. Days with assignments but no resolvable recipe (or no
   * exposed nutrition) appear with zeroed totals. Iteration order is date-ascending. Memoised per
   * (candidate, context) identity (see {@link AggMemo}) — byte-identical to a fresh compute.
   */
  public Map<LocalDate, DailyMacroTotals> aggregateByDate(
      CandidatePlan plan, PlanCompositionContext ctx) {
    AggMemo m = aggMemo;
    if (m != null && m.plan() == plan && m.ctx() == ctx) {
      return m.result();
    }
    Map<LocalDate, DailyMacroTotals> out = computeByDate(plan, ctx);
    aggMemo = new AggMemo(plan, ctx, out);
    return out;
  }

  private Map<LocalDate, DailyMacroTotals> computeByDate(
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
    Map<String, Integer> mealCalTargets = PerMealCalorieTargets.forContext(ctx);
    Map<String, BigDecimal> mealProteinTargets = PerMealCalorieTargets.proteinForContext(ctx);

    for (SlotAssignment a : plan.assignments()) {
      applySlot(builders, a, byRecipeId, mealCalTargets, mealProteinTargets);
    }

    return build(builders);
  }

  /**
   * Fold one assignment's per-serving nutrition into the per-date builder map — the single source of
   * the per-slot aggregation arithmetic, shared by the whole-plan walk ({@link #computeByDate}) and
   * the incremental Stage-A scorer ({@code IncrementalScoringEngine}). Mutates {@code builders} in
   * place (a {@link TreeMap} so iteration stays date-ascending), creating a day bucket on first use
   * so every date with an assignment appears in the rollup list even when the recipe / nutrition is
   * missing. Pure function of its arguments otherwise — no DB, no time, no randomness. The {@code
   * mealCalTargets} map is {@link PerMealCalorieTargets#forContext} for the run; {@code byRecipeId}
   * is the pool index — both constant for a generation, so callers build them once.
   *
   * <p>Returns the {@code builders} map for fluent chaining; the same reference is mutated.
   */
  Map<LocalDate, DailyMacroTotals.Builder> applySlot(
      Map<LocalDate, DailyMacroTotals.Builder> builders,
      SlotAssignment a,
      Map<UUID, RecipeDto> byRecipeId,
      Map<String, Integer> mealCalTargets,
      Map<String, BigDecimal> mealProteinTargets) {
    LocalDate date = a.onDate();
    if (date == null) {
      return builders;
    }
    // Every day with an assignment gets a bucket even if the recipe / nutrition is missing,
    // so the day still appears in the daily rollup list (ticket edge-case checklist).
    DailyMacroTotals.Builder b = builders.computeIfAbsent(date, DailyMacroTotals::builder);

    // Phase-2 in-meal additions ride on the assignment and carry their OWN per-portion nutrition
    // (USDA-derived for ingredients, the side's figures for side recipes). They are pre-sized to
    // the residual, so they are summed verbatim (NOT portion-scaled like the main) and counted
    // even when the slot's main recipe is missing/unresolved.
    addAdditions(b, a.additions());

    RecipeDto recipe = byRecipeId.get(a.recipeId());
    if (recipe == null) {
      return builders; // unfilled / unresolvable slot → 0-macro contribution
    }
    RecipeVersionDto versionBody = recipe.currentVersionBody();
    NutritionPerServingDto n = versionBody == null ? null : versionBody.nutritionPerServing();
    if (n == null) {
      // Nutrition not yet computed for this recipe (status pending / nothing persisted) → 0
      // contribution. The day still has its bucket so it appears in the rollup list.
      return builders;
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
    // FINALISE override: if the chosen plan's PortionOptimizer attached an optimised per-slot
    // factor, use it verbatim (the optimiser is the finalise authority — it sized the day's slots
    // jointly against ALL macro targets). When ABSENT (every in-flight beam/incremental assignment,
    // and any assignment built before finalise) fall back to the EXACT existing calorie-only
    // PortionScaler path, so the beam / incremental scoring walk stays byte-identical.
    double portion;
    if (a.portionFactor() != null) {
      portion = a.portionFactor().doubleValue();
    } else if (a.kind() != null) {
      String kindKey = PortionScaler.normaliseKind(a.kind().name());
      // Macro-aware: scale toward the per-meal calorie target but cap so a protein-dense recipe
      // can't inflate the day's protein floor when scaled up to hit calories.
      portion =
          PortionScaler.factor(
              n.calories(),
              mealCalTargets.get(kindKey),
              n.proteinG(),
              mealProteinTargets.get(kindKey));
    } else {
      portion = 1.0;
    }
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
    return builders;
  }

  /** Materialise the per-date builder map into immutable totals, in date-ascending order. */
  Map<LocalDate, DailyMacroTotals> build(Map<LocalDate, DailyMacroTotals.Builder> builders) {
    Map<LocalDate, DailyMacroTotals> out = new LinkedHashMap<>();
    for (Map.Entry<LocalDate, DailyMacroTotals.Builder> e : builders.entrySet()) {
      out.put(e.getKey(), e.getValue().build());
    }
    return out;
  }

  /**
   * Seed an empty {@link IncrementalNutritionState} for {@code ctx} — captures the per-meal calorie
   * targets and pool index once (both constant for a generation), so each beam {@code append} folds
   * a slot in O(1)-ish via the SAME {@link #applySlot} arithmetic the whole-plan walk uses.
   */
  public IncrementalNutritionState seedIncremental(PlanCompositionContext ctx) {
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);
    Map<String, Integer> mealCalTargets = PerMealCalorieTargets.forContext(ctx);
    Map<String, BigDecimal> mealProteinTargets = PerMealCalorieTargets.proteinForContext(ctx);
    return new IncrementalNutritionState(
        this, mealCalTargets, mealProteinTargets, byRecipeId, new TreeMap<>());
  }

  /**
   * Sum each in-meal addition's own per-portion nutrition into the day bucket. Additions are
   * pre-sized to the day's residual, so (unlike the main recipe) they are NOT portion-scaled.
   * Macros + micros + per-micro provenance flow through verbatim, mirroring the main-recipe walk.
   */
  private void addAdditions(DailyMacroTotals.Builder b, List<Addition> additions) {
    if (additions == null) {
      return;
    }
    for (Addition add : additions) {
      if (add == null || add.nutrition() == null) {
        continue;
      }
      NutritionPerServingDto n = add.nutrition();
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
      if (n.micros() != null) {
        Map<String, String> microSrc = n.microSources() == null ? Map.of() : n.microSources();
        for (Map.Entry<String, BigDecimal> micro : n.micros().entrySet()) {
          if (micro.getKey() != null && micro.getValue() != null) {
            b.addMicro(micro.getKey(), micro.getValue());
            b.addMicroSource(micro.getKey(), microSrc.get(micro.getKey()));
          }
        }
      }
    }
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
