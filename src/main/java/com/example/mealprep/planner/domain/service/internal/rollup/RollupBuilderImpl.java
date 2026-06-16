package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.MicroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.DailyRollupDocument;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.NutritionCoverageDocument;
import com.example.mealprep.planner.api.dto.NutritionTargetCoverageDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.WeeklyRollupDocument;
import com.example.mealprep.planner.domain.service.internal.scoring.NutritionFloorGate;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stage B rollup builder (planner-01f). Walks an already-loaded candidate plan against the loaded
 * composition context and emits the flat {@link RollupSummaryDocument}. Deterministic, no DB.
 *
 * <p>Aggregation is delegated to the shared {@link DailyMacroAggregator} / {@link
 * DailyCostAggregator} / {@link WeeklyCostConfidence} helpers (also used by the refactored 01e
 * {@code NutritionFloorGate} / {@code CostSubScore}) so the gate, the cost sub-score and the rollup
 * never drift.
 *
 * <p><b>Codebase divergences vs. the ticket's verbatim snippets</b> (the snippets assumed an
 * idealised LLD shape that does not match this codebase):
 *
 * <ul>
 *   <li>{@code RecipeVersionDto} has no {@code nutritionPerServing} → macros are 0 (01e behaviour
 *       preserved). The {@code staleIngredientCount} counts distinct recipes whose {@code
 *       RecipeDto.nutritionStatus != CALCULATED} (the codebase's nutrition-pending signal in lieu
 *       of a null JsonNode).
 *   <li>{@code SlotAssignment} has no {@code batchCookSessionId} field → {@code batchCookSessions}
 *       is 0 (no batch grouping is modelled on the assignment yet; 01j wires it).
 *   <li>{@code totalTimeMin} uses {@code recipe.currentVersionBody().metadata().totalTimeMins()}.
 *   <li>{@code varietyIndex} is read from {@code plan.scoreResult().breakdown().variety()} (never
 *       recomputed); {@code BigDecimal.ZERO} when {@link ScoreResult} is absent (fixtures that
 *       build {@code CandidatePlan} directly — ticket gotcha #8).
 *   <li>Violations v1: {@code "slot <kind>@<date> is unfilled"} per missing recipe, plus {@code
 *       "hard floor breach"} when the re-run {@link NutritionFloorGate} returns false.
 * </ul>
 */
@Component
class RollupBuilderImpl implements RollupBuilder {

  private final DailyMacroAggregator macroAggregator;
  private final DailyCostAggregator costAggregator;
  private final WeeklyCostConfidence costConfidence;
  private final NutritionFloorGate floorGate;

  RollupBuilderImpl(
      DailyMacroAggregator macroAggregator,
      DailyCostAggregator costAggregator,
      WeeklyCostConfidence costConfidence,
      NutritionFloorGate floorGate) {
    this.macroAggregator = macroAggregator;
    this.costAggregator = costAggregator;
    this.costConfidence = costConfidence;
    this.floorGate = floorGate;
  }

  @Override
  public RollupSummaryDocument build(CandidatePlan plan, PlanCompositionContext ctx) {
    Map<LocalDate, DailyMacroTotals> dailyMacros = macroAggregator.aggregateByDate(plan, ctx);
    Map<LocalDate, BigDecimal> dailyCosts = costAggregator.aggregateByDate(plan, ctx);
    Map<LocalDate, Integer> dailyTotalTimes = aggregateTotalTime(plan, ctx);
    Map<LocalDate, List<String>> dailyViolations = computeDailyViolations(plan, ctx);

    List<DailyRollupDocument> daily =
        dailyMacros.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                e ->
                    new DailyRollupDocument(
                        e.getKey(),
                        e.getValue().kcal(),
                        e.getValue().proteinG().setScale(1, RoundingMode.HALF_UP),
                        e.getValue().fatG().setScale(1, RoundingMode.HALF_UP),
                        e.getValue().carbsG().setScale(1, RoundingMode.HALF_UP),
                        e.getValue().fibreG().setScale(1, RoundingMode.HALF_UP),
                        dailyCosts.getOrDefault(e.getKey(), BigDecimal.ZERO),
                        dailyTotalTimes.getOrDefault(e.getKey(), 0),
                        dailyViolations.getOrDefault(e.getKey(), List.of())))
            .toList();

    WeeklyRollupDocument weekly = buildWeekly(plan, ctx, daily);
    NutritionCoverageDocument coverage = computeNutritionCoverage(dailyMacros, ctx);
    return new RollupSummaryDocument(daily, weekly, coverage);
  }

  private WeeklyRollupDocument buildWeekly(
      CandidatePlan plan, PlanCompositionContext ctx, List<DailyRollupDocument> daily) {
    int kcalTotal = daily.stream().mapToInt(DailyRollupDocument::kcal).sum();
    int n = Math.max(1, daily.size());

    BigDecimal proteinAvg = average(daily, DailyRollupDocument::proteinG, n);
    BigDecimal fatAvg = average(daily, DailyRollupDocument::fatG, n);
    BigDecimal carbsAvg = average(daily, DailyRollupDocument::carbsG, n);

    BigDecimal costTotal =
        daily.stream().map(DailyRollupDocument::costGbp).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal confidence = costConfidence.compute(plan, ctx);

    int staleCount = countStaleRecipes(plan, ctx);
    BigDecimal varietyIndex = resolveVarietyIndex(plan);
    int batchSessions =
        0; // no batchCookSessionId on SlotAssignment in this codebase (01j wires it)

    List<String> constraintViolations = aggregateConstraintViolations(plan, ctx, daily);

    return new WeeklyRollupDocument(
        kcalTotal,
        proteinAvg,
        fatAvg,
        carbsAvg,
        costTotal,
        confidence,
        staleCount,
        varietyIndex,
        batchSessions,
        constraintViolations);
  }

  private BigDecimal average(
      List<DailyRollupDocument> daily,
      java.util.function.Function<DailyRollupDocument, BigDecimal> field,
      int n) {
    return daily.stream()
        .map(field)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP);
  }

  // ---- nutrition coverage (plan projected vs the primary user's targets) ----------------------

  private static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");

  /**
   * Project the plan's per-person daily-average nutrition and compare each configured target. Daily
   * averages over the plan's days; per-person (one serving/slot, per {@link DailyMacroAggregator}).
   * {@code null} when the primary user has no targets row.
   */
  private NutritionCoverageDocument computeNutritionCoverage(
      Map<LocalDate, DailyMacroTotals> dailyMacros, PlanCompositionContext ctx) {
    UUID primary = primaryUserId(ctx);
    TargetsDto targets = primary == null ? null : ctx.nutritionByUserId().get(primary);
    if (targets == null || dailyMacros.isEmpty()) {
      return null;
    }
    Collection<DailyMacroTotals> days = dailyMacros.values();
    BigDecimal n = BigDecimal.valueOf(days.size());

    BigDecimal kcalAvg = sumInt(days, DailyMacroTotals::kcal).divide(n, 0, RoundingMode.HALF_UP);
    BigDecimal proteinAvg = sumBd(days, DailyMacroTotals::proteinG).divide(n, 1, RoundingMode.HALF_UP);
    BigDecimal carbsAvg = sumBd(days, DailyMacroTotals::carbsG).divide(n, 1, RoundingMode.HALF_UP);
    BigDecimal fatAvg = sumBd(days, DailyMacroTotals::fatG).divide(n, 1, RoundingMode.HALF_UP);
    BigDecimal fibreAvg = sumBd(days, DailyMacroTotals::fibreG).divide(n, 1, RoundingMode.HALF_UP);

    Map<String, BigDecimal> microAvg = new LinkedHashMap<>();
    for (DailyMacroTotals d : days) {
      if (d.micros() == null) {
        continue;
      }
      for (Map.Entry<String, BigDecimal> e : d.micros().entrySet()) {
        microAvg.merge(e.getKey(), e.getValue(), BigDecimal::add);
      }
    }
    microAvg.replaceAll((k, v) -> v.divide(n, 3, RoundingMode.HALF_UP));

    List<NutritionTargetCoverageDocument> macros = new ArrayList<>();
    if (targets.calories() != null && targets.calories().dailyTarget() > 0) {
      macros.add(
          macroCoverage(
              "calories",
              "kcal",
              BigDecimal.valueOf(targets.calories().dailyTarget()),
              kcalAvg,
              targets.calories().direction()));
    }
    addMacroCoverage(macros, "protein", targets.protein(), proteinAvg);
    addMacroCoverage(macros, "carbs", targets.carbs(), carbsAvg);
    addMacroCoverage(macros, "fat", targets.fat(), fatAvg);
    addMacroCoverage(macros, "fibre", targets.fibre(), fibreAvg);

    List<NutritionTargetCoverageDocument> micros = new ArrayList<>();
    if (targets.microTargets() != null) {
      for (MicroTargetDto m : targets.microTargets()) {
        if (m == null || m.nutrientKey() == null) {
          continue;
        }
        boolean hasFloor = m.targetValue() != null;
        boolean hasCap = m.upperLimit() != null;
        if (!hasFloor && !hasCap) {
          continue;
        }
        BigDecimal actual = microAvg.getOrDefault(m.nutrientKey(), BigDecimal.ZERO);
        boolean met =
            (!hasFloor || actual.compareTo(m.targetValue()) >= 0)
                && (!hasCap || actual.compareTo(m.upperLimit()) <= 0);
        micros.add(
            new NutritionTargetCoverageDocument(
                m.nutrientKey(),
                microUnit(m.nutrientKey()),
                hasFloor ? m.targetValue() : m.upperLimit(),
                actual,
                hasFloor ? "LOWER_FLOOR" : "UPPER_LIMIT",
                met));
      }
    }
    int macrosMet = (int) macros.stream().filter(NutritionTargetCoverageDocument::met).count();
    int microsMet = (int) micros.stream().filter(NutritionTargetCoverageDocument::met).count();
    return new NutritionCoverageDocument(
        macros, micros, macrosMet, macros.size(), microsMet, micros.size());
  }

  /** First eater of the first slot skeleton, preferring one that actually has a targets row. */
  private UUID primaryUserId(PlanCompositionContext ctx) {
    UUID firstEater =
        ctx.slotSkeletons() == null
            ? null
            : ctx.slotSkeletons().stream()
                .map(MealSlotSkeleton::eaters)
                .filter(e -> e != null && !e.isEmpty())
                .map(e -> e.get(0))
                .findFirst()
                .orElse(null);
    if (firstEater != null && ctx.nutritionByUserId().containsKey(firstEater)) {
      return firstEater;
    }
    return ctx.nutritionByUserId().keySet().stream().findFirst().orElse(firstEater);
  }

  private static void addMacroCoverage(
      List<NutritionTargetCoverageDocument> out,
      String key,
      MacroTargetDto target,
      BigDecimal actual) {
    if (target == null || target.targetG() == null) {
      return;
    }
    out.add(macroCoverage(key, "g", target.targetG(), actual, target.direction()));
  }

  private static NutritionTargetCoverageDocument macroCoverage(
      String key, String unit, BigDecimal target, BigDecimal actual, EnforcementDirection dir) {
    EnforcementDirection d = dir == null ? EnforcementDirection.BOTH_BOUNDED : dir;
    return new NutritionTargetCoverageDocument(
        key, unit, target, actual, d.name(), macroMet(d, actual, target));
  }

  private static boolean macroMet(
      EnforcementDirection dir, BigDecimal actual, BigDecimal target) {
    if (target == null || target.signum() == 0) {
      return true;
    }
    return switch (dir) {
      case LOWER_FLOOR -> actual.compareTo(target) >= 0;
      case UPPER_LIMIT -> actual.compareTo(target) <= 0;
      case BOTH_BOUNDED ->
          actual.subtract(target).abs().compareTo(target.multiply(TEN_PERCENT)) <= 0;
    };
  }

  private static String microUnit(String key) {
    if (key.endsWith("_mcg")) {
      return "mcg";
    }
    if (key.endsWith("_mg")) {
      return "mg";
    }
    return "";
  }

  private static BigDecimal sumInt(
      Collection<DailyMacroTotals> vals, java.util.function.ToIntFunction<DailyMacroTotals> f) {
    int sum = 0;
    for (DailyMacroTotals d : vals) {
      sum += f.applyAsInt(d);
    }
    return BigDecimal.valueOf(sum);
  }

  private static BigDecimal sumBd(
      Collection<DailyMacroTotals> vals,
      java.util.function.Function<DailyMacroTotals, BigDecimal> f) {
    BigDecimal sum = BigDecimal.ZERO;
    for (DailyMacroTotals d : vals) {
      BigDecimal v = f.apply(d);
      if (v != null) {
        sum = sum.add(v);
      }
    }
    return sum;
  }

  private Map<LocalDate, Integer> aggregateTotalTime(
      CandidatePlan plan, PlanCompositionContext ctx) {
    Map<LocalDate, Integer> byDate = new TreeMap<>();
    if (plan == null || plan.assignments() == null) {
      return new LinkedHashMap<>();
    }
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);
    for (SlotAssignment a : plan.assignments()) {
      LocalDate date = a.onDate();
      if (date == null) {
        continue;
      }
      byDate.putIfAbsent(date, 0);
      RecipeDto recipe = byRecipeId.get(a.recipeId());
      if (recipe == null
          || recipe.currentVersionBody() == null
          || recipe.currentVersionBody().metadata() == null) {
        continue;
      }
      byDate.merge(date, recipe.currentVersionBody().metadata().totalTimeMins(), Integer::sum);
    }
    return new LinkedHashMap<>(byDate);
  }

  private Map<LocalDate, List<String>> computeDailyViolations(
      CandidatePlan plan, PlanCompositionContext ctx) {
    Map<LocalDate, List<String>> byDate = new TreeMap<>();
    if (plan == null || plan.assignments() == null) {
      return new LinkedHashMap<>();
    }
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);
    for (SlotAssignment a : plan.assignments()) {
      LocalDate date = a.onDate();
      if (date == null) {
        continue;
      }
      byDate.computeIfAbsent(date, d -> new ArrayList<>());
      if (byRecipeId.get(a.recipeId()) == null) {
        byDate.get(date).add("slot " + a.kind() + "@" + date + " is unfilled");
      }
    }
    Map<LocalDate, List<String>> out = new LinkedHashMap<>();
    for (Map.Entry<LocalDate, List<String>> e : byDate.entrySet()) {
      out.put(e.getKey(), List.copyOf(e.getValue()));
    }
    return out;
  }

  private List<String> aggregateConstraintViolations(
      CandidatePlan plan, PlanCompositionContext ctx, List<DailyRollupDocument> daily) {
    List<String> all = new ArrayList<>();
    for (DailyRollupDocument d : daily) {
      all.addAll(d.violations());
    }
    if (plan != null
        && plan.assignments() != null
        && !plan.assignments().isEmpty()
        && !floorGate.passes(plan, ctx)) {
      all.add("hard floor breach");
    }
    return List.copyOf(all);
  }

  private int countStaleRecipes(CandidatePlan plan, PlanCompositionContext ctx) {
    if (plan == null || plan.assignments() == null) {
      return 0;
    }
    Map<UUID, RecipeDto> byRecipeId = indexRecipes(ctx);
    java.util.Set<UUID> stale = new java.util.HashSet<>();
    for (SlotAssignment a : plan.assignments()) {
      RecipeDto recipe = byRecipeId.get(a.recipeId());
      if (recipe != null && recipe.nutritionStatus() != NutritionStatus.CALCULATED) {
        stale.add(recipe.id());
      }
    }
    return stale.size();
  }

  private BigDecimal resolveVarietyIndex(CandidatePlan plan) {
    if (plan == null || plan.scoreResult() == null || plan.scoreResult().breakdown() == null) {
      return BigDecimal.ZERO;
    }
    return Objects.requireNonNullElse(plan.scoreResult().breakdown().variety(), BigDecimal.ZERO);
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
