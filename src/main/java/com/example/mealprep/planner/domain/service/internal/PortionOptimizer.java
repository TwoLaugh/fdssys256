package com.example.mealprep.planner.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.config.PlannerProperties.ScoringTuning.NutritionMacroWeights;
import com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroTotals;
import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Finalise-only per-day portioning optimisation. Runs ONCE on the chosen plan (NOT during the beam
 * search) and sets each slot's portion factor — the per-person servings of its main recipe — to
 * minimise the day's total weighted deviation from <b>all</b> the primary eater's daily macro
 * targets simultaneously (calories + protein + carbs + fat + fibre + satFat, each with its
 * enforcement direction).
 *
 * <p><b>Why this exists.</b> The beam's {@link PortionScaler} scales each slot to its per-meal
 * <i>calorie</i> target and then caps protein — a local, per-slot band-aid that plays whack-a-mole
 * (capping protein lets fat overshoot). This optimiser instead chooses servings jointly across the
 * day so the bounded grid naturally balances every macro at once — no caps. {@link PortionScaler}
 * is left intact as the beam's cheap ranking proxy; this is the finalise authority. The beam,
 * incremental scoring, and aggregator's existing scaler path are all untouched: during the search
 * assignments carry a {@code null} portion factor, so the old path runs; here we attach the
 * optimised factor via {@link SlotAssignment#withPortionFactor}, and the aggregator / persister read
 * it when present.
 *
 * <p><b>The optimisation (per day).</b> For each day, choose servings {@code x_i} for the day's
 * slots on the grid {@code {0.5, 0.75, …, 3.0}} (= {@link PortionScaler#MIN_FACTOR}..{@link
 * PortionScaler#MAX_FACTOR} step {@link PortionScaler#STEP}) — a <i>physical feasibility</i> bound
 * (you can't eat 0× or 8× a dish), NOT a macro cap. Each slot {@code i} contributes {@code x_i ×}
 * (its recipe's per-serving macros) to the day's totals; the objective is the sum over configured
 * macros of a direction-aware deviation penalty (see {@link #penalty}). Days with ≤ 5 slots are
 * brute-forced over the full grid ({@code ≤ 11^5}); larger days use greedy coordinate descent
 * warm-started from the calorie-only {@link PortionScaler} factor. Deterministic, no external
 * solver, no AI.
 */
@Component
public class PortionOptimizer {

  // ---- tunable penalty weights -----------------------------------------------------------------
  // Per-macro importance is now CONFIGURED (mealprep.planner.scoring.nutrition-macro-weights.*) and
  // injected via PlannerProperties — this is the user-facing "what do you care about" knob. The live
  // default is calories/protein-primary with carbs/fat a minor nudge; a weight of 0 drops a macro
  // from the objective entirely (importance × penalty == 0). See NutritionMacroWeights.

  // Direction-aware (under, over) deviation weights. A FLOOR penalises undershoot hard (3.0) but
  // STILL mildly penalises overshoot (0.5) — this is the nuance that stops the optimiser piling on
  // 280 g of protein "for free" just because protein is a floor. A LIMIT is the mirror; a bounded
  // band penalises both directions hard.
  private static final double FLOOR_UNDER = 3.0;
  private static final double FLOOR_OVER = 0.5;
  private static final double LIMIT_UNDER = 0.5;
  private static final double LIMIT_OVER = 3.0;
  private static final double BOUNDED_UNDER = 3.0;
  private static final double BOUNDED_OVER = 3.0;

  // A snack slot may scale DOWN but not UP past one serving — keeps the day-total optimiser from
  // turning a snack into a full meal. Mains stay at the physical PortionScaler.MAX_FACTOR.
  private static final double SNACK_MAX_FACTOR = 1.0;

  // Greedy coordinate-descent passes for days with > 5 slots (rare; the brute force handles the
  // common case exactly). A handful of passes converges since each slot's best value given the
  // others fixed is a 1-D grid search and the objective is bounded below.
  private static final int GREEDY_PASSES = 6;

  /** Zero per-day additions baseline — the mains-only objective (unit tests, no-additions days). */
  private static final double[] ZERO_OFFSET = new double[Macro.values().length];

  private final PlannerProperties properties;

  public PortionOptimizer(PlannerProperties properties) {
    this.properties = properties;
  }

  /**
   * Attach optimised per-slot portion factors to {@code assignments}, grouped + solved per {@code
   * onDate}. Returns a new list (same order) with {@link SlotAssignment#withPortionFactor} set on
   * each assignment. A day with no targets, or a slot whose recipe has no per-serving nutrition,
   * leaves that slot's factor at {@code 1.0}.
   */
  public List<SlotAssignment> optimise(List<SlotAssignment> assignments, PlanCompositionContext ctx) {
    if (assignments == null || assignments.isEmpty()) {
      return assignments;
    }
    TargetsDto targets = primaryTargets(ctx);
    List<MacroTarget> configured = configuredMacros(targets, properties.scoring().nutritionMacroWeights());
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);

    // Group assignments by day, preserving the original list position so we can stitch the solved
    // factors back into a same-order output.
    Map<LocalDate, List<Integer>> byDate = new LinkedHashMap<>();
    for (int i = 0; i < assignments.size(); i++) {
      SlotAssignment a = assignments.get(i);
      LocalDate date = a.onDate();
      byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(i);
    }

    // Default every slot to 1.0; the per-day solve overwrites slots it can size.
    BigDecimal[] factors = new BigDecimal[assignments.size()];
    for (int i = 0; i < factors.length; i++) {
      factors[i] = BigDecimal.ONE;
    }

    for (Map.Entry<LocalDate, List<Integer>> day : byDate.entrySet()) {
      if (day.getKey() == null) {
        continue; // no date → can't aggregate a day; leave those slots at 1.0
      }
      solveDay(assignments, day.getValue(), configured, byRecipeId, ctx, factors);
    }

    List<SlotAssignment> out = new ArrayList<>(assignments.size());
    for (int i = 0; i < assignments.size(); i++) {
      out.add(assignments.get(i).withPortionFactor(factors[i]));
    }
    return out;
  }

  /**
   * Solve one day's portioning and write the chosen factor into {@code factors} at each slot's
   * original list index. Slots whose recipe has no per-serving nutrition are fixed at 1.0 and
   * excluded from the decision (their macros are 0 anyway). No configured targets / no sizable slot
   * → every slot stays 1.0.
   */
  private void solveDay(
      List<SlotAssignment> assignments,
      List<Integer> dayIndices,
      List<MacroTarget> configured,
      Map<UUID, RecipeDto> byRecipeId,
      PlanCompositionContext ctx,
      BigDecimal[] factors) {
    // Build the per-slot decision model: per-serving macro vector + a calorie-only warm start.
    List<Integer> sizable = new ArrayList<>();
    List<double[]> perServing = new ArrayList<>(); // index-aligned with `sizable`
    List<Double> warmStart = new ArrayList<>();
    Map<String, Integer> mealCalTargets = PerMealCalorieTargets.forContext(ctx);
    Map<String, BigDecimal> mealProteinTargets = PerMealCalorieTargets.proteinForContext(ctx);

    for (int idx : dayIndices) {
      SlotAssignment a = assignments.get(idx);
      double[] macros = perServingMacros(a, byRecipeId);
      if (macros == null) {
        factors[idx] = BigDecimal.ONE; // no nutrition → unsizable, plain single serving
        continue;
      }
      sizable.add(idx);
      perServing.add(macros);
      // Warm start from the beam's calorie-only PortionScaler factor (clamped to the grid).
      double warm = 1.0;
      if (a.kind() != null) {
        String kindKey = PortionScaler.normaliseKind(a.kind().name());
        NutritionPerServingDto n = nutritionFor(a, byRecipeId);
        warm =
            PortionScaler.factor(
                n.calories(),
                mealCalTargets.get(kindKey),
                n.proteinG(),
                mealProteinTargets.get(kindKey));
      }
      warmStart.add(snapToGrid(warm));
    }

    if (sizable.isEmpty() || configured.isEmpty()) {
      // Nothing to size, or no targets to optimise against → everything stays 1.0.
      for (int idx : sizable) {
        factors[idx] = BigDecimal.ONE;
      }
      return;
    }

    // Fixed per-day additions baseline. Phase-2 in-meal additions ride on the assignments and are
    // summed VERBATIM by DailyMacroAggregator (NOT portion-scaled). The optimiser must size the
    // mains against the target NET of this baseline, else the persisted day = optimised-mains +
    // additions overshoots every target the additions already contribute to. Modelled as a constant
    // offset added to every candidate's day total, so the optimiser's day-total arithmetic is
    // identical to the aggregator's — the two now cooperate instead of double-counting.
    double[] additionOffset = dayAdditionOffset(assignments, dayIndices);

    // Per-slot upper bound on the serving factor. Snacks are capped so the day-total optimiser can't
    // balloon a snack into a 2-3× meal (e.g. a 600 kcal recipe scaled ×2.25 → a 1,400 kcal "snack");
    // the mains carry the remaining calories instead. Everything else keeps the physical max.
    double[] maxFactor = new double[sizable.size()];
    for (int s = 0; s < sizable.size(); s++) {
      SlotAssignment a = assignments.get(sizable.get(s));
      boolean isSnack = a.kind() != null && a.kind().name().toUpperCase(java.util.Locale.ROOT).contains("SNACK");
      maxFactor[s] = isSnack ? SNACK_MAX_FACTOR : PortionScaler.MAX_FACTOR;
    }

    double[] grid = grid();
    double[] solution =
        sizable.size() <= 5
            ? bruteForce(perServing, configured, grid, additionOffset, maxFactor)
            : greedy(perServing, configured, grid, warmStart, additionOffset, maxFactor);

    for (int s = 0; s < sizable.size(); s++) {
      factors[sizable.get(s)] = BigDecimal.valueOf(solution[s]);
    }
  }

  /**
   * The day's verbatim in-meal additions summed into a {@link Macro}-ordinal-indexed vector — the
   * fixed baseline the mains are sized against. Mirrors {@code DailyMacroAggregator.addAdditions}:
   * additions are pre-sized to the residual, so they are summed as-is (NOT portion-scaled), and
   * satFat stays 0 (no per-serving field). A day with no additions yields an all-zero offset.
   */
  private static double[] dayAdditionOffset(List<SlotAssignment> assignments, List<Integer> dayIndices) {
    double[] offset = new double[Macro.values().length];
    for (int idx : dayIndices) {
      List<Addition> additions = assignments.get(idx).additions();
      if (additions == null) {
        continue;
      }
      for (Addition add : additions) {
        if (add == null || add.nutrition() == null) {
          continue;
        }
        NutritionPerServingDto n = add.nutrition();
        offset[Macro.CALORIES.ordinal()] += n.calories();
        offset[Macro.PROTEIN.ordinal()] += d(n.proteinG());
        offset[Macro.CARBS.ordinal()] += d(n.carbsG());
        offset[Macro.FAT.ordinal()] += d(n.fatG());
        offset[Macro.FIBRE.ordinal()] += d(n.fibreG());
        // SAT_FAT has no per-serving field → stays 0, matching the aggregator + perServingMacros.
      }
    }
    return offset;
  }

  // ---- pure solver (unit-testable, no Spring / DB / time) --------------------------------------

  /**
   * Total day objective with NO fixed additions baseline (mains-only) — the case unit tests pin.
   * Delegates to the offset-aware core with a zero offset.
   */
  static double objective(List<double[]> perServing, double[] servings, List<MacroTarget> macros) {
    return objective(perServing, servings, macros, ZERO_OFFSET);
  }

  /**
   * Total day objective: sum over configured macros of the direction-aware deviation penalty of the
   * day total = {@code fixedOffset[m] + Σ_i x_i × macro_i}. The {@code fixedOffset} is the day's
   * verbatim in-meal additions ({@link Macro}-ordinal indexed), so the mains are sized against the
   * target NET of the additions — making this arithmetic identical to {@code DailyMacroAggregator}'s
   * (mains × factor + additions verbatim). Pure function of its arguments.
   */
  static double objective(
      List<double[]> perServing, double[] servings, List<MacroTarget> macros, double[] fixedOffset) {
    double total = 0.0;
    for (int m = 0; m < macros.size(); m++) {
      double dayTotal = fixedOffset[m];
      for (int i = 0; i < perServing.size(); i++) {
        dayTotal += servings[i] * perServing.get(i)[m];
      }
      total += penalty(dayTotal, macros.get(m));
    }
    return total;
  }

  /**
   * Direction-aware per-macro deviation penalty, normalised by the target so macros on different
   * scales are comparable:
   *
   * <pre>{@code
   * pen = importance × ( wUnder × relu(target - total)/target + wOver × relu(total - target)/target )
   * }</pre>
   *
   * with (wUnder, wOver) by direction — FLOOR (3.0, 0.5), LIMIT (0.5, 3.0), BOUNDED (3.0, 3.0).
   */
  static double penalty(double total, MacroTarget macro) {
    double target = macro.target();
    if (target <= 0) {
      return 0.0; // skipped macro (defensive — configuredMacros already drops these)
    }
    double under = Math.max(0.0, target - total) / target;
    double over = Math.max(0.0, total - target) / target;
    return macro.importance() * (macro.wUnder() * under + macro.wOver() * over);
  }

  /** Exhaustive grid search over all slots — the global minimum for ≤ 5 slots ({@code ≤ 11^5}). */
  private static double[] bruteForce(
      List<double[]> perServing,
      List<MacroTarget> macros,
      double[] grid,
      double[] offset,
      double[] maxFactor) {
    int n = perServing.size();
    double[] current = new double[n];
    double[] best = new double[n];
    double[] bestObj = {Double.POSITIVE_INFINITY};
    bruteForceRec(perServing, macros, grid, offset, maxFactor, current, 0, best, bestObj);
    return best;
  }

  private static void bruteForceRec(
      List<double[]> perServing,
      List<MacroTarget> macros,
      double[] grid,
      double[] offset,
      double[] maxFactor,
      double[] current,
      int depth,
      double[] best,
      double[] bestObj) {
    if (depth == current.length) {
      double obj = objective(perServing, current, macros, offset);
      if (obj < bestObj[0]) {
        bestObj[0] = obj;
        System.arraycopy(current, 0, best, 0, current.length);
      }
      return;
    }
    for (double g : grid) {
      if (g > maxFactor[depth] + 1e-9) {
        continue; // slot-specific cap (e.g. snacks ≤ 1 serving)
      }
      current[depth] = g;
      bruteForceRec(perServing, macros, grid, offset, maxFactor, current, depth + 1, best, bestObj);
    }
  }

  /**
   * Greedy coordinate descent for larger days: each pass walks the slots and snaps each to its best
   * grid value given the others fixed; warm-started from the calorie-only factors. Deterministic and
   * monotonically non-increasing in the objective, so it converges in a few passes.
   */
  private static double[] greedy(
      List<double[]> perServing,
      List<MacroTarget> macros,
      double[] grid,
      List<Double> warmStart,
      double[] offset,
      double[] maxFactor) {
    int n = perServing.size();
    double[] servings = new double[n];
    for (int i = 0; i < n; i++) {
      servings[i] = Math.min(warmStart.get(i), maxFactor[i]); // honour the slot cap from the start
    }
    for (int pass = 0; pass < GREEDY_PASSES; pass++) {
      boolean changed = false;
      for (int i = 0; i < n; i++) {
        double bestG = servings[i];
        double bestObj = Double.POSITIVE_INFINITY;
        for (double g : grid) {
          if (g > maxFactor[i] + 1e-9) {
            continue; // slot-specific cap (e.g. snacks ≤ 1 serving)
          }
          servings[i] = g;
          double obj = objective(perServing, servings, macros, offset);
          if (obj < bestObj) {
            bestObj = obj;
            bestG = g;
          }
        }
        if (bestG != servings[i]) {
          changed = true;
        }
        servings[i] = bestG;
      }
      if (!changed) {
        break;
      }
    }
    return servings;
  }

  /** The serving grid {@code {0.5, 0.75, …, 3.0}} from {@link PortionScaler}'s physical bounds. */
  static double[] grid() {
    int steps = (int) Math.round((PortionScaler.MAX_FACTOR - PortionScaler.MIN_FACTOR) / PortionScaler.STEP);
    double[] out = new double[steps + 1];
    for (int i = 0; i <= steps; i++) {
      out[i] = PortionScaler.MIN_FACTOR + i * PortionScaler.STEP;
    }
    return out;
  }

  private static double snapToGrid(double v) {
    double snapped = Math.round(v / PortionScaler.STEP) * PortionScaler.STEP;
    return Math.max(PortionScaler.MIN_FACTOR, Math.min(PortionScaler.MAX_FACTOR, snapped));
  }

  // ---- target / nutrition resolution -----------------------------------------------------------

  /** Per-macro target + direction weights, as the solver consumes them. */
  record MacroTarget(
      Macro macro, double target, double importance, double wUnder, double wOver) {}

  /** The macro set the optimiser ranges over — mirrors {@code NutritionSubScore}'s macro fields. */
  enum Macro {
    CALORIES,
    PROTEIN,
    CARBS,
    FAT,
    FIBRE,
    SAT_FAT
  }

  /**
   * The configured macro targets for the primary eater, in the SAME set + direction source {@code
   * NutritionSubScore} uses: calories from {@code TargetsDto.calories()} and protein/carbs/fat/
   * fibre/satFat from the {@code MacroTargetDto} fields with their {@code direction()}. Each macro's
   * importance is the caller-supplied configured {@code weights} (so the objective honours "calories
   * + protein matter, carbs/fat barely do"); a macro with weight {@code 0} still enters the list but
   * with zero importance, so its penalty term vanishes (importance × penalty == 0) — it's ignored
   * without a special case. A macro whose target is null/0 is skipped. Empty when there are no
   * targets.
   */
  static List<MacroTarget> configuredMacros(TargetsDto targets, NutritionMacroWeights weights) {
    List<MacroTarget> out = new ArrayList<>();
    if (targets == null) {
      return out;
    }
    if (targets.calories() != null && targets.calories().dailyTarget() > 0) {
      addMacro(out, Macro.CALORIES, weights.calories().doubleValue(),
          targets.calories().dailyTarget(), targets.calories().direction());
    }
    addMacroTarget(out, Macro.PROTEIN, weights.protein().doubleValue(), targets.protein());
    addMacroTarget(out, Macro.CARBS, weights.carbs().doubleValue(), targets.carbs());
    addMacroTarget(out, Macro.FAT, weights.fat().doubleValue(), targets.fat());
    addMacroTarget(out, Macro.FIBRE, weights.fibre().doubleValue(), targets.fibre());
    addMacroTarget(out, Macro.SAT_FAT, weights.satFat().doubleValue(), targets.satFat());
    return out;
  }

  private static void addMacroTarget(
      List<MacroTarget> out, Macro macro, double importance, MacroTargetDto target) {
    if (target == null || target.targetG() == null || target.targetG().signum() <= 0) {
      return; // null / 0 target → skip this macro
    }
    addMacro(out, macro, importance, target.targetG().doubleValue(), target.direction());
  }

  private static void addMacro(
      List<MacroTarget> out,
      Macro macro,
      double importance,
      double target,
      EnforcementDirection direction) {
    double wUnder;
    double wOver;
    switch (direction == null ? EnforcementDirection.BOTH_BOUNDED : direction) {
      case LOWER_FLOOR -> {
        wUnder = FLOOR_UNDER;
        wOver = FLOOR_OVER;
      }
      case UPPER_LIMIT -> {
        wUnder = LIMIT_UNDER;
        wOver = LIMIT_OVER;
      }
      default -> {
        wUnder = BOUNDED_UNDER;
        wOver = BOUNDED_OVER;
      }
    }
    out.add(new MacroTarget(macro, target, importance, wUnder, wOver));
  }

  /**
   * Per-serving macro vector for {@code a}, index-aligned with the {@link Macro} ordinal order, or
   * {@code null} when the slot has no resolvable per-serving nutrition (→ the slot is fixed at 1.0).
   * Saturated fat is USDA-derived into the per-serving {@code micros} map (key {@link
   * DailyMacroTotals#SATURATED_FAT_KEY}); read it here so the optimiser can size servings to keep
   * saturated fat under its limit (0 when the recipe carries no saturated-fat figure).
   */
  private double[] perServingMacros(SlotAssignment a, Map<UUID, RecipeDto> byRecipeId) {
    NutritionPerServingDto n = nutritionFor(a, byRecipeId);
    if (n == null) {
      return null;
    }
    double[] m = new double[Macro.values().length];
    m[Macro.CALORIES.ordinal()] = n.calories();
    m[Macro.PROTEIN.ordinal()] = d(n.proteinG());
    m[Macro.CARBS.ordinal()] = d(n.carbsG());
    m[Macro.FAT.ordinal()] = d(n.fatG());
    m[Macro.FIBRE.ordinal()] = d(n.fibreG());
    BigDecimal satFat =
        n.micros() == null ? null : n.micros().get(DailyMacroTotals.SATURATED_FAT_KEY);
    m[Macro.SAT_FAT.ordinal()] = satFat == null ? 0.0 : satFat.doubleValue();
    return m;
  }

  private NutritionPerServingDto nutritionFor(SlotAssignment a, Map<UUID, RecipeDto> byRecipeId) {
    RecipeDto recipe = a.recipeId() == null ? null : byRecipeId.get(a.recipeId());
    if (recipe == null) {
      return null;
    }
    RecipeVersionDto body = recipe.currentVersionBody();
    return body == null ? null : body.nutritionPerServing();
  }

  /**
   * The primary eater's {@code TargetsDto}, resolved the same way {@code PerMealCalorieTargets} does
   * (first eater of the first slot skeleton → {@code nutritionByUserId}). {@code null} when there is
   * no eater / no targets row.
   */
  private static TargetsDto primaryTargets(PlanCompositionContext ctx) {
    if (ctx == null || ctx.nutritionByUserId() == null || ctx.slotSkeletons() == null) {
      return null;
    }
    UUID primary =
        ctx.slotSkeletons().stream()
            .map(MealSlotSkeleton::eaters)
            .filter(e -> e != null && !e.isEmpty())
            .map(e -> e.get(0))
            .findFirst()
            .orElse(null);
    return primary == null ? null : ctx.nutritionByUserId().get(primary);
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

  private static double d(BigDecimal v) {
    return v == null ? 0.0 : v.doubleValue();
  }
}
